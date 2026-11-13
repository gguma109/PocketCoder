package com.pocketcoder.remote

import java.net.ServerSocket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class InputSocketTest {
    @Test fun pipelinesInputsWithoutWaitingAndKeepsAuthenticationInHeaders() {
        ServerSocket(0).use { server ->
            val release = CountDownLatch(1)
            val received = CompletableFuture.supplyAsync {
                server.accept().use { socket ->
                    socket.soTimeout = 2000
                    val input = socket.getInputStream().bufferedReader()
                    val requests = (1..2).map {
                        val request = input.readLine()
                        val headers = mutableListOf<String>()
                        var length = 0
                        while (true) {
                            val line = input.readLine() ?: error("Missing headers")
                            if (line.isEmpty()) break
                            headers += line
                            if (line.startsWith("Content-Length:", true)) length = line.substringAfter(':').trim().toInt()
                        }
                        val body = CharArray(length)
                        var offset = 0
                        while (offset < length) { val n = input.read(body, offset, length - offset); check(n > 0); offset += n }
                        Triple(request, headers, String(body))
                    }
                    // Do not reply until both requests arrived: mouse input must not wait for round trips.
                    socket.getOutputStream().apply {
                        repeat(2) { write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\n{}".toByteArray()) }
                        flush()
                    }
                    release.await(2, TimeUnit.SECONDS)
                    requests
                }
            }
            InputSocket("http://127.0.0.1:${server.localPort}", "test-only-token").use { connection ->
                connection.send("type=move&dx=1&dy=2".toByteArray())
                connection.send("type=text&value=%ED%95%9C".toByteArray())
                release.countDown()
                val requests = received.get(3, TimeUnit.SECONDS)
                assertTrue(requests.all { it.first == "POST /input HTTP/1.1" })
                assertTrue(requests.all { it.second.contains("Authorization: Bearer test-only-token") })
                assertEquals(listOf("type=move&dx=1&dy=2", "type=text&value=%ED%95%9C"), requests.map { it.third })
            }
        }
    }
    @Test fun serverRejectionAndClosedIdleConnectionsAreDetected() {
        ServerSocket(0).use { server ->
            val worker = CompletableFuture.runAsync {
                server.accept().use { socket ->
                    val input = socket.getInputStream().bufferedReader()
                    while (input.readLine().isNotEmpty()) { }
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 401 Unauthorized\r\nContent-Length: 0\r\n\r\n".toByteArray()); flush()
                    }
                }
            }
            InputSocket("http://127.0.0.1:${server.localPort}", "").use { connection ->
                connection.send("type=move&dx=0&dy=0".toByteArray())
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                while (connection.isHealthy && System.nanoTime() < deadline) Thread.sleep(5)
                assertFalse("A rejected socket must not keep silently accepting local writes", connection.isHealthy)
            }
            worker.get(3, TimeUnit.SECONDS)
        }
    }
}
