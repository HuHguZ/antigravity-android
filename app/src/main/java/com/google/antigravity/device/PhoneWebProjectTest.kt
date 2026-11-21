package com.google.antigravity.device

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import kotlin.coroutines.resume

/** Runs fixed DOM assertions against project files in an isolated, offline WebView. */
@SuppressLint("SetJavaScriptEnabled")
internal suspend fun testPhoneWebProject(context: Context, project: File, entry: String, checks: JSONArray): JSONArray {
    val preview = Uri.parse(startPhoneProjectPreview(project, entry))
    val prefix = "/${preview.pathSegments.first()}/"
    fun isProjectUrl(url: Uri): Boolean = url.scheme == "http" && url.host == preview.host &&
        url.port == preview.port && url.encodedPath?.startsWith(prefix) == true

    val script = """
        (() => JSON.parse(${JSONObject.quote(checks.toString())}).map(check => {
            try {
                const node = document.querySelector(check.selector);
                const text = node?.textContent ?? '';
                return { ok: !!node && (check.text === undefined || text.includes(check.text)), actual: text.slice(0, 200) };
            } catch (_) { return { ok: false, actual: 'Некорректный CSS-селектор' }; }
        }))()
    """.trimIndent()

    val encoded = withContext(Dispatchers.Main) {
        val browser = WebView(context)
        try {
            browser.settings.apply {
                javaScriptEnabled = true
                blockNetworkLoads = true
                allowFileAccess = false
                allowContentAccess = false
            }
            withTimeoutOrNull(12_000) {
                suspendCancellableCoroutine<String?> { pending ->
                    browser.webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                            return if (request.method == "GET" && isProjectUrl(request.url)) {
                                readTestResource(request.url)
                            } else unavailableTestResource()
                        }

                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                            !isProjectUrl(request.url)

                        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                            if (request.isForMainFrame && pending.isActive) pending.resume(null)
                        }

                        override fun onPageFinished(view: WebView, url: String) {
                            if (!pending.isActive || !isProjectUrl(Uri.parse(url))) return
                            view.evaluateJavascript(script) { value -> if (pending.isActive) pending.resume(value) }
                        }
                    }
                    browser.loadUrl(preview.toString())
                }
            }
        } finally {
            browser.stopLoading()
            browser.destroy()
        }
    } ?: throw IOException("Браузер не успел загрузить страницу для проверки.")
    return JSONArray(encoded)
}

private fun readTestResource(url: Uri): WebResourceResponse {
    val connection = URL(url.toString()).openConnection(Proxy.NO_PROXY) as HttpURLConnection
    return try {
        connection.connectTimeout = 2_000
        connection.readTimeout = 3_000
        connection.instanceFollowRedirects = false
        if (connection.responseCode != 200) {
            connection.disconnect()
            unavailableTestResource()
        } else {
            val mime = connection.contentType?.substringBefore(';') ?: "application/octet-stream"
            val stream = object : FilterInputStream(connection.inputStream) {
                override fun close() {
                    try { super.close() } finally { connection.disconnect() }
                }
            }
            WebResourceResponse(mime, "UTF-8", stream)
        }
    } catch (_: IOException) {
        connection.disconnect()
        unavailableTestResource()
    }
}

private fun unavailableTestResource() = WebResourceResponse(
    "text/plain", "UTF-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0))
)
