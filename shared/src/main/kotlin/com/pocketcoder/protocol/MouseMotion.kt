package com.pocketcoder.protocol

import kotlin.math.roundToInt

/** Spread a touch/network burst over at most 16ms, without changing its total distance. */
class MouseMotion(private val now: () -> Long = { System.nanoTime() }) {
    private var x = 0
    private var y = 0
    private var due = 0L
    private var last = 0L
    fun offer(dx: Int, dy: Int) {
        val time = now()
        if (x == 0 && y == 0) last = time
        x = (x + dx).coerceIn(-2500, 2500)
        y = (y + dy).coerceIn(-2500, 2500)
        due = time + 16_000_000L
    }
    fun step(flush: Boolean = false): Pair<Int, Int> {
        val time = now()
        if (x == 0 && y == 0) { last = time; return 0 to 0 }
        val ratio = if (flush || time >= due) 1.0 else
            ((time - last).toDouble() / (due - last).coerceAtLeast(1)).coerceIn(0.0, 1.0)
        val dx = (x * ratio).roundToInt(); val dy = (y * ratio).roundToInt()
        x -= dx; y -= dy; last = time
        return dx to dy
    }
    fun clear() { x = 0; y = 0; due = 0; last = 0 }
}
