package io.github.manuelrtsch.sidetrack.data

import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Drives [httpFetch] against a throwaway socket, so no test needs a real server. */
class HttpFetchTest {

    @Test
    fun `a success carries the body and its headers`() = withServer(
        status = "200 OK",
        body = """{"ok":true}""",
        header = "Retry-After: 7"
    ) { port ->
        val response = httpFetch("http://127.0.0.1:$port/catalog")

        assertEquals(200, response.status)
        assertTrue(response.isSuccess)
        assertEquals("""{"ok":true}""", response.body)
        assertEquals("7", response.header("retry-after"))
        assertNull(response.header("X-Nothing"))
    }

    @Test
    fun `an error status still carries the server's message`() = withServer(
        status = "503 Service Unavailable",
        body = "down for maintenance"
    ) { port ->
        val response = httpFetch("http://127.0.0.1:$port/catalog")

        assertFalse(response.isSuccess)
        assertEquals(503, response.status)
        assertEquals("down for maintenance", response.body)
    }

    @Test
    fun `a body is written with the method and headers`() = withServer(
        status = "200 OK",
        body = """{"access_token":"token"}""",
        onRequest = { request ->
            assertTrue(request.startsWith("POST /token HTTP/1.1"))
            assertTrue(request.lowercase().contains("content-type: application/x-www-form-urlencoded"))
            assertTrue(request.endsWith("grant_type=refresh_token"))
        }
    ) { port ->
        val response = httpFetch(
            "http://127.0.0.1:$port/token",
            method = "POST",
            headers = mapOf("Content-Type" to "application/x-www-form-urlencoded"),
            body = "grant_type=refresh_token"
        )

        assertEquals("""{"access_token":"token"}""", response.body)
    }

    @Test
    fun `an empty body is still a request body, so a POST stays a POST`() = withServer(
        status = "200 OK",
        body = "<xml/>",
        onRequest = { request ->
            assertTrue(request.startsWith("POST /unclaimed HTTP/1.1"))
            assertTrue(request.lowercase().contains("content-length: 0"))
        }
    ) { port ->
        val response = httpFetch("http://127.0.0.1:$port/unclaimed", method = "POST", body = "")

        assertEquals("<xml/>", response.body)
    }

    @Test
    fun `a refused connection surfaces as IOException for the provider to map`() {
        val port = ServerSocket(0).use { it.localPort }

        try {
            runBlocking { httpFetch("http://127.0.0.1:$port/catalog") }
            fail("expected an IOException")
        } catch (_: IOException) {
            // Each provider turns this into its own failure kind.
        }
    }

    @Test
    fun `the debug pause runs before the request`() {
        DebugLatency.millis = 200
        try {
            withServer(status = "200 OK", body = "ok") { port ->
                val started = System.currentTimeMillis()
                httpFetch("http://127.0.0.1:$port/catalog")
                assertTrue(System.currentTimeMillis() - started >= 190)
            }
        } finally {
            DebugLatency.millis = 0
        }
    }

    private fun withServer(
        status: String,
        body: String,
        header: String? = null,
        onRequest: (String) -> Unit = {},
        block: suspend (port: Int) -> Unit
    ) {
        val server = ServerSocket(0)
        val serverFailure = AtomicReference<Throwable?>()
        thread(isDaemon = true, name = "httpFetchTest-server") {
            runCatching {
                server.accept().use { socket -> reply(socket, status, body, header, onRequest) }
            }.onFailure { serverFailure.set(it) }
            server.close()
        }
        try {
            runBlocking { block(server.localPort) }
        } finally {
            runCatching { server.close() }
        }
        // An assertion the fake server makes should fail the test, not vanish into its thread.
        serverFailure.get()?.let { throw it }
    }

    private fun reply(
        socket: Socket,
        status: String,
        body: String,
        header: String?,
        onRequest: (String) -> Unit
    ) {
        onRequest(readRequest(socket))
        val bytes = body.toByteArray()
        val head = buildString {
            append("HTTP/1.1 ").append(status).append("\r\n")
            append("Content-Type: text/plain\r\n")
            append("Content-Length: ").append(bytes.size).append("\r\n")
            append("Connection: close\r\n")
            if (header != null) append(header).append("\r\n")
            append("\r\n")
        }
        socket.getOutputStream().apply {
            write(head.toByteArray())
            write(bytes)
            flush()
        }
    }

    /** The request head plus whatever body the `Content-Length` announces. */
    private fun readRequest(socket: Socket): String {
        val input = socket.getInputStream()
        val head = StringBuilder()
        while (!head.endsWith("\r\n\r\n")) {
            val next = input.read()
            if (next < 0) break
            head.append(next.toChar())
        }
        val length = Regex("(?i)content-length: (\\d+)")
            .find(head)
            ?.groupValues
            ?.get(1)
            ?.toInt()
            ?: 0
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val next = input.read(body, read, length - read)
            if (next < 0) break
            read += next
        }
        return head.toString() + String(body, 0, read)
    }
}
