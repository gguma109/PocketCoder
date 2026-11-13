package com.pocketcoder.remote

import java.net.ServerSocket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class VideoConnectionTest {
    @Test fun readsChunkedVideoAcrossChunkBoundaries() {
        ServerSocket(0).use { server ->
            val sent = CompletableFuture.runAsync {
                server.accept().use { socket ->
                    val input = socket.getInputStream().bufferedReader()
                    while (input.readLine().isNotEmpty()) { }
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n2\r\nab\r\n3;extension=test\r\ncde\r\n0\r\n\r\n".toByteArray())
                        flush()
                    }
                }
            }
            VideoConnection("http://127.0.0.1:${server.localPort}", "/video", "").use {
                assertEquals("abcde", it.body().readBytes().toString(Charsets.UTF_8))
            }
            sent.get(3, TimeUnit.SECONDS)
        }
    }
    @Test fun closingVideoImmediatelyCancelsABlockedRead() {
        ServerSocket(0).use { server ->
            val connected = CountDownLatch(1)
            val sent = CompletableFuture.runAsync {
                server.accept().use { socket ->
                    val input = socket.getInputStream().bufferedReader()
                    while (input.readLine().isNotEmpty()) { }
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n".toByteArray()); flush()
                    }
                    connected.countDown()
                    socket.getInputStream().read() // Wait for the client to cancel, not a timeout.
                }
            }
            VideoConnection("http://127.0.0.1:${server.localPort}", "/video", "").use { connection ->
                val body = connection.body()
                assertTrue(connected.await(2, TimeUnit.SECONDS))
                val read = CompletableFuture.supplyAsync { runCatching { body.read() }.isFailure }
                connection.close()
                assertTrue("Monitor switching must unblock video without the 5s read timeout", read.get(1, TimeUnit.SECONDS))
            }
            sent.get(3, TimeUnit.SECONDS)
        }
    }
}
