package io.nekohasekai.sagernet.fmt.shadowsocks

import com.google.common.io.BaseEncoding
import io.nekohasekai.sagernet.fmt.v2ray.StandardV2RayBean
import io.nekohasekai.sagernet.fmt.v2ray.XhttpExtraConverter
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import io.nekohasekai.sagernet.fmt.v2ray.buildSingBoxOutboundStreamSettings
import io.nekohasekai.sagernet.fmt.v2ray.buildSingBoxOutboundTLS
import io.nekohasekai.sagernet.fmt.v2ray.parseDuckSoft
import io.nekohasekai.sagernet.ktx.*
import moe.matsuri.nb4a.SingBoxOptions
import moe.matsuri.nb4a.utils.JavaUtil
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject

// Keep in sync with the sing-shadowsocks2 method registries used by sing-box.
private val supportedShadowsocksMethods = setOf(
    "none",
    "2022-blake3-aes-128-gcm",
    "2022-blake3-aes-256-gcm",
    "2022-blake3-chacha20-poly1305",
    "aes-128-gcm",
    "aes-192-gcm",
    "aes-256-gcm",
    "chacha20-ietf-poly1305",
    "xchacha20-ietf-poly1305",
    "aes-128-ctr",
    "aes-192-ctr",
    "aes-256-ctr",
    "aes-128-cfb",
    "aes-192-cfb",
    "aes-256-cfb",
    "rc4-md5",
    "chacha20-ietf",
    "xchacha20",
)

private fun String.decodeShadowsocksBase64(): String = String(
    BaseEncoding.base64().decode(filterNot(Char::isWhitespace).replace('-', '+').replace('_', '/'))
)

private fun normalizeShadowsocksMethod(method: String?): String {
    return method.takeIf(supportedShadowsocksMethods::contains) ?: "none"
}

// Transports offered by the profile editor and understood by the link parser.
internal val shadowsocksTransports = setOf("tcp", "ws", "grpc", "httpupgrade", "xhttp")

/** Copies the transport and TLS settings of [stream] into this profile. */
fun ShadowsocksBean.applyStreamSettings(stream: StandardV2RayBean) {
    require(stream.type.orEmpty().ifBlank { "tcp" } in shadowsocksTransports) {
        "unsupported Shadowsocks transport: ${stream.type}"
    }
    require(stream.security.isNullOrBlank() || stream.security in setOf("none", "tls")) {
        "unsupported Shadowsocks security: ${stream.security}"
    }
    type = stream.type.orEmpty().ifBlank { "tcp" }
    host = stream.host.orEmpty()
    path = stream.path.orEmpty()
    security = if (stream.security == "tls") "tls" else "none"
    sni = stream.sni.orEmpty()
    alpn = stream.alpn.orEmpty()
    certificates = stream.certificates.orEmpty()
    utlsFingerprint = stream.utlsFingerprint.orEmpty()
    allowInsecure = stream.allowInsecure == true
    xhttpMode = stream.xhttpMode.orEmpty().ifBlank { "auto" }
    xhttpExtra = stream.xhttpExtra.orEmpty()
    if (type == "xhttp") {
        val transport = JSONObject(JavaUtil.gson.toJson(buildSingBoxOutboundStreamSettings(stream)))
        listOf("type", "host", "path", "mode").forEach(transport::remove)
        xhttpExtra = if (transport.length() > 0) transport.toString() else ""
    }
    tlsCurvePreferences = stream.tlsCurvePreferences
    tlsCertificatePublicKeySha256 = stream.tlsCertificatePublicKeySha256
    tlsXrayCertificateSha256 = stream.tlsXrayCertificateSha256
    tlsClientCertificate = stream.tlsClientCertificate
    tlsClientKey = stream.tlsClientKey
    echQueryServerName = stream.echQueryServerName
    tlsHandshakeTimeout = stream.tlsHandshakeTimeout
    val custom = stream.customOutboundJson.takeUnless { it.isNullOrBlank() }?.let(::JSONObject) ?: JSONObject()
    if (type == "ws") {
        val transport = JSONObject(JavaUtil.gson.toJson(buildSingBoxOutboundStreamSettings(stream)))
        listOf("type", "headers", "path").forEach(transport::remove)
        if (transport.length() > 0) custom.put("transport", transport)
        // The shared builder extracts ?ed= from the WebSocket path.
        path = path.substringBefore("?ed=")
    }
    if (stream.enableECH == true) {
        custom.put("tls", JSONObject().put("ech", JSONObject().put("enabled", true)
            .put("config", org.json.JSONArray(stream.echConfig.orEmpty().lines().filter(String::isNotBlank)))))
    }
    if (custom.length() > 0) customOutboundJson = custom.toString()
    validateStreamSettings()
}

