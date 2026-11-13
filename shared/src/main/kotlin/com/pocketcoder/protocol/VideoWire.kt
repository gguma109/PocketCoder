package com.pocketcoder.protocol

import java.io.*

private const val MAX_VIDEO_PACKET = 2_097_152
data class VideoHeader(val width: Int, val height: Int, val fps: Int)
data class VideoPacket(val timeUs: Long, val bytes: ByteArray)

fun writeVideoHeader(output: DataOutputStream, header: VideoHeader) {
    output.writeInt(0x50435631)
    output.writeInt(header.width); output.writeInt(header.height); output.writeInt(header.fps)
    output.flush()
}
fun writeVideoPacket(output: DataOutputStream, bytes: ByteArray, timeUs: Long) {
    require(bytes.size in 1..MAX_VIDEO_PACKET)
    output.writeInt(bytes.size); output.writeLong(timeUs); output.write(bytes); output.flush()
}
class VideoReader(input: InputStream) {
    private val data = DataInputStream(input)
    val header: VideoHeader
    init {
        if (data.readInt() != 0x50435631) throw IOException("Unsupported video protocol")
        header = VideoHeader(data.readInt(), data.readInt(), data.readInt())
        if (header.width !in 2..8192 || header.height !in 2..8192 || header.fps !in 1..120) throw IOException("Invalid video dimensions")
    }
    fun next(): VideoPacket? {
        val first = data.read()
        if (first == -1) return null
        val length = (first shl 24) or (data.readUnsignedByte() shl 16) or (data.readUnsignedByte() shl 8) or data.readUnsignedByte()
        if (length !in 1..MAX_VIDEO_PACKET) throw IOException("Invalid video packet size")
        val time = data.readLong()
        return VideoPacket(time, ByteArray(length).also { data.readFully(it) })
    }
}

/** Streaming Annex-B NAL parser, including start codes split across input reads. */
class AnnexBReader(private val input: InputStream) {
    private var started = false
    private var ended = false
    fun nextNal(): ByteArray? {
        if (ended) return null
        val output = ByteArrayOutputStream()
        var zeros = 0
        while (true) {
            val value = input.read()
            if (value == -1) {
                ended = true
                return if (output.size() > 0) output.toByteArray() else null
            }
            if (value == 0) {
                if (++zeros > MAX_VIDEO_PACKET) throw IOException("Oversized Annex-B padding")
                continue
            }
            if (value == 1 && zeros >= 2) {
                zeros = 0
                if (started && output.size() > 0) return output.toByteArray()
                started = true
                continue
            }
            if (started) {
                repeat(zeros) { output.write(0) }
                output.write(value)
                if (output.size() > MAX_VIDEO_PACKET) throw IOException("Oversized NAL unit")
            }
            zeros = 0
        }
    }
}

fun nalUnits(bytes: ByteArray): List<ByteArray> {
    val reader = AnnexBReader(bytes.inputStream())
    val result = mutableListOf<ByteArray>()
    while (true) result += reader.nextNal() ?: break
    return result
}
fun annexB(nal: ByteArray) = byteArrayOf(0, 0, 0, 1) + nal

class AccessUnitReader(input: InputStream) {
    private val reader = AnnexBReader(input)
    private var pending: ByteArray? = null
    fun next(): ByteArray? {
        val output = ByteArrayOutputStream()
        var nal = pending ?: reader.nextNal() ?: return null
        pending = null
        while (true) {
            output.write(annexB(nal))
            if (output.size() > MAX_VIDEO_PACKET) throw IOException("Oversized access unit")
            val next = reader.nextNal() ?: return output.toByteArray()
            if ((next[0].toInt() and 31) == 9) {
                pending = next
                return output.toByteArray()
            }
            nal = next
        }
    }
}

/** URL encoded input cannot contain literal LF; cap each streaming input independently. */
fun readInputLine(input: InputStream): String? {
    val output = ByteArrayOutputStream()
    while (true) {
        val value = input.read()
        if (value == -1) {
            if (output.size() != 0) throw EOFException("Truncated input event")
            return null
        }
        if (value == 10) return output.toString("UTF-8")
        if (output.size() >= 32768) throw IOException("Input event too large")
        output.write(value)
    }
}
