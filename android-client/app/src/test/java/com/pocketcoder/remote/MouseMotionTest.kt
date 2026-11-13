package com.pocketcoder.remote

import com.pocketcoder.protocol.MouseMotion
import org.junit.Assert.*
import org.junit.Test

class MouseMotionTest {
    @Test fun splitsMotionIntoRegularUpdatesAndPreservesDistance() {
        var time = 0L
        val motion = MouseMotion { time }
        motion.offer(80, -40)
        time = 8_000_000
        assertEquals(40 to -20, motion.step())
        time = 16_000_000
        assertEquals(40 to -20, motion.step())
        assertEquals(0 to 0, motion.step())
    }
    @Test fun clickFlushesPendingMotionAndDisplaySwitchDiscardsIt() {
        var time = 0L
        val motion = MouseMotion { time }
        motion.offer(19, 7)
        time = 8_000_000
        val first = motion.step()
        motion.offer(-3, 2)
        val rest = motion.step(flush = true)
        assertEquals(16, first.first + rest.first)
        assertEquals(9, first.second + rest.second)
        motion.offer(100, 100); motion.clear()
        time += 50_000_000
        assertEquals(0 to 0, motion.step())
    }
}
