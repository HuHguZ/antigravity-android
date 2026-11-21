package com.google.antigravity

import com.google.antigravity.device.startPhoneProjectPreview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.Socket
import java.net.SocketException
import java.net.URI
import java.nio.file.Files

class PhoneProjectPreviewTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun servesHtmlAndRelativeModulesOnAnIsolatedLoopbackOrigin() {
        val project = temporary.newFolder("game")
        val html = "<script type=\"module\" src=\"../src/game%20+%20logic.mjs\"></script>"
        project.file("pages/игра.html", html)
        project.file("src/game + logic.mjs", "export const ready = true;")
        val first = URI(startPhoneProjectPreview(project, "pages/игра.html"))
        assertEquals("127.0.0.1", first.host)
        assertEquals(first, URI(startPhoneProjectPreview(project, "pages/игра.html")))
        assertEquals(html, first.request().body)
        val module = first.resolve("../src/game%20+%20logic.mjs?v=1").request()
        assertEquals(200, module.status)
        assertEquals("text/javascript; charset=utf-8", module.contentType)
        assertEquals("export const ready = true;", module.body)
        assertEquals("", first.request("HEAD").body)

        val other = temporary.newFolder("other").apply { file("index.html", "second") }
        val second = URI(startPhoneProjectPreview(other))
        assertNotEquals(first.port, second.port)
        assertEquals("second", second.request().body)
        assertEquals(html, first.request().body)
    }

    @Test fun rejectsNonProjectPathsAndNonReadMethods() {
        val project = temporary.newFolder("game")
        project.file("index.html", "project")
        project.file(".secret/info.txt", "private")
        temporary.root.file("outside.txt", "outside")
        assertThrows(IllegalArgumentException::class.java) { startPhoneProjectPreview(project, "../outside.txt") }
        assertThrows(IllegalArgumentException::class.java) { startPhoneProjectPreview(project, "missing.html") }
        assertThrows(IllegalArgumentException::class.java) { startPhoneProjectPreview(project, ".secret/info.txt") }
        val page = URI(startPhoneProjectPreview(project))
        val base = page.resolve("./")
        for (relative in listOf("%2e%2e/outside.txt", "%2e%2e%2foutside.txt", ".secret/info.txt", "missing.txt")) {
            assertEquals(relative, 404, base.resolve(relative).request().status)
        }
        assertEquals(404, page.resolve("/index.html").request().status)
        assertEquals(405, page.request("POST").status)
        Socket(page.host, page.port).use { socket ->
            socket.soTimeout = 4000
            socket.getOutputStream().write("GET ${page.rawPath} HTTP/1.1\r\nHost: example.com\r\n\r\n".toByteArray())
            assertTrue(socket.getInputStream().bufferedReader().readLine().startsWith("HTTP/1.1 400"))
        }
        assertEquals("project", page.request().body)
    }

    @Test fun evictsTheOldestPreviewWhenTheFifthProjectOpens() {
        val pages = (1..5).map { number ->
            val project = temporary.newFolder("game-$number").apply { file("index.html", "game $number") }
            URI(startPhoneProjectPreview(project)).also { assertEquals("game $number", it.request().body) }
        }
        assertThrows(SocketException::class.java) { pages.first().request() }
        assertEquals("game 2", pages[1].request().body)
    }

    @Test fun rejectsSymbolicLinksEvenWithinTheProject() {
        val project = temporary.newFolder("game")
        val page = project.file("index.html", "project")
        try {
            Files.createSymbolicLink(File(project, "link.html").toPath(), page.toPath())
        } catch (failure: IOException) {
            assumeNoException(failure)
        } catch (failure: UnsupportedOperationException) {
            assumeNoException(failure)
        }
        assertThrows(IllegalArgumentException::class.java) { startPhoneProjectPreview(project, "link.html") }
        val entry = URI(startPhoneProjectPreview(project))
        assertEquals(404, entry.resolve("link.html").request().status)
    }

    private fun File.file(path: String, text: String) = resolve(path).also {
        it.parentFile?.mkdirs()
        it.writeText(text)
    }

    private data class Response(val status: Int, val contentType: String?, val body: String)

    private fun URI.request(method: String = "GET"): Response {
        val connection = toURL().openConnection(Proxy.NO_PROXY) as HttpURLConnection
        return try {
            connection.connectTimeout = 4000
            connection.readTimeout = 4000
            connection.requestMethod = method
            connection.instanceFollowRedirects = false
            val status = connection.responseCode
            val stream = if (status < 400) connection.inputStream else connection.errorStream
            Response(status, connection.contentType, stream?.bufferedReader()?.use { it.readText() }.orEmpty())
        } finally {
            connection.disconnect()
        }
    }
}
