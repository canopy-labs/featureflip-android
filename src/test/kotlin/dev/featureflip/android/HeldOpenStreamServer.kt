package dev.featureflip.android

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * A client Evaluation API whose streams stay open until the client closes them.
 *
 * MockWebServer ends every response once its body is written, so a connection that
 * outlives its replacement (the leak this exists to catch) never shows up there. Here
 * each `/v1/client/stream` connection gets a snapshot frame and is then held open, and
 * [Stream.isClosed] reports when the client side hangs up. Any other request is
 * answered with [flagsJson] and closed.
 */
internal class HeldOpenStreamServer(private val flagsJson: String) : AutoCloseable {
    private val socket = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    private val running = AtomicBoolean(true)
    val streams = CopyOnWriteArrayList<Stream>()

    val baseUrl: String get() = "http://127.0.0.1:${socket.localPort}"

    class Stream(internal val socket: Socket) {
        @Volatile var isClosed = false
            internal set
    }

    init {
        thread(isDaemon = true, name = "held-open-stream-server") {
            while (running.get()) {
                val client = try {
                    socket.accept()
                } catch (_: Exception) {
                    break
                }
                thread(isDaemon = true) { handle(client) }
            }
        }
    }

    private fun handle(client: Socket) {
        val reader = BufferedReader(InputStreamReader(client.getInputStream()))
        val requestLine = reader.readLine() ?: return client.close()
        var contentLength = 0
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            if (line.startsWith("Content-Length:", ignoreCase = true)) {
                contentLength = line.substringAfter(':').trim().toInt()
            }
        }
        repeat(contentLength) { reader.read() }
        val out = client.getOutputStream()

        if (!requestLine.startsWith("GET /v1/client/stream")) {
            val body = flagsJson.toByteArray()
            out.write(
                ("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                    "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray(),
            )
            out.write(body)
            out.flush()
            client.close()
            return
        }

        val stream = Stream(client)
        streams.add(stream)
        out.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\n\r\n".toByteArray())
        out.write("event: flags-updated\ndata: $flagsJson\n\n".toByteArray())
        out.flush()
        // The client never sends anything on a stream, so a read returns only when it
        // hangs up: -1 on an orderly close, an exception on a reset.
        try {
            client.getInputStream().read()
        } catch (_: Exception) {
        }
        stream.isClosed = true
        client.close()
    }

    /** Waits until [condition] holds, polling every 10ms; false on timeout. */
    fun awaitUntil(timeoutMs: Long = 5_000, condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < deadline) {
            if (condition()) return true
            Thread.sleep(10)
        }
        return condition()
    }

    fun openStreams(): Int = streams.count { !it.isClosed }

    override fun close() {
        running.set(false)
        streams.forEach { runCatching { it.socket.close() } }
        socket.close()
    }
}