/**
 * Reads Xray style stream parameters (type, security, host, path, serviceName,
 * sni, alpn, fp, mode) from a Shadowsocks share link. The VLESS/Trojan link
 * parser is reused, so every parameter is read exactly like it is for those
 * protocols.
 */
private fun ShadowsocksBean.applyStreamParameters(link: HttpUrl) {
    if (link.queryParameter("type").isNullOrBlank() &&
        link.queryParameter("security").isNullOrBlank()
    ) return
    val security = link.queryParameter("security").orEmpty().ifBlank { "none" }
    require(security == "none" || security == "tls") {
        "unsupported Shadowsocks security: $security"
    }
    val stream = VMessBean().apply {
        parseDuckSoft(link)
        initializeDefaultValues()
    }
    if (stream.type !in shadowsocksTransports) {
        throw IllegalArgumentException("unsupported Shadowsocks transport: ${stream.type}")
    }
    if (stream.security != "none" && stream.security != "tls") {
        error("unsupported Shadowsocks security: ${stream.security}")
    }
    link.queryParameter("eh")?.takeIf { stream.type == "ws" }?.let {
        stream.earlyDataHeaderName = it
    }
    applyStreamSettings(stream)
    // Keep the complete extra object: the shared parser moves some fields to
    // GUI properties which Shadowsocks does not store separately.
    link.queryParameter("extra")?.takeIf { type == "xhttp" }?.let {
        xhttpExtra = XhttpExtraConverter.xrayToSingBox(it)
    }
    link.queryParameter("authority")?.takeIf { type == "grpc" && it.isNotBlank() }?.let {
        customOutboundJson = JSONObject().put("transport", JSONObject().put("authority", it)).toString()
    }
    require(plugin.isNullOrBlank() || plugin == "none" || !hasStreamSettings()) {
        "plugin cannot be combined with tls or transport"
    }
}

/** A transient bean so the transport and TLS builders of VLESS/Trojan can be reused as they are. */
private fun ShadowsocksBean.toStreamBean(): StandardV2RayBean = VMessBean().also { stream ->
    stream.serverAddress = serverAddress
    stream.serverPort = serverPort
    stream.type = type.orEmpty().ifBlank { "tcp" }
    stream.host = host
    stream.path = path
    stream.security = security.orEmpty().ifBlank { "none" }
    stream.sni = sni
    stream.alpn = alpn
    stream.certificates = certificates
    stream.utlsFingerprint = utlsFingerprint
    stream.allowInsecure = allowInsecure
    stream.xhttpMode = xhttpMode
    stream.xhttpExtra = xhttpExtra
    // Shared TLS options exist on every profile; the TLS builder reads them from the bean it gets.
    stream.tlsCurvePreferences = tlsCurvePreferences
    stream.tlsCertificatePublicKeySha256 = tlsCertificatePublicKeySha256
    stream.tlsXrayCertificateSha256 = tlsXrayCertificateSha256
    stream.tlsClientCertificate = tlsClientCertificate
    stream.tlsClientKey = tlsClientKey
    stream.echQueryServerName = echQueryServerName
    stream.tlsHandshakeTimeout = tlsHandshakeTimeout
    stream.initializeDefaultValues()
}

fun ShadowsocksBean.fixPluginName() {
    if (plugin?.startsWith("simple-obfs") == true) {
        plugin = plugin.replaceFirst("simple-obfs", "obfs-local")
    }
}

fun parseShadowsocks(url: String): ShadowsocksBean {

    if (url.substringBefore("#").contains("@")) {
        var link = url.replace("ss://", "https://").toHttpUrlOrNull() ?: error(
            "invalid ss-android link $url"
        )

        if (link.username.isBlank()) { // fix justmysocks's shit link
            link = (("https://" + url.substringAfter("ss://")
                .substringBefore("#")
                .decodeShadowsocksBase64()).toHttpUrlOrNull()
                ?: error("invalid jms link $url")
                    ).newBuilder().fragment(url.substringAfter("#")).build()
        }

        // ss-android style

        if (link.password.isNotBlank()) {
            return ShadowsocksBean().apply {
                serverAddress = link.host
                serverPort = link.port
                method = normalizeShadowsocksMethod(link.username)
                password = link.password
                plugin = link.queryParameter("plugin") ?: ""
                name = link.fragment
                fixPluginName()
                applyStreamParameters(link)
            }
        }

        val methodAndPswd = link.username.decodeShadowsocksBase64()

        return ShadowsocksBean().apply {
            serverAddress = link.host
            serverPort = link.port
            method = normalizeShadowsocksMethod(methodAndPswd.substringBefore(":"))
            password = methodAndPswd.substringAfter(":")
            plugin = link.queryParameter("plugin") ?: ""
            name = link.fragment
            fixPluginName()
            applyStreamParameters(link)
        }
    } else {
        // v2rayN style
        val payload = url.substringAfter("ss://").substringBefore("#")
        val decoded = payload.substringBefore("?").decodeShadowsocksBase64()
        val query = payload.substringAfter("?", "")
        val link = ("https://" + decoded + if (query.isNotBlank()) "?$query" else "").toHttpUrlOrNull() ?: error("invalid v2rayN link $url")

        return ShadowsocksBean().apply {
            serverAddress = link.host
            serverPort = link.port
            method = normalizeShadowsocksMethod(link.username)
            password = link.password
            plugin = link.queryParameter("plugin").orEmpty()
            fixPluginName()
            applyStreamParameters(link)
            val remarks = java.net.URLDecoder.decode(url.substringAfter("#", ""), "UTF-8")
            if (remarks.isNotBlank()) name = remarks
        }
    }

}

