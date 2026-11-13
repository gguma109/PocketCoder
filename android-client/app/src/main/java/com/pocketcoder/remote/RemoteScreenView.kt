package com.pocketcoder.remote

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import kotlin.math.min

/** Scale-to-fit screenshot with absolute-position taps (0..10000 normalized coordinates). */
class RemoteScreenView(context: Context, private val onTap: (Int, Int) -> Unit) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(57, 74, 95); style = Paint.Style.STROKE; strokeWidth = 2f
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(144, 164, 185); textAlign = Paint.Align.CENTER
        textSize = resources.displayMetrics.scaledDensity * 14f
    }
    private var frame: Bitmap? = null
    private var drawRect = RectF()
    var zoom: Float = 1f
        set(value) { field = value.coerceIn(1f, 3f); invalidate() }

    fun show(bitmap: Bitmap) {
        frame = bitmap
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.rgb(11, 18, 31))
        val bmp = frame
        if (bmp == null) {
            canvas.drawText("PC 화면이 여기에 표시됩니다", width / 2f, height / 2f - 8, textPaint)
            canvas.drawText("연결 후 화면을 터치하면 클릭됩니다", width / 2f, height / 2f + 20, textPaint)
            return
        }
        val scale = min(width.toFloat() / bmp.width, height.toFloat() / bmp.height) * zoom
        val sw = bmp.width * scale
        val sh = bmp.height * scale
        val left = (width - sw) / 2f
        val top = (height - sh) / 2f
        drawRect = RectF(left, top, left + sw, top + sh)
        canvas.save()
        canvas.clipRect(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawBitmap(bmp, null, drawRect, paint)
        canvas.drawRect(drawRect, border)
        canvas.restore()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP && frame != null) {
            if (drawRect.contains(event.x, event.y)) {
                val x = (((event.x - drawRect.left) / drawRect.width()) * 10000).toInt().coerceIn(0, 10000)
                val y = (((event.y - drawRect.top) / drawRect.height()) * 10000).toInt().coerceIn(0, 10000)
                onTap(x, y)
                performClick()
            }
            return true
        }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }
}
