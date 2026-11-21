package io.github.manuelrtsch.sidetrack.test

import java.io.BufferedInputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.sin

/**
 * A local HTTP music source for playback tests, on the JVM and on a device.
 *
 * The JDK's `com.sun.net.httpserver` is not part of Android, so this speaks the little HTTP the
 * transports need: a request line, headers, `Range`, and a PCM WAV body. It records every request
 * it answered, so a test can state what the player actually asked for — note that ExoPlayer opens
 * a fresh item at position 0 without a `Range` and only sends one when it resumes or seeks inside
 * the file, so a non-empty [requests] is the portable claim and the header shape is not.
 *
 * Lives in `:core`'s test fixtures because both the unit and the instrumented playback tests need
 * it, and two copies of a WAV generator drift.
 */
class TestAudioStream(
    /** When set, a request without this token in [TOKEN_HEADER] is answered 401. */
    private val requireToken: String? = null
) : AutoCloseable {
    /** One answered request, as the transport sent it. */
    data class Request(val path: String, val range: String?, val token: String?)

    val requests = CopyOnWriteArrayList<Request>()

    private val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
    private val running = AtomicBoolean(true)

    val url: String get() = "http://127.0.0.1:${server.localPort}/stream.wav"

    /** The same audio behind the token check, for a test that proves headers reach the transport. */
    val authUrl: String get() = "http://127.0.0.1:${server.localPort}/auth.wav"

    private val worker = thread(name = "test-audio-stream", isDaemon = true) {
        while (running.get()) {
            val socket = runCatching { server.accept() }.getOrNull() ?: break
            runCatching { serve(socket) }
        }
    }

    private fun serve(socket: Socket) {
        socket.use {
            val input = BufferedInputStream(it.getInputStream())
            val requestLine = readLine(input) ?: return
            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
            }
            val path = requestLine.split(' ').getOrNull(1).orEmpty()
            if (path != "/stream.wav" && path != "/auth.wav") {
                writeResponse(it.getOutputStream(), 404, emptyMap(), ByteArray(0))
                return
            }
            val token = headers[TOKEN_HEADER.lowercase()]
            requests += Request(path = path, range = headers["range"], token = token)
            if (requireToken != null && token != requireToken) {
                writeResponse(it.getOutputStream(), 401, emptyMap(), ByteArray(0))
                return
            }
            val range = headers["range"]
            val (start, end) = parseRange(range, WAV.size)
            val body = WAV.copyOfRange(start, end + 1)
            val responseHeaders = mutableMapOf(
                "Accept-Ranges" to "bytes",
                "Content-Type" to "audio/wav",
                "Connection" to "close"
            )
            if (range != null) {
                responseHeaders["Content-Range"] = "bytes $start-$end/${WAV.size}"
                writeResponse(it.getOutputStream(), 206, responseHeaders, body)
            } else {
                writeResponse(it.getOutputStream(), 200, responseHeaders, body)
            }
        }
    }

    private fun readLine(input: BufferedInputStream): String? {
        val line = StringBuilder()
        while (true) {
            val c = input.read()
            if (c == -1) return if (line.isEmpty()) null else line.toString()
            if (c == '\n'.code) return line.toString().trimEnd('\r')
            line.append(c.toChar())
        }
    }

    private fun writeResponse(out: OutputStream, code: Int, headers: Map<String, String>, body: ByteArray) {
        val reason = when (code) {
            206 -> "Partial Content"
            200 -> "OK"
            401 -> "Unauthorized"
            else -> "Not Found"
        }
        val head = buildString {
            append("HTTP/1.1 $code $reason\r\n")
            headers.forEach { (key, value) -> append("$key: $value\r\n") }
            append("Content-Length: ${body.size}\r\n")
            append("\r\n")
        }
        out.write(head.toByteArray(Charsets.US_ASCII))
        out.write(body)
        out.flush()
    }

    private fun parseRange(range: String?, size: Int): Pair<Int, Int> {
        if (range == null) return 0 to size - 1
        val spec = range.removePrefix("bytes=").substringBefore(',')
        val start = spec.substringBefore('-').toIntOrNull() ?: 0
        val end = spec.substringAfter('-', "").toIntOrNull() ?: (size - 1)
        return start.coerceIn(0, size - 1) to end.coerceIn(start, size - 1)
    }

    override fun close() {
        running.set(false)
        runCatching { server.close() }
        worker.join(1_000)
    }

    companion object {
        /** Header a token-protected stream expects; the fixture records it, the test sets it. */
        const val TOKEN_HEADER = "X-Test-Token"

        /** One second of a 440 Hz tone, 16-bit mono PCM at 8 kHz: long enough to play on a device. */
        val WAV: ByteArray = run {
            val sampleRate = 8_000
            val samples = sampleRate
            val dataSize = samples * 2
            val out = ByteArray(44 + dataSize)
            fun putAscii(offset: Int, value: String) {
                value.forEachIndexed { i, c -> out[offset + i] = c.code.toByte() }
            }
            fun putInt(offset: Int, value: Int) {
                out[offset] = (value and 0xff).toByte()
                out[offset + 1] = (value shr 8 and 0xff).toByte()
                out[offset + 2] = (value shr 16 and 0xff).toByte()
                out[offset + 3] = (value shr 24 and 0xff).toByte()
            }
            fun putShort(offset: Int, value: Int) {
                out[offset] = (value and 0xff).toByte()
                out[offset + 1] = (value shr 8 and 0xff).toByte()
            }
            putAscii(0, "RIFF")
            putInt(4, 36 + dataSize)
            putAscii(8, "WAVE")
            putAscii(12, "fmt ")
            putInt(16, 16)
            putShort(20, 1) // PCM
            putShort(22, 1) // mono
            putInt(24, sampleRate)
            putInt(28, sampleRate * 2) // byte rate
            putShort(32, 2) // block align
            putShort(34, 16) // bits per sample
            putAscii(36, "data")
            putInt(40, dataSize)
            for (i in 0 until samples) {
                val value = (sin(2.0 * Math.PI * 440.0 * i / sampleRate) * Short.MAX_VALUE * 0.2).toInt()
                putShort(44 + i * 2, value)
            }
            out
        }
    }
}
