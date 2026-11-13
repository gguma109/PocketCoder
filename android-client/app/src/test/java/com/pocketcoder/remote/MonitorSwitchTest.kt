package com.pocketcoder.remote

import android.os.Looper
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MonitorSwitchTest {
    @Test fun switchingKeepsTheSessionAndDoesNotRepeatTheLoginHandshake() {
        val server = ServerSocket(0)
        val healthCount = AtomicInteger()
        val switches = AtomicInteger()
        val selected = AtomicInteger(-1)
        val thread = Thread({
            try {
                while (!server.isClosed) server.accept().use { socket ->
                    socket.soTimeout = 2000
                    val reader = socket.getInputStream().bufferedReader()
                    val request = reader.readLine()
                    var length = 0
                    while (true) {
                        val header = reader.readLine() ?: break
                        if (header.isEmpty()) break
                        if (header.startsWith("Content-Length:", true)) length = header.substringAfter(':').trim().toInt()
                    }
                    repeat(length) { reader.read() }
                    val body = if (request.contains("/health")) {
                        healthCount.incrementAndGet()
                        """{"auth":"tailscale","video":"h264","videoFps":60,"stream":"mjpeg","displays":2,"display":0}"""
                    } else {
                        assertTrue(request.contains("/display"))
                        switches.incrementAndGet(); "{}"
                    }.toByteArray()
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(body); flush()
                    }
                }
            } catch (_: java.net.SocketException) { }
        }).apply { isDaemon = true; start() }
        val client = RemoteClient({ _, _ -> }, {}, onDisplays = { _, index -> selected.set(index) })
        fun awaitSelection(index: Int) {
            val deadline = System.nanoTime() + 3_000_000_000L
            while (selected.get() != index && System.nanoTime() < deadline) {
                shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5)
            }
            assertEquals(index, selected.get())
        }
        try {
            client.connect("127.0.0.1:${server.localPort}"); client.resume()
            awaitSelection(0)
            client.selectDisplay(1); awaitSelection(1)
            client.selectDisplay(0); awaitSelection(0)
            assertEquals(2, switches.get())
            assertEquals("Monitor changes must not repeat PC authentication and reconnect the session", 1, healthCount.get())
        } finally { client.shutdown(); server.close(); thread.join(1000) }
    }
}
