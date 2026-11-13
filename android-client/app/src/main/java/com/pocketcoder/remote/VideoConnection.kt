package com.pocketcoder.remote

import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI

/** Closing the socket immediately interrupts a blocked video read during a monitor change. */
internal class VideoConnection(base: String, path: String, token: String) : Closeable {
    private val socket = Socket()
    private val input: InputStream
    init {
        val uri = URI(base)
        require(uri.scheme == "http" && uri.host != null && !token.contains('\r') && !token.contains('\n'))
        try {
            socket.tcpNoDelay = true
            socket.soTimeout = 5000
            socket.connect(InetSocketAddress(uri.host, if (uri.port == -1) 80 else uri.port), 3000)
            val auth = if (token.isEmpty()) "" else "Authorization: Bearer $token\r\n"
            socket.getOutputStream().apply {
                write(("GET $path HTTP/1.1\r\nHost: ${uri.rawAuthority}\r\n" + auth +
                    "Connection: close\r\nCache-Control: no-store\r\n\r\n").toByteArray(Charsets.US_ASCII))
                flush()
            }
            input = socket.getInputStream().buffered(65536)
        } catch (e: Exception) { socket.close(); throw e }
    }
    fun body(): InputStream {
        val status = line(input).split(' ').getOrNull(1)?.toIntOrNull() ?: throw IOException("Invalid video HTTP response")
        val headers = mutableMapOf<String, String>()
        var bytes = 0
        while (true) {
            val header = line(input)
            bytes += header.length + 2
            if (bytes > 32768) throw IOException("Oversized video HTTP headers")
            if (header.isEmpty()) break
            val colon = header.indexOf(':')
            if (colon <= 0) throw IOException("Invalid video HTTP header")
            headers[header.substring(0, colon).lowercase()] = header.substring(colon + 1).trim()
        }
        if (status == 409) throw VideoBusyException()
        if (status != 200) throw IOException("영상 요청 실패: $status")
        return if (headers["transfer-encoding"]?.lowercase() == "chunked") Chunked(input) else input
    }
    override fun close() { socket.close() }

    private class Chunked(private val source: InputStream) : InputStream() {
        private var remaining = 0L
        private var needCrlf = false
        private var ended = false
        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 255
        }
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (ended) return -1
            if (remaining == 0L) {
                if (needCrlf && line(source).isNotEmpty()) throw IOException("Invalid video chunk ending")
                remaining = line(source).substringBefore(';').trim().toLongOrNull(16)
                    ?.takeIf { it in 0..16_777_216 } ?: throw IOException("Invalid video chunk size")
                if (remaining == 0L) { ended = true; return -1 }
                needCrlf = true
            }
            val count = source.read(bytes, offset, minOf(length.toLong(), remaining).toInt())
            if (count < 0) throw EOFException("Truncated video HTTP chunk")
            remaining -= count
            return count
        }
    }
    companion object {
        private fun line(input: InputStream): String {
            val text = StringBuilder()
            while (text.length <= 8192) {
                val value = input.read()
                if (value < 0) throw EOFException("Video HTTP connection closed")
                if (value == 10) return text.toString().removeSuffix("\r")
                text.append(value.toChar())
            }
            throw IOException("Oversized video HTTP line")
        }
    }
}

internal class VideoBusyException : IOException("모니터 영상 전환 중")
