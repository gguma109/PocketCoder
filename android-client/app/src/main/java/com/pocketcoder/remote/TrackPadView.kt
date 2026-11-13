package com.pocketcoder.remote

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/** Relative mouse pad: move finger to steer, tap to click, long press to right-click, two fingers to wheel. */
class TrackPadView(
    context: Context,
    private val onMove: (Int, Int) -> Unit,
    private val onClick: (String) -> Unit,
    private val onWheel: (Int) -> Unit
) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var lastX = 0f
    private var lastY = 0f
    private var startX = 0f
    private var startY = 0f
    private var timeDown = 0L
    private var moved = false
    private var dual = false
    private var previousTap = 0L
    private var wheelAccum = 0f
    private var previousMoveSentAt = 0L

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        paint.color = Color.rgb(24, 36, 54)
        canvas.drawRoundRect(1f, 1f, w-1, h-1, 25f, 25f, paint)
        paint.color = Color.rgb(48, 68, 91)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        canvas.drawRoundRect(1f, 1f, w-1, h-1, 25f, 25f, paint)
        canvas.drawCircle(w/2, h/2, h*0.29f, paint)
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(244, 187, 86)
        canvas.drawCircle(w/2, h/2, h*0.085f, paint)
        paint.color = Color.rgb(158, 171, 192)
        paint.textSize = h * 0.13f
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText("TRACKPAD  ·  탭: 클릭 / 길게: 우클릭 / 두 손가락: 스크롤", w/2, h-13, paint)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = e.x; lastY = e.y
                startX = e.x; startY = e.y
                timeDown = System.currentTimeMillis()
                moved = false; dual = false; wheelAccum = 0f
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
                    if (System.currentTimeMillis() - previousMoveSentAt < 35) return true
                    previousMoveSentAt = System.currentTimeMillis()
                    val dx = ((e.x - lastX) * 2.1).roundToInt().coerceIn(-200, 200)
                    val dy = ((e.y - lastY) * 2.1).roundToInt().coerceIn(-200, 200)
                    if (hypot((e.x - startX).toDouble(), (e.y - startY).toDouble()) > 12) moved = true
                    if (dx != 0 || dy != 0) onMove(dx, dy)
                    lastX = e.x; lastY = e.y
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!moved && !dual && abs(e.x - startX) < 15 && abs(e.y - startY) < 15) {
                    val now = System.currentTimeMillis()
                    if (now - timeDown > 510) { onClick("right"); previousTap = 0L }
                    else if (now - previousTap < 340) { onClick("double"); previousTap = 0L }
                    else { onClick("left"); previousTap = now }
                    performClick()
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> return true
        }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }
}
