package com.google.antigravity.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

class LocalAntigravityHubTest {
    @Test
    fun acceptsTheCliLaunchLinkAndKeepsItsFragment() {
        val link = "http://localhost:4401/#t=example"
        assertEquals(link, parseLocalAntigravityLaunchUrl(" $link ")?.toString())
        assertEquals(4401, parseLocalAntigravityLaunchUrl(link)?.port)
    }

    @Test
    fun acceptsTheLinkCopiedWithTerminalOutputOrWithoutTheScheme() {
        val link = "http://localhost:4400/#t=example"
        assertEquals(link, parseLocalAntigravityLaunchUrl("Open in browser: <$link>\nPress Enter to retry.")?.toString())
        assertEquals(link, parseLocalAntigravityLaunchUrl("http://localhost:4400/\n#t=example")?.toString())
        assertEquals(link, parseLocalAntigravityLaunchUrl("localhost:4400/#t=example")?.toString())
    }

    @Test
    fun rejectsMissingTokenAndNonLocalLinks() {
        listOf(
            "http://localhost:4400/",
            "http://localhost:4400/#t=",
            "http://localhost:4400/#t=example&other=bad",
            "http://localhost:4400/redirect#t=example",
            "http://example.com:4400/#t=example",
            "http://localhost@example.com:4400/#t=example",
            "http://username@localhost:4400/#t=example",
            "http://localhost:65536/#t=example",
            "https://example.com/?next=http://localhost:4400/#t=example",
            "http://localhost:4400/#t=old\nhttp://localhost:4400/#t=new"
        ).forEach { assertNull(it, parseLocalAntigravityLaunchUrl(it)) }
    }

    @Test
    fun recognizesHubPage() {
        assertEquals(null, probePage(200, "<html><head><title>Antigravity</title></head></html>"))
    }

    @Test
    fun rejectsOtherPagesAndRedirects() {
        assertEquals(LocalHubFailure.UNEXPECTED_RESPONSE, probePage(200, "<title>Other app</title>"))
        assertEquals(LocalHubFailure.UNEXPECTED_RESPONSE, probePage(302, "<title>Antigravity</title>"))
    }

    @Test
    fun reportsWhenHubIsStopped() {
        val closedPort = ServerSocket().use { socket ->
            socket.bind(InetSocketAddress("127.0.0.1", 0))
            socket.localPort
        }
        assertEquals(LocalHubFailure.NOT_RUNNING, checkLocalAntigravityHub(closedPort))
    }

    private fun probePage(status: Int, body: String): LocalHubFailure? {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val bytes = body.encodeToByteArray()
            val responder = thread(isDaemon = true) {
                server.accept().use { socket ->
                    val request = socket.getInputStream().bufferedReader()
                    while (!request.readLine().isNullOrEmpty()) { }
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 $status Test\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".encodeToByteArray())
                        write(bytes)
                    }
                }
            }
            val result = checkLocalAntigravityHub(server.localPort)
            responder.join(2000)
            return result
        }
    }
}