fun ShadowsocksBean.toUri(): String {

    val builder = linkBuilder().username(BaseEncoding.base64Url().omitPadding().encode("$method:$password".toByteArray()))
        .host(serverAddress)
        .port(serverPort)

    if (plugin.isNotBlank()) {
        builder.addQueryParameter("plugin", plugin)
    }

    validateStreamSettings()
    if (hasStreamSettings()) {
        // Resolve transport overrides exactly as configuration export does.
        // TLS link parameters remain profile values, independent of global TLS policy.
        val options = buildSingBoxOutboundStreamSettings(toStreamBean())?.apply {
            _hack_custom_config = customOutboundJson.takeIf { it.isNotBlank() }
                ?.let(::JSONObject)?.optJSONObject("transport")?.toString().orEmpty()
        }
        val transport = options?.let { JSONObject(it.asMap()) }
        val transportType = transport?.optString("type").orEmpty().ifBlank { "tcp" }
        require(transportType in shadowsocksTransports) { "unsupported Shadowsocks transport: $transportType" }
        builder.addQueryParameter("type", transportType)
        when (transportType) {
            "ws", "httpupgrade", "xhttp" -> {
                val transportHost = if (transportType == "ws") {
                    transport?.optJSONObject("headers")?.optString("Host").orEmpty()
                } else transport?.optString("host").orEmpty()
                val transportPath = transport?.optString("path").orEmpty()
                if (transportHost.isNotBlank()) builder.addQueryParameter("host", transportHost)
                if (transportPath.isNotBlank()) builder.addQueryParameter("path", transportPath)
                if (transportType == "ws") {
                    val earlyData = transport?.optInt("max_early_data") ?: 0
                    if (earlyData > 0) builder.addQueryParameter("ed", earlyData.toString())
                    transport?.optString("early_data_header_name")?.takeIf(String::isNotBlank)?.let {
                        builder.addQueryParameter("eh", it)
                    }
                }
                if (transportType == "xhttp" && transport != null) {
                    builder.addQueryParameter("mode", transport.optString("mode", "auto"))
                    val extra = JSONObject(transport.toString())
                    listOf("type", "host", "path", "mode").forEach(extra::remove)
                    if (extra.length() > 0) {
                        builder.addQueryParameter("extra", XhttpExtraConverter.singBoxToXray(extra.toString()))
                    }
                }
            }

            "grpc" -> {
                transport?.optString("service_name")?.takeIf(String::isNotBlank)?.let {
                    builder.addQueryParameter("serviceName", it)
                }
                transport?.optString("authority")?.takeIf(String::isNotBlank)?.let {
                    builder.addQueryParameter("authority", it)
                }
            }
        }
        if (security == "tls") {
            builder.addQueryParameter("security", "tls")
            if (sni.isNotBlank()) builder.addQueryParameter("sni", sni)
            if (alpn.isNotBlank()) builder.addQueryParameter("alpn", alpn.replace("\n", ","))
            if (certificates.isNotBlank()) builder.addQueryParameter("cert", certificates)
            if (allowInsecure) builder.addQueryParameter("allowInsecure", "1")
            if (utlsFingerprint.isNotBlank()) builder.addQueryParameter("fp", utlsFingerprint)
        }
    }

    if (name.isNotBlank()) {
        builder.fragment(name)
    }

    return builder.toLink("ss").replace("$serverPort/", "$serverPort")

}

