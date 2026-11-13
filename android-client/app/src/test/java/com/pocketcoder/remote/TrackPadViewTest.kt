package com.pocketcoder.remote

import android.app.Activity
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TrackPadViewTest {
    @Test fun everyHighRefreshMoveIsDeliveredWithoutTheOld25msGate() {
        val moves = mutableListOf<Pair<Int, Int>>()
        val pad = TrackPadView(org.robolectric.RuntimeEnvironment.getApplication(), { x, y -> moves += x to y }, {}, {})
        fun touch(action: Int, x: Float, time: Long) {
            val event = MotionEvent.obtain(0, time, action, x, 50f, 0)
            pad.onTouchEvent(event); event.recycle()
        }
        touch(MotionEvent.ACTION_DOWN, 20f, 0)
        repeat(10) { i ->
            ShadowSystemClock.advanceBy(Duration.ofMillis(8))
            touch(MotionEvent.ACTION_MOVE, 22f + i * 2, (i + 1) * 8L)
        }
        touch(MotionEvent.ACTION_UP, 40f, 90)
        assertEquals(10, moves.size)
        assertTrue(moves.all { it.first > 0 && it.second == 0 })
    }

    @Test fun verticalPadDragKeepsMovingMouseWithoutScrollingPage() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get()
            val moves = mutableListOf<Pair<Int, Int>>()
            val scroll = ScrollView(activity)
            val content = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            val pad = TrackPadView(activity, { dx, dy -> moves += dx to dy }, {}, {})
            content.addView(pad, LinearLayout.LayoutParams(400, 300))
            content.addView(View(activity), LinearLayout.LayoutParams(400, 1200))
            scroll.addView(content)
            activity.setContentView(scroll)
            scroll.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(500, View.MeasureSpec.EXACTLY))
            scroll.layout(0, 0, 400, 500)
            val down = SystemClock.uptimeMillis()
            fun touch(action: Int, y: Float, step: Long) {
                ShadowSystemClock.advanceBy(Duration.ofMillis(50))
                val e = MotionEvent.obtain(down, down + step, action, 100f, y, 0)
                scroll.dispatchTouchEvent(e)
                e.recycle()
            }
            touch(MotionEvent.ACTION_DOWN, 200f, 0)
            touch(MotionEvent.ACTION_MOVE, 150f, 50)
            touch(MotionEvent.ACTION_MOVE, 100f, 100)
            touch(MotionEvent.ACTION_UP, 100f, 150)
            assertEquals("The app page must stay still while steering the mouse", 0, scroll.scrollY)
            assertTrue("Mouse movement must continue after the parent touch slop", moves.size >= 2)
            // A drag starting outside the pad must still scroll the page afterward.
            touch(MotionEvent.ACTION_DOWN, 450f, 200)
            touch(MotionEvent.ACTION_MOVE, 350f, 250)
            touch(MotionEvent.ACTION_MOVE, 250f, 300)
            touch(MotionEvent.ACTION_UP, 250f, 350)
            assertTrue("Normal page scrolling must remain available", scroll.scrollY > 0)
        } finally { controller.pause().stop().destroy() }
    }
}
