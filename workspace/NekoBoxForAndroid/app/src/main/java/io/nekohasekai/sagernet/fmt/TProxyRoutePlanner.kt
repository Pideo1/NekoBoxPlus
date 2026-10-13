package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.IPv6Mode

internal fun tproxyDeviceTags(ipv6Mode: Int) = buildList {
    if (ipv6Mode != IPv6Mode.ONLY) add("tproxy-in4")
    if (ipv6Mode != IPv6Mode.DISABLE) add("tproxy-in6")
}

internal fun tproxyTetherTags(ipv6Mode: Int) = buildList {
    if (ipv6Mode != IPv6Mode.ONLY) add("tproxy-tether-in4")
    if (ipv6Mode != IPv6Mode.DISABLE) add("tproxy-tether-in6")
}

/** Reuse device guards without assigning Android package identities to clients. */
internal fun retargetTunRules(config: MutableMap<String, Any?>, deviceTags: List<String>, allTags: List<String>) {
    fun visit(value: Any?, tags: List<String>) {
        when (value) {
            is MutableMap<*, *> -> {
                @Suppress("UNCHECKED_CAST")
                val map = value as MutableMap<String, Any?>
                val inbounds = map["inbound"] as? List<*>
                if (inbounds != null && TAG_TUN in inbounds) {
                    map["inbound"] = inbounds.filterNot { it == TAG_TUN } + tags
                }
                map.values.toList().forEach { visit(it, tags) }
            }
            is List<*> -> value.forEach { visit(it, tags) }
        }
    }
    visit(config["route"], deviceTags)
    visit(config["dns"], allTags)
}
