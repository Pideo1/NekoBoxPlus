@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package io.nekohasekai.sagernet.ui

import android.annotation.SuppressLint
import android.content.Intent
import androidx.core.net.toUri
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ui.compose.NekoComposeTheme
import io.nekohasekai.sagernet.ui.dashboard.strings
import org.json.JSONArray
import org.json.JSONObject

/** Isolated authentication activity; never hosts the dashboard or a core API. */
class DashboardBrowserAuthActivity : ThemedActivity() {
    private var webView: WebView? = null
    private var completed = false
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val request = intent.getStringExtra("request")?.let(::JSONObject) ?: return finish()
        val browser = request.getJSONObject("browser")
        val startUrl = browser.optString("url")
        if (startUrl.toUri().scheme !in listOf("https", "http")) return finish()
        setContent { NekoComposeTheme {
            Scaffold(contentWindowInsets = WindowInsets.safeDrawing, topBar = {
                TopAppBar(title = { Text(stringResource(R.string.dashboard_authenticate)) }, navigationIcon = {
                    IconButton(onClick = { finish() }) { Icon(painterResource(R.drawable.baseline_arrow_back_24), stringResource(R.string.dashboard_back)) }
                })
            }) { padding -> AndroidView(modifier = Modifier.padding(padding), factory = { context ->
                WebView(context).apply {
                    webView = this
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    webViewClient = object : WebViewClient() {
                        private fun finishChallenge(url: String, headers: Map<String, String> = emptyMap()): Boolean {
                            if (completed) return true
                            val final = browser.optString("finalURL")
                            val callback = browser.optJSONArray("callbackURLPrefixes").strings().any { url.startsWith(it) }
                            val finalReached = final.isNotBlank() && url.startsWith(final)
                            val cookieUrl = if (url.toUri().scheme in listOf("http", "https")) url else startUrl
                            val availableCookies = CookieManager.getInstance().getCookie(cookieUrl).orEmpty().split(';').mapNotNull {
                                val parts = it.trim().split('=', limit = 2)
                                if (parts.size == 2) parts[0] to parts[1] else null
                            }.toMap()
                            val early = browser.optJSONArray("earlyCookieNames").strings()
                            val sameHost = cookieUrl.toUri().host == startUrl.toUri().host
                            if (!callback && !finalReached && !(sameHost && early.isNotEmpty() && early.all { it in availableCookies })) return false
                            val names = browser.optJSONArray("cookieNames").strings() + early
                            val cookies = JSONArray(names.distinct().mapNotNull { name -> availableCookies[name]?.let { JSONObject().put("name", name).put("value", it) } })
                            val selectedHeaders = JSONArray(browser.optJSONArray("headerNames").strings().mapNotNull { name ->
                                headers.entries.find { it.key.equals(name, true) }?.let { JSONObject().put("name", name).put("values", JSONArray(listOf(it.value))) }
                            })
                            completed = true
                            val response = JSONObject().put("endpointTag", request.getString("endpointTag")).put("challengeID", request.getString("challengeID"))
                                .put("browser", JSONObject().put("finalURL", url).put("cookies", cookies).put("headers", selectedHeaders))
                            setResult(RESULT_OK, Intent().putExtra("response", response.toString())); finish()
                            return true
                        }
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                            finishChallenge(request.url.toString(), request.requestHeaders) || request.url.scheme !in listOf("http", "https")
                        override fun onPageFinished(view: WebView, url: String) { finishChallenge(url) }
                    }
                    loadUrl(startUrl)
                }
            }) }
        } }
    }
    override fun onDestroy() { webView?.apply { stopLoading(); destroy() }; webView = null; super.onDestroy() }
}
