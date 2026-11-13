package com.pocketcoder.remote

import android.os.Looper
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
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
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/health") { exchange ->
            exchange.sendResponseHeaders(503, -1); exchange.close(); attempts.release()
        }
        server.start()
        val failed = Semaphore(0)
        val client = RemoteClient({ _, message -> if (message.contains("자동 재연결")) failed.release() }, {})
        try {
            client.connect("127.0.0.1:${server.address.port}")
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
        } finally { client.shutdown(); server.stop(0) }
    }
}
