package com.pocketcoder.remote

import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.net.Socket
import java.net.URI
import java.util.concurrent.atomic.AtomicInteger

/** Keep input independent of a long-lived server lease. Pipeline small requests, read every reply. */
internal class InputSocket(base: String, private val token: String, private val onDisplay: (Int, Long) -> Unit = { _, _ -> }) : Closeable {
    private val socket = Socket()
    private val output: java.io.OutputStream
    private val authority: String
    private val pending = AtomicInteger()
    @Volatile private var failure: Exception? = null
    val isHealthy: Boolean get() = failure == null && !socket.isClosed
    init {
        val uri = URI(base)
        require(uri.scheme == "http" && uri.host != null)
        require(!token.contains('\r') && !token.contains('\n'))
        authority = uri.rawAuthority
        try {
            socket.tcpNoDelay = true
            socket.keepAlive = true
            socket.soTimeout = 15000
            socket.connect(java.net.InetSocketAddress(uri.host, if (uri.port == -1) 80 else uri.port), 3000)
            output = socket.getOutputStream()
            Thread({ readReplies() }, "pocketcoder-input-replies").apply { isDaemon = true; start() }
        } catch (e: Exception) { socket.close(); throw e }
    }
    fun send(encoded: ByteArray) {
        if (!isHealthy) throw IOException("입력 연결 재설정", failure)
        if (pending.incrementAndGet() > 32) { close(); throw IOException("입력 응답 지연 · 연결 재설정") }
        val auth = if (token.isEmpty()) "" else "Authorization: Bearer $token\r\n"
        val header = ("POST /input HTTP/1.1\r\nHost: $authority\r\n" + auth +
            "Content-Type: application/x-www-form-urlencoded; charset=utf-8\r\nContent-Length: ${encoded.size}\r\nConnection: keep-alive\r\n\r\n").toByteArray(Charsets.US_ASCII)
        output.write(header + encoded); output.flush()
    }
    private fun readReplies() {
        try {
            val input = socket.getInputStream().buffered(4096)
            while (!socket.isClosed) {
                val status = line(input).split(' ').getOrNull(1)?.toIntOrNull() ?: throw IOException("입력 응답 형식 오류")
                var length = -1
                var total = 0
                while (true) {
                    val header = line(input); total += header.length
                    if (total > 16384) throw IOException("입력 응답 헤더 초과")
                    if (header.isEmpty()) break
                    if (header.startsWith("Content-Length:", true)) length = header.substringAfter(':').trim().toIntOrNull() ?: -1
                }
                if (status != 200) throw IOException("입력 거부: HTTP $status")
                if (length !in 0..4096) throw IOException("입력 응답 크기 오류")
                val body = ByteArray(length)
                var offset = 0
                while (offset < length) {
                    val count = input.read(body, offset, length - offset)
                    if (count < 0) throw EOFException("입력 응답 종료")
                    offset += count
                }
                val receipt = body.toString(Charsets.UTF_8)
                val display = Regex("\"display\"\\s*:\\s*(\\d+)").find(receipt)?.groupValues?.get(1)?.toIntOrNull()
                val revision = Regex("\"displayRevision\"\\s*:\\s*(\\d+)").find(receipt)?.groupValues?.get(1)?.toLongOrNull()
                if (display != null && revision != null) onDisplay(display, revision)
                pending.decrementAndGet()
            }
        } catch (e: Exception) { failure = e; try { socket.close() } catch (_: Exception) { } }
    }
    private fun line(input: java.io.InputStream): String {
        val text = StringBuilder()
        while (text.length < 8192) {
            val value = input.read()
            if (value < 0) throw EOFException("입력 연결 종료")
            if (value == 10) return text.toString().removeSuffix("\r")
            text.append(value.toChar())
        }
        throw IOException("입력 응답 헤더 초과")
    }
    override fun close() { socket.close() }
}
