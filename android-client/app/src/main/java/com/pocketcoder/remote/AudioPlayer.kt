package com.pocketcoder.remote

import android.media.*
import com.pocketcoder.protocol.AudioReader
import java.util.concurrent.atomic.AtomicReference

internal class AudioPlayer(private val state: (String) -> Unit) {
    @Volatile private var running = false
    private val generation = java.util.concurrent.atomic.AtomicInteger()
    @Volatile var volume = .7f
    private val connection = AtomicReference<VideoConnection?>()
    private val player = AtomicReference<AudioTrack?>()
    private var worker: Thread? = null
    @Synchronized fun start(base: String, token: String) {
        if (running) { try { player.get()?.setVolume(volume) } catch (_: Exception) { }; return }
        running = true
        val ticket = generation.incrementAndGet()
        worker = Thread({
            while (running && generation.get() == ticket) {
                var activeConnection: VideoConnection? = null
                try {
                    val socket = VideoConnection(base, "/audio", token)
                    activeConnection = socket
                    synchronized(this@AudioPlayer) {
                        if (running && generation.get() == ticket) connection.set(socket)
                    }
                    if (!running || generation.get() != ticket) { socket.close(); break }
                    socket.use {
                        val reader = AudioReader(it.body().buffered(65536))
                        val size = AudioTrack.getMinBufferSize(reader.header.rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
                        require(size > 0)
                        val track = AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
                            .setAudioFormat(AudioFormat.Builder().setSampleRate(reader.header.rate).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                            .setBufferSizeInBytes(maxOf(size, reader.header.rate * 4 / 10)).setTransferMode(AudioTrack.MODE_STREAM)
                            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY).build()
                        synchronized(this@AudioPlayer) {
                            if (running && generation.get() == ticket) player.set(track)
                        }
                        if (!running || generation.get() != ticket) { player.compareAndSet(track, null); track.release(); return@use }
                        try {
                            val packets = java.util.concurrent.ArrayBlockingQueue<ByteArray>(8)
                            val error = java.util.concurrent.atomic.AtomicReference<Exception?>()
                            Thread({
                                try {
                                    while (running && generation.get() == ticket) {
                                        val packet = reader.next()
                                        if (!packets.offer(packet)) { packets.poll(); packets.offer(packet) }
                                    }
                                } catch (e: Exception) { error.set(e) }
                            }, "pocketcoder-audio-receive").apply { isDaemon = true; start() }
                            track.setVolume(volume.coerceIn(0f, 1f)); track.play()
                            if (running && generation.get() == ticket) state("재생 중")
                            while (running && generation.get() == ticket) {
                                error.get()?.let { throw it }
                                val pcm = packets.poll(1, java.util.concurrent.TimeUnit.SECONDS) ?: continue
                                var offset = 0
                                while (offset < pcm.size && running && generation.get() == ticket) {
                                    track.setVolume(volume.coerceIn(0f, 1f))
                                    val count = track.write(pcm, offset, pcm.size - offset, AudioTrack.WRITE_BLOCKING)
                                    if (count <= 0) error("Audio output stopped")
                                    offset += count
                                }
                            }
                        } finally { player.compareAndSet(track, null); try { track.stop() } catch (_: Exception) { }; track.release() }
                    }
                } catch (_: Exception) { if (running && generation.get() == ticket) state("재연결 중") }
                finally { activeConnection?.let { connection.compareAndSet(it, null); it.close() } }
                if (running && generation.get() == ticket) try { Thread.sleep(1000) } catch (_: InterruptedException) { break }
            }
            if (generation.get() == ticket) state("꺼짐")
        }, "pocketcoder-playback").apply { isDaemon = true; start() }
    }
    @Synchronized fun stop() {
        running = false; generation.incrementAndGet(); connection.getAndSet(null)?.close()
        try { player.get()?.pause(); player.get()?.flush() } catch (_: Exception) { }
        worker?.interrupt(); worker = null
        state("꺼짐")
    }
}
