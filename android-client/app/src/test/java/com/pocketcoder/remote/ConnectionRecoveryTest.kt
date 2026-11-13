package com.pocketcoder.remote

import android.os.Looper
import java.net.ServerSocket
import java.time.Duration
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ConnectionRecoveryTest {
    @Test fun retriesAfterNetworkFailureAndResumesButRespectsManualDisconnect() {
        val attempts = Semaphore(0)
        val server = ServerSocket(0)
        val accepting = Thread({
            try {
                while (!server.isClosed) server.accept().use { socket ->
                    socket.soTimeout = 2000
                    val reader = socket.getInputStream().bufferedReader()
                    while (true) { val line = reader.readLine() ?: break; if (line.isEmpty()) break }
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 503 Service Unavailable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                        flush()
                    }
                    attempts.release()
                }
            } catch (_: java.net.SocketException) { }
        }, "test-health-server").apply { isDaemon = true; start() }
        val failed = Semaphore(0)
        val client = RemoteClient({ _, message -> if (message.contains("자동 재연결")) failed.release() }, {})
        try {
            client.connect("127.0.0.1:${server.localPort}")
            assertFalse(attempts.tryAcquire(100, TimeUnit.MILLISECONDS))
            client.resume()
            assertTrue(attempts.tryAcquire(3, TimeUnit.SECONDS))
            // Allow the worker to post the failure and delayed retry.
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (failed.availablePermits() == 0 && System.nanoTime() < deadline) {
                shadowOf(Looper.getMainLooper()).idle()
                Thread.yield()
            }
            assertTrue(failed.tryAcquire())
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1600))
            assertTrue(attempts.tryAcquire(3, TimeUnit.SECONDS))
            client.pause()
            client.resume()
            assertTrue(attempts.tryAcquire(3, TimeUnit.SECONDS))
            client.disconnect()
            client.resume()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
            assertFalse(attempts.tryAcquire(200, TimeUnit.MILLISECONDS))
        } finally { client.shutdown(); server.close(); accepting.join(1000) }
    }
}
