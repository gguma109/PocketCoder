package com.pocketcoder.remote

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.view.Surface
import com.pocketcoder.protocol.*
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Hardware decoding straight to a Surface; no JPEG/Bitmap allocation or playback prebuffer. */
internal fun decodeH264(input: InputStream, surface: Surface, running: () -> Boolean, onSize: (Int, Int) -> Unit) {
    val reader = VideoReader(input)
    val first = reader.next() ?: throw IOException("Empty H.264 stream")
    val nals = nalUnits(first.bytes)
    val sps = nals.firstOrNull { (it[0].toInt() and 31) == 7 } ?: throw IOException("Missing H.264 SPS")
    val pps = nals.firstOrNull { (it[0].toInt() and 31) == 8 } ?: throw IOException("Missing H.264 PPS")
    val format = MediaFormat.createVideoFormat("video/avc", reader.header.width, reader.header.height).apply {
        setByteBuffer("csd-0", ByteBuffer.wrap(annexB(sps)))
        setByteBuffer("csd-1", ByteBuffer.wrap(annexB(pps)))
        setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 2_097_152)
        if (Build.VERSION.SDK_INT >= 30) setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
    }
    val decoder = MediaCodec.createDecoderByType("video/avc")
    val draining = AtomicBoolean(true)
    val error = AtomicReference<Exception?>()
    var outputThread: Thread? = null
    try {
        decoder.configure(format, surface, null, 0)
        decoder.start()
        onSize(reader.header.width, reader.header.height)
        outputThread = Thread({
            val info = MediaCodec.BufferInfo()
            try {
                while (draining.get() && running()) {
                    var latest = decoder.dequeueOutputBuffer(info, 10000)
                    if (latest < 0) continue
                    // If rendering fell behind, discard old decoded frames and render the latest.
                    for (i in 0 until 7) {
                        val next = decoder.dequeueOutputBuffer(info, 0)
                        if (next < 0) break
                        decoder.releaseOutputBuffer(latest, false)
                        latest = next
                    }
                    decoder.releaseOutputBuffer(latest, true)
                }
            } catch (e: Exception) { if (draining.get() && running()) error.set(e) }
        }, "pocketcoder-video-render").also { it.start() }
        var packet: VideoPacket? = first
        var number = 0L
        while (running() && surface.isValid) {
            val frame = packet ?: break
            error.get()?.let { throw IOException("Video decoder failed", it) }
            var index = decoder.dequeueInputBuffer(10000)
            while (index < 0 && running()) {
                error.get()?.let { throw IOException("Video decoder failed", it) }
                index = decoder.dequeueInputBuffer(10000)
            }
            if (index < 0 || !running()) break
            val buffer = decoder.getInputBuffer(index) ?: throw IOException("Missing video input buffer")
            buffer.clear()
            if (buffer.remaining() < frame.bytes.size) throw IOException("Encoded frame too large")
            buffer.put(frame.bytes)
            decoder.queueInputBuffer(index, 0, frame.bytes.size, number++ * 1_000_000L / reader.header.fps, 0)
            packet = reader.next()
        }
        if (running()) throw IOException("Video stream ended")
    } finally {
        draining.set(false)
        outputThread?.join(1500)
        try { decoder.stop() } catch (_: Exception) { }
        decoder.release()
    }
}
