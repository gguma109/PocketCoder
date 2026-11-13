package com.pocketcoder.remote

import java.util.concurrent.atomic.AtomicLong

data class LiveMetrics(val fps: Double, val mbps: Double, val rttMs: Long, val inputMs: Long, val decodeMs: Long, val inputState: String, val audioState: String, val viewers: Int)
internal class MetricsCounter {
    private val frames = AtomicLong()
    private val bytes = AtomicLong()
    private var previous = System.nanoTime()
    @Volatile var rtt = 0L
    @Volatile var input = 0L
    @Volatile var decode = 0L
    @Volatile var inputState = "대기"
    @Volatile var audioState = "꺼짐"
    @Volatile var viewers = 1
    fun frame() { frames.incrementAndGet() }
    fun packet(size: Int) { bytes.addAndGet(size.toLong()) }
    @Synchronized fun snapshot(): LiveMetrics {
        val time = System.nanoTime(); val seconds = ((time - previous) / 1e9).coerceAtLeast(.001); previous = time
        return LiveMetrics(frames.getAndSet(0) / seconds, bytes.getAndSet(0) * 8 / seconds / 1e6, rtt, input, decode, inputState, audioState, viewers)
    }
}
