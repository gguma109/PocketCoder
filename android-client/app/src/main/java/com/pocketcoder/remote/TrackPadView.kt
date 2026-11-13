package com.pocketcoder.remote

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import android.os.SystemClock
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/** Relative mouse pad: move finger to steer, tap to click, long press to right-click, two fingers to wheel. */
class TrackPadView(
    context: Context,
    private val onMove: (Int, Int) -> Unit,
    private val onClick: (String) -> Unit,
    private val onWheel: (Int) -> Unit,
    private val onButton: (Boolean) -> Unit = {}
) : View(context) {
    var sensitivity = 2.1f
    var acceleration = false
    var dragMode = false
    private var dragging = false
    private var previousTime = 0L
    fun cancelDrag() { if (dragging) { dragging = false; onButton(false) } }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var lastX = 0f
    private var lastY = 0f
    private var startX = 0f
    private var startY = 0f
    private var timeDown = 0L
    private var moved = false
    private var dual = false
    private var wheelAccum = 0f

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        paint.color = Color.rgb(24, 36, 54)
        canvas.drawRoundRect(1f, 1f, w-1, h-1, 25f, 25f, paint)
        paint.color = Color.rgb(48, 68, 91)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        canvas.drawRoundRect(1f, 1f, w-1, h-1, 25f, 25f, paint)
        canvas.drawCircle(w/2, h/2, minOf(w, h)*0.24f, paint)
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(244, 187, 86)
        canvas.drawCircle(w/2, h/2, minOf(w, h)*0.065f, paint)
        paint.color = Color.rgb(158, 171, 192)
        paint.textSize = 11 * resources.displayMetrics.density
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText("마우스 이동", w/2, h - 12 * resources.displayMetrics.density, paint)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                lastX = e.x; lastY = e.y
                startX = e.x; startY = e.y
                timeDown = SystemClock.uptimeMillis()
                moved = false; dual = false; wheelAccum = 0f
                previousTime = e.eventTime
                if (dragMode) { dragging = true; onButton(true) }
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> { dual = true; lastY = e.y; return true }
            MotionEvent.ACTION_MOVE -> {
                if (e.pointerCount >= 2) {
                    dual = true
                    wheelAccum += (e.y - lastY) / 18f
                    val ticks = wheelAccum.toInt().coerceIn(-10, 10)
                    if (ticks != 0) { onWheel(-ticks); wheelAccum -= ticks }
                    lastY = e.y; lastX = e.x
                } else if (!dual) {
                    if (hypot((e.x - startX).toDouble(), (e.y - startY).toDouble()) > 12) moved = true
                    val elapsed = (e.eventTime - previousTime).coerceAtLeast(1)
                    val speed = hypot((e.x - lastX).toDouble(), (e.y - lastY).toDouble()) / resources.displayMetrics.density / elapsed
                    val gain = sensitivity.coerceIn(.5f, 4f) * if (acceleration) (1 + (speed * .15).coerceIn(0.0, 1.5)) else 1.0
                    val dx = ((e.x - lastX) * gain).roundToInt().coerceIn(-200, 200)
                    val dy = ((e.y - lastY) * gain).roundToInt().coerceIn(-200, 200)
                    if (hypot((e.x - startX).toDouble(), (e.y - startY).toDouble()) > 12) moved = true
                    if (dx != 0 || dy != 0) onMove(dx, dy)
                    lastX = e.x; lastY = e.y
                    previousTime = e.eventTime
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                val wasDragging = dragging
                cancelDrag()
                if (!wasDragging && !moved && !dual && abs(e.x - startX) < 15 && abs(e.y - startY) < 15) {
                    // Each tap sends one click; the desktop detects a double tap as two clicks.
                    onClick(if (SystemClock.uptimeMillis() - timeDown > 510) "right" else "left")
                    performClick()
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                cancelDrag()
                moved = true
                return true
            }
        }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }
}
