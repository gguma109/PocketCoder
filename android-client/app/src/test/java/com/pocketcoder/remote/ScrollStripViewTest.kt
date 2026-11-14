package com.pocketcoder.remote

import android.os.Looper
import android.view.MotionEvent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ScrollStripViewTest {
    @Test fun swipeSendsWheelWithoutAddingATapOnRelease() {
        val ticks = mutableListOf<Int>()
        val strip = ScrollStripView(RuntimeEnvironment.getApplication()) { ticks += it }
        strip.layout(0, 0, 80, 200)
        fun touch(action: Int, y: Float) {
            val e = MotionEvent.obtain(0, 0, action, 40f, y, 0)
            strip.onTouchEvent(e); e.recycle()
        }
        touch(MotionEvent.ACTION_DOWN, 160f)
        touch(MotionEvent.ACTION_MOVE, 40f)
        assertTrue(ticks.isNotEmpty()); assertTrue(ticks.all { it > 0 })
        val count = ticks.size
        touch(MotionEvent.ACTION_UP, 40f)
        assertEquals(count, ticks.size)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertEquals("Swipe must cancel arrow repeat", count, ticks.size)
    }
    @Test fun cancellationStopsHeldArrowAndNeverAddsAClick() {
        val ticks = mutableListOf<Int>()
        val strip = ScrollStripView(RuntimeEnvironment.getApplication()) { ticks += it }
        strip.layout(0, 0, 80, 200)
        val down = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 40f, 15f, 0)
        strip.onTouchEvent(down); down.recycle()
        strip.cancelTouch()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertTrue(ticks.isEmpty())
    }
}
