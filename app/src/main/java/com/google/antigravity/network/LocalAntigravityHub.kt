package com.google.antigravity.network

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.Proxy
import java.net.URI
import java.net.URISyntaxException
import java.util.concurrent.TimeUnit

private const val HUB_HOST = "127.0.0.1"
private const val HUB_PORT = 4400
internal const val LOCAL_ANTIGRAVITY_HUB_COMMAND = "AGY_ENABLE_HUB=1 ~/.local/bin/agy --hub --hub-port=$HUB_PORT"

internal enum class LocalHubFailure { NOT_RUNNING, TIMEOUT, UNEXPECTED_RESPONSE }

private val hubLaunchFragment = Regex("t=[A-Za-z0-9._~-]+")

internal fun parseLocalAntigravityLaunchUrl(input: String): URI? {
    val uri = try {
        URI(input.trim())
    } catch (_: URISyntaxException) {
        return null
    }
    return uri.takeIf {
        it.scheme == "http" && (it.host == "localhost" || it.host == HUB_HOST) &&
            it.port in 1..65535 && it.rawPath == "/" && it.rawUserInfo == null &&
            it.rawQuery == null && it.rawFragment?.matches(hubLaunchFragment) == true
    }
}

private val localHubClient = OkHttpClient.Builder()
    .proxy(Proxy.NO_PROXY)
    .followRedirects(false)
    .retryOnConnectionFailure(false)
    .callTimeout(2, TimeUnit.SECONDS)
    .build()

internal fun checkLocalAntigravityHub(port: Int = HUB_PORT): LocalHubFailure? {
    val request = Request.Builder().url("http://$HUB_HOST:$port/").build()
    return try {
        localHubClient.newCall(request).execute().use { response ->
            // shortcut: use the page title until the Hub publishes a health endpoint.
            if (response.code == 200 && "<title>Antigravity</title>" in response.peekBody(4096).string()) {
                null
            } else {
                LocalHubFailure.UNEXPECTED_RESPONSE
            }
        }
    } catch (_: ConnectException) {
        LocalHubFailure.NOT_RUNNING
    } catch (_: InterruptedIOException) {
        LocalHubFailure.TIMEOUT
    } catch (_: IOException) {
        LocalHubFailure.UNEXPECTED_RESPONSE
    }
}
