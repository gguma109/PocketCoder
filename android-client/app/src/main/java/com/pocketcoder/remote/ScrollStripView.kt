package com.pocketcoder.remote

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs

/** Dedicated wheel surface: swipe naturally, tap an arrow, or hold it to repeat. */
class ScrollStripView(context: Context, private val onWheel: (Int) -> Unit) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val density = resources.displayMetrics.density
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var startY = 0f
    private var lastY = 0f
    private var remainder = 0f
    private var moved = false
    private var repeating = false
    private var held = false
    private var direction = 1
    private val repeatWheel = object : Runnable {
        override fun run() {
            if (!held || moved || !isEnabled) return
            repeating = true
            onWheel(direction)
            postDelayed(this, 90)
        }
    }
    init { isClickable = true; contentDescription = "스크롤: 위아래로 밀기, 화살표 탭 또는 길게 누르기" }
    fun cancelTouch() {
        held = false; isPressed = false; removeCallbacks(repeatWheel)
        parent?.requestDisallowInterceptTouchEvent(false)
    }
    override fun onDetachedFromWindow() { cancelTouch(); super.onDetachedFromWindow() }
    override fun onDraw(canvas: Canvas) {
        paint.color = Color.rgb(34, 49, 70)
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), 9 * density, 9 * density, paint)
        paint.color = Color.rgb(230, 237, 245); paint.textAlign = Paint.Align.CENTER
        paint.textSize = 16 * density
        canvas.drawText("↑", width / 2f, 22 * density, paint)
        canvas.drawText("↓", width / 2f, height - 10 * density, paint)
        paint.color = Color.rgb(144, 161, 184); paint.textSize = 9 * density
        canvas.drawText("스크롤", width / 2f, height / 2f + 3 * density, paint)
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) { cancelTouch(); return false }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                held = true; isPressed = true; startY = event.y; lastY = event.y
                moved = false; repeating = false; remainder = 0f
                direction = if (event.y < height / 2f) -1 else 1
                postDelayed(repeatWheel, 350)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!held) return true
                if (!moved && abs(event.y - startY) > slop) { moved = true; removeCallbacks(repeatWheel) }
                if (moved) {
                    remainder += (lastY - event.y) / (16 * density)
                    val ticks = remainder.toInt().coerceIn(-10, 10)
                    if (ticks != 0) { onWheel(ticks); remainder -= ticks }
                }
                lastY = event.y
            }
            MotionEvent.ACTION_UP -> {
                if (held && !moved && !repeating) performClick()
                cancelTouch()
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> cancelTouch()
        }
        return true
    }
    override fun performClick(): Boolean { if (!isEnabled) return false; super.performClick(); onWheel(direction); return true }
}
