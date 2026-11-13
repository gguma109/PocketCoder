package com.pocketcoder.protocol

import java.io.*

data class AudioHeader(val rate: Int, val channels: Int = 2, val bits: Int = 16)
fun writeAudioHeader(output: DataOutputStream, header: AudioHeader) {
    output.writeInt(0x50434131); output.writeInt(header.rate); output.writeInt(header.channels); output.writeInt(header.bits); output.flush()
}
class AudioReader(input: InputStream) {
    private val data = DataInputStream(input)
    val header: AudioHeader
    init {
        if (data.readInt() != 0x50434131) throw IOException("Invalid audio protocol")
        header = AudioHeader(data.readInt(), data.readInt(), data.readInt())
        if (header.rate !in 8000..192000 || header.channels != 2 || header.bits != 16) throw IOException("Unsupported audio format")
    }
    fun next(): ByteArray {
        val size = data.readInt()
        if (size !in 4..262144 || size % 4 != 0) throw IOException("Invalid audio packet")
        return ByteArray(size).also { data.readFully(it) }
    }
}
