package com.pocketcoder.remote

import android.content.Context
import android.graphics.*
import android.view.*
import android.widget.FrameLayout
import kotlin.math.min

/** A texture for hardware video plus the compatible bitmap renderer and normalized tap mapping. */
class RemoteScreenView(context: Context, private val onTap: (Int, Int) -> Unit) : FrameLayout(context), TextureView.SurfaceTextureListener {
    @Volatile var videoSurface: Surface? = null
        private set
    private var videoWidth = 0
    private var videoHeight = 0
    private var videoMode = false
    private var drawRect = RectF()
    private var panX = 0f
    private var panY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var downX = 0f
    private var downY = 0f
    private var moved = false
    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            moved = true
            zoom *= detector.scaleFactor
            return true
        }
    })
    private val texture = TextureView(context).apply { surfaceTextureListener = this@RemoteScreenView }
    private val bitmapView = BitmapView(context)
    var zoom: Float = 1f
        set(value) { field = value.coerceIn(1f, 6f); updateGeometry(); bitmapView.invalidate() }
    fun resetZoom() { panX = 0f; panY = 0f; zoom = 1f }
    init {
        setBackgroundColor(Color.rgb(11, 18, 31))
        clipChildren = true
        addView(texture, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(bitmapView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }
    fun beginDisplaySwitch() {
        texture.alpha = 0f
        videoMode = false
        bitmapView.frame = null
        bitmapView.visibility = View.VISIBLE
        bitmapView.invalidate()
        resetZoom()
    }
    fun show(bitmap: Bitmap) {
        texture.alpha = 0f
        videoMode = false
        bitmapView.visibility = View.VISIBLE
        bitmapView.frame = bitmap
        updateGeometry()
        bitmapView.invalidate()
    }
    fun showVideo(width: Int, height: Int) {
        texture.alpha = 1f
        videoMode = true
        videoWidth = width; videoHeight = height
        bitmapView.visibility = View.GONE
        bitmapView.frame = null
        updateGeometry()
    }
    private fun updateGeometry() {
        val w = if (videoMode) videoWidth else bitmapView.frame?.width ?: return
        val h = if (videoMode) videoHeight else bitmapView.frame?.height ?: return
        if (w <= 0 || h <= 0 || width <= 0 || height <= 0) return
        val scale = min(width.toFloat() / w, height.toFloat() / h) * zoom
        val sw = w * scale; val sh = h * scale
        val limitX = maxOf(0f, (sw - width) / 2)
        val limitY = maxOf(0f, (sh - height) / 2)
        panX = panX.coerceIn(-limitX, limitX); panY = panY.coerceIn(-limitY, limitY)
        drawRect.set((width - sw) / 2 + panX, (height - sh) / 2 + panY, (width + sw) / 2 + panX, (height + sh) / 2 + panY)
        if (videoMode) texture.setTransform(Matrix().apply {
            setScale(sw / width, sh / height, width / 2f, height / 2f)
            postTranslate(panX, panY)
        })
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { super.onSizeChanged(w, h, oldw, oldh); updateGeometry() }
    override fun onInterceptTouchEvent(event: MotionEvent) = true
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x; downY = event.y; lastX = event.x; lastY = event.y; moved = false
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_POINTER_DOWN -> moved = true
            MotionEvent.ACTION_MOVE -> {
                if (kotlin.math.abs(event.x - downX) + kotlin.math.abs(event.y - downY) > 8 * resources.displayMetrics.density) moved = true
                if (event.pointerCount == 1 && zoom > 1 && !scaleDetector.isInProgress) {
                    panX += event.x - lastX; panY += event.y - lastY
                    updateGeometry(); bitmapView.invalidate()
                }
                lastX = event.x; lastY = event.y
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> parent?.requestDisallowInterceptTouchEvent(false)
        }
        if (event.actionMasked == MotionEvent.ACTION_UP && !moved && drawRect.contains(event.x, event.y)) {
            onTap((((event.x - drawRect.left) / drawRect.width()) * 10000).toInt().coerceIn(0, 10000),
                (((event.y - drawRect.top) / drawRect.height()) * 10000).toInt().coerceIn(0, 10000))
            performClick()
        }
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) { videoSurface = Surface(surface); updateGeometry() }
    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) { updateGeometry() }
    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) { }
    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean { videoSurface?.release(); videoSurface = null; return true }

    private inner class BitmapView(context: Context) : View(context) {
        var frame: Bitmap? = null
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(Color.rgb(11, 18, 31))
            val bitmap = frame
            if (bitmap != null) canvas.drawBitmap(bitmap, null, drawRect, paint)
            else {
                paint.color = Color.rgb(144, 164, 185)
                paint.textSize = 14 * resources.displayMetrics.density
                paint.textAlign = Paint.Align.CENTER
                canvas.drawText("연결 후 PC 화면이 표시됩니다", width / 2f, height / 2f, paint)
            }
        }
    }
}
