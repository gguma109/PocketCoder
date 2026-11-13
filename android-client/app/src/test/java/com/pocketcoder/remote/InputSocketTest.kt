package com.pocketcoder.remote

import java.net.ServerSocket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class InputSocketTest {
    @Test fun sendsEachEventBeforeStreamIsClosedAndKeepsAuthenticationInHeaders() {
        ServerSocket(0).use { server ->
            val received = CompletableFuture.supplyAsync {
                server.accept().use { socket ->
                    socket.soTimeout = 2000
                    val input = socket.getInputStream().bufferedReader(Charsets.UTF_8)
                    val headers = mutableListOf<String>()
                    while (true) { val line = input.readLine() ?: error("Missing headers"); if (line.isEmpty()) break; headers += line }
                    val events = (1..2).map {
                        val length = input.readLine().toInt(16)
                        val chars = CharArray(length)
                        var offset = 0
                        while (offset < length) { val n = input.read(chars, offset, length - offset); check(n > 0); offset += n }
                        check(input.readLine().isEmpty())
                        String(chars)
                    }
                    headers to events
                }
            }
            InputSocket("http://127.0.0.1:${server.localPort}", "test-only-token").use { connection ->
                connection.send("type=move&dx=1&dy=2".toByteArray())
                connection.send("type=text&value=%ED%95%9C".toByteArray())
                val (headers, events) = received.get(3, TimeUnit.SECONDS)
                assertTrue(headers.contains("Authorization: Bearer test-only-token"))
                assertEquals(listOf("type=move&dx=1&dy=2\n", "type=text&value=%ED%95%9C\n"), events)
                assertFalse(events.any { it.contains("test-only-token") })
            }
        }
    }
}
