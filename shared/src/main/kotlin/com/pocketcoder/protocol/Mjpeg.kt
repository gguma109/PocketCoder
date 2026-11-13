package com.pocketcoder.protocol

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** Length-delimited multipart JPEG framing. Never scan binary image bytes for a boundary. */
class MjpegReader(private val input: InputStream) {
    fun nextFrame(): ByteArray? {
        var line = readLine() ?: return null
        if (line.isEmpty()) line = readLine() ?: return null
        if (line == "--pocketcoder-frame--") return null
        if (line != "--pocketcoder-frame") throw IOException("Invalid MJPEG boundary")
        var length: Int? = null
        var jpeg = false
        var count = 0
        while (true) {
            line = readLine() ?: throw EOFException("Truncated MJPEG headers")
            if (line.isEmpty()) break
            if (++count > 16) throw IOException("Too many MJPEG headers")
            val name = line.substringBefore(':').trim().lowercase()
            val value = line.substringAfter(':', "").trim()
            if (name == "content-length") length = value.toIntOrNull()
            if (name == "content-type") jpeg = value.equals("image/jpeg", ignoreCase = true)
        }
        val size = length?.takeIf { it in 1..2_097_152 } ?: throw IOException("Invalid MJPEG length")
        if (!jpeg) throw IOException("Expected JPEG frame")
        val bytes = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val read = input.read(bytes, offset, size - offset)
            if (read < 0) throw EOFException("Truncated JPEG frame")
            if (read == 0) throw IOException("Empty JPEG read")
            offset += read
        }
        if (input.read() != 13 || input.read() != 10) throw IOException("Missing JPEG terminator")
        return bytes
    }

    private fun readLine(): String? {
        val line = StringBuilder()
        while (true) {
            val ch = input.read()
            if (ch == -1) {
                if (line.isNotEmpty()) throw EOFException("Truncated MJPEG line")
                return null
            }
            if (ch == 10) return line.toString().removeSuffix("\r")
            if (line.length >= 1024) throw IOException("MJPEG line too long")
            line.append(ch.toChar())
        }
    }
}

fun writeMjpegFrame(output: OutputStream, jpeg: ByteArray) {
    require(jpeg.size in 1..2_097_152)
    output.write(("--pocketcoder-frame\r\nContent-Type: image/jpeg\r\n" +
        "Content-Length: ${jpeg.size}\r\n\r\n").toByteArray(Charsets.US_ASCII))
    output.write(jpeg)
    output.write(byteArrayOf(13, 10))
    output.flush()
}
