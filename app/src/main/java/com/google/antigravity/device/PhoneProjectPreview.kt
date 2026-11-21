package com.google.antigravity.device

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URLConnection
import java.net.URLDecoder
import java.nio.channels.Channels
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.READ
import java.util.UUID
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

internal fun startPhoneProjectPreview(project: File, entry: String = "index.html"): String {
    val directory = project.toPath()
    require(Files.isDirectory(directory, NOFOLLOW_LINKS)) { "Папка проекта недоступна." }
    val root = directory.toRealPath()
    require(entry.substringAfterLast('.', "").lowercase() in setOf("html", "htm") &&
        resolvePreviewFile(root, entry.split('/')) != null) {
        "Укажите существующий HTML-файл внутри проекта."
    }
    return PhoneProjectPreviews.open(root, entry)
}

private fun resolvePreviewFile(root: Path, parts: List<String>): Path? {
    if (!Files.isDirectory(root, NOFOLLOW_LINKS)) return null
    var path = root
    for (part in parts) {
        if (part.isBlank() || part != part.trim() || part.startsWith('.') || part.endsWith('.') ||
            part.any { it.isISOControl() || it in "/\\:<>\"|?*" }) return null
        path = path.resolve(part)
        if (!Files.exists(path, NOFOLLOW_LINKS) || Files.isSymbolicLink(path) || Files.isHidden(path)) return null
    }
    return path.takeIf { Files.isRegularFile(it, NOFOLLOW_LINKS) && it.toRealPath() == it }
}

private object PhoneProjectPreviews {
    private const val MAX_PREVIEWS = 4
    private val idleNanos = TimeUnit.MINUTES.toNanos(30)
    private val previews = mutableMapOf<Path, Preview>()
    private val workers = ThreadPoolExecutor(0, 12, 30, TimeUnit.SECONDS, SynchronousQueue()) { task ->
        Thread(task, "phone-preview-request").apply { isDaemon = true }
    }
    private val timeouts = ScheduledThreadPoolExecutor(1) { task ->
        Thread(task, "phone-preview-timeout").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }

    @Synchronized
    fun open(root: Path, entry: String): String {
        val preview = previews[root]?.takeUnless { it.listener.isClosed } ?: Preview(root).also { new ->
            if (previews.size == MAX_PREVIEWS && root !in previews) {
                val oldest = previews.minBy { it.value.lastAccess }
                previews.remove(oldest.key)?.listener?.close()
            }
            previews[root] = new
            thread(name = "phone-preview-listener", isDaemon = true) { listen(new) }
        }
        preview.lastAccess = System.nanoTime()
        return URI("http", preview.authority, "${preview.prefix}$entry", null, null).toASCIIString()
    }

    private fun listen(preview: Preview) {
        try {
            while (!preview.listener.isClosed) {
                synchronized(this) {
                    if (System.nanoTime() - preview.lastAccess >= idleNanos) preview.listener.close()
                }
                if (preview.listener.isClosed) break
                val socket = try { preview.listener.accept() } catch (_: SocketTimeoutException) { continue }
                try {
                    workers.execute { handle(preview, socket) }
                } catch (_: RejectedExecutionException) {
                    socket.close()
                }
            }
        } catch (_: IOException) {
            // Closing an idle or evicted listener also interrupts accept().
        } finally {
            preview.listener.close()
            synchronized(this) { previews.remove(preview.root, preview) }
        }
    }

    private fun handle(preview: Preview, socket: Socket) {
        try {
            socket.use {
                it.soTimeout = 3000
                val timeout = timeouts.schedule({ it.close() }, 10, TimeUnit.SECONDS)
                try {
                    preview.serve(it)
                } finally {
                    timeout.cancel(false)
                }
            }
        } catch (_: IOException) {
            // A disconnected or timed-out browser request leaves the preview running.
        }
    }

    private class Preview(val root: Path) {
        val listener = ServerSocket(0, 12, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 15_000 }
        val authority = "127.0.0.1:${listener.localPort}"
        val prefix = "/${UUID.randomUUID()}/"
        @Volatile var lastAccess = System.nanoTime()

        fun serve(socket: Socket) {
            val headers = socket.getInputStream().buffered().readPreviewHeaders()
            val request = headers.firstOrNull().orEmpty().split(' ')
            val host = headers.drop(1).filter { it.substringBefore(':').equals("host", ignoreCase = true) }
                .singleOrNull()?.substringAfter(':', "")?.trim()
            val output = socket.getOutputStream()
            if (request.size != 3 || request[2] !in setOf("HTTP/1.0", "HTTP/1.1") || host != authority) {
                return output.respond("400 Bad Request")
            }
            val method = request[0]
            if (method != "GET" && method != "HEAD") return output.respond("405 Method Not Allowed", allow = "Allow: GET, HEAD\r\n")
            val uri = try { URI.create(request[1]) } catch (_: IllegalArgumentException) { return output.respond("400 Bad Request") }
            val path = uri.rawPath?.takeIf { it.startsWith(prefix) }
            if (path == null || uri.isAbsolute || uri.rawAuthority != null || uri.rawFragment != null) {
                return output.respond("404 Not Found")
            }
            val parts = path.removePrefix(prefix).split('/').map { URLDecoder.decode(it.replace("+", "%2B"), "UTF-8") }
            val file = resolvePreviewFile(root, parts) ?: return output.respond("404 Not Found")
            lastAccess = System.nanoTime()
            Files.newByteChannel(file, READ, NOFOLLOW_LINKS).use { channel ->
                output.respond("200 OK", previewContentType(file.fileName.toString()), channel.size())
                if (method == "GET") Channels.newInputStream(channel).copyTo(output)
            }
        }
    }
}

private fun InputStream.readPreviewHeaders(): List<String> {
    val header = StringBuilder()
    repeat(8192) {
        val next = read()
        if (next < 0) return emptyList()
        header.append(next.toChar())
        if (header.endsWith("\r\n\r\n")) return header.substring(0, header.length - 4).split("\r\n")
    }
    return emptyList()
}

private fun OutputStream.respond(status: String, type: String = "text/plain", length: Long = 0, allow: String = "") {
    write(("HTTP/1.1 $status\r\nContent-Type: $type\r\nContent-Length: $length\r\n" + allow +
        "Cache-Control: no-store\r\nReferrer-Policy: no-referrer\r\nX-Content-Type-Options: nosniff\r\n" +
        "Cross-Origin-Resource-Policy: same-origin\r\nContent-Security-Policy: frame-ancestors 'none'\r\n" +
        "Connection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
}

private fun previewContentType(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
    "html", "htm" -> "text/html; charset=utf-8"
    "css" -> "text/css; charset=utf-8"
    "js", "mjs" -> "text/javascript; charset=utf-8"
    "json", "map" -> "application/json"
    "webmanifest" -> "application/manifest+json"
    "wasm" -> "application/wasm"
    "svg" -> "image/svg+xml"
    "woff" -> "font/woff"
    "woff2" -> "font/woff2"
    "ttf" -> "font/ttf"
    "otf" -> "font/otf"
    "ogg" -> "audio/ogg"
    "webm" -> "video/webm"
    "txt", "glsl", "vert", "frag" -> "text/plain; charset=utf-8"
    else -> URLConnection.guessContentTypeFromName(name.lowercase()) ?: "application/octet-stream"
}
