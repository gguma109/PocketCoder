package com.pocketcoder.remote

import java.io.Closeable
import java.net.Socket
import java.net.URI

/** Small input packets must not wait for TCP's Nagle/delayed-ACK interaction. */
internal class InputSocket(base: String, token: String) : Closeable {
    private val socket = Socket()
    private val output: java.io.OutputStream
    init {
        val uri = URI(base)
        require(uri.scheme == "http" && uri.host != null)
        require(!token.contains('\r') && !token.contains('\n'))
        try {
            socket.tcpNoDelay = true
            socket.keepAlive = true
            socket.connect(java.net.InetSocketAddress(uri.host, if (uri.port == -1) 80 else uri.port), 3000)
            output = socket.getOutputStream()
            val auth = if (token.isEmpty()) "" else "Authorization: Bearer $token\r\n"
            output.write(("POST /input-stream HTTP/1.1\r\nHost: ${uri.rawAuthority}\r\n" +
                "Transfer-Encoding: chunked\r\nContent-Type: application/x-pocketcoder-input\r\n" +
                auth + "Connection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
            output.flush()
        } catch (e: Exception) { socket.close(); throw e }
    }
    fun send(encoded: ByteArray) {
        val size = encoded.size + 1
        output.write("${size.toString(16)}\r\n".toByteArray(Charsets.US_ASCII))
        output.write(encoded); output.write(10)
        output.write(byteArrayOf(13, 10)); output.flush()
    }
    override fun close() { socket.close() }
}