fun JSONObject.parseShadowsocks(): ShadowsocksBean {
    return ShadowsocksBean().apply {
        serverAddress = getStr("server")
        serverPort = getIntNya("server_port")
        password = getStr("password")
        method = normalizeShadowsocksMethod(getStr("method"))
        name = optString("remarks", optString("tag", ""))

        val pId = getStr("plugin")
        if (!pId.isNullOrBlank()) {
            plugin = pId + ";" + optString("plugin_opts", "")
        }
        fixPluginName()
        val transport = optJSONObject("transport")
        val tls = optJSONObject("tls")
        type = transport?.optString("type").orEmpty().ifBlank { "tcp" }
        require(type in shadowsocksTransports) { "unsupported Shadowsocks transport: $type" }
        security = if (tls?.optBoolean("enabled") == true) "tls" else "none"
        require(tls?.optJSONObject("reality")?.optBoolean("enabled") != true) {
            "unsupported Shadowsocks security: reality"
        }
        host = transport?.optString("host").orEmpty()
        if (type == "ws") host = transport?.optJSONObject("headers")?.let { headers ->
            headers.keys().asSequence().firstOrNull { it.equals("Host", true) }?.let(headers::optString)
        }.orEmpty()
        path = transport?.optString(if (type == "grpc") "service_name" else "path").orEmpty()
        xhttpMode = transport?.optString("mode").orEmpty().ifBlank { "auto" }
        if (type == "xhttp" && transport != null) {
            val extra = JSONObject(transport.toString())
            listOf("type", "host", "path", "mode").forEach(extra::remove)
            xhttpExtra = extra.toString()
        }
        sni = tls?.optString("server_name").orEmpty()
        alpn = tls?.optJSONArray("alpn")?.let { array ->
            (0 until array.length()).joinToString("\n") { array.getString(it) }
        }.orEmpty()
        certificates = when (val certificate = tls?.opt("certificate")) {
            is org.json.JSONArray -> (0 until certificate.length()).joinToString("\n") { certificate.getString(it) }
            is String -> certificate
            else -> ""
        }
        allowInsecure = tls?.optBoolean("insecure") == true
        utlsFingerprint = tls?.optJSONObject("utls")?.takeIf { it.optBoolean("enabled") }
            ?.optString("fingerprint").orEmpty()
        // Keep options without editor fields, such as WS headers, early data,
        // gRPC authority, ECH and client certificates, in the outbound override.
        val custom = JSONObject()
        transport?.let {
            val remaining = JSONObject(it.toString())
            listOf("type", "host", "path", "service_name", "mode").forEach(remaining::remove)
            if (type == "ws") remaining.optJSONObject("headers")?.let { headers ->
                headers.keys().asSequence().toList().filter { it.equals("Host", true) }.forEach(headers::remove)
                if (headers.length() == 0) remaining.remove("headers")
            }
            if (type != "xhttp" && remaining.length() > 0) custom.put("transport", remaining)
        }
        tls?.let {
            val remaining = JSONObject(it.toString())
            listOf("enabled", "server_name", "alpn", "certificate", "insecure", "utls").forEach(remaining::remove)
            if (remaining.length() > 0) custom.put("tls", remaining)
        }
        if (custom.length() > 0) customOutboundJson = custom.toString()
        require(plugin.isNullOrBlank() || plugin == "none" || !hasStreamSettings()) {
            "plugin cannot be combined with tls or transport"
        }
    }
}

private fun ShadowsocksBean.validateStreamSettings() {
    require(type.orEmpty().ifBlank { "tcp" } in shadowsocksTransports) { "unsupported Shadowsocks transport: $type" }
    require(security.isNullOrBlank() || security == "none" || security == "tls") {
        "unsupported Shadowsocks security: $security"
    }
    require(plugin.isNullOrBlank() || plugin == "none" || !hasStreamSettings()) {
        "plugin cannot be combined with tls or transport"
    }
}

fun buildSingBoxOutboundShadowsocksBean(bean: ShadowsocksBean): SingBoxOptions.Outbound_ShadowsocksOptions {
    bean.validateStreamSettings()
    return SingBoxOptions.Outbound_ShadowsocksOptions().apply {
        type = "shadowsocks"
        server = bean.serverAddress
        server_port = bean.serverPort
        password = bean.password
        method = bean.method
        if (bean.plugin.isNotBlank()) {
            plugin = bean.plugin.substringBefore(";")
            plugin_opts = bean.plugin.substringAfter(";")
            if (plugin == "none") {
                plugin = null
                plugin_opts = null
            }
        }
        if (bean.hasStreamSettings()) {
            val stream = bean.toStreamBean()
            tls = buildSingBoxOutboundTLS(stream)
            transport = buildSingBoxOutboundStreamSettings(stream)
        }
    }
}
