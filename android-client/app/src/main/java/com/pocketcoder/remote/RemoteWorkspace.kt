package com.pocketcoder.remote

import android.content.Context
import android.view.View
import android.view.ViewGroup

/** The video is a sibling of the controls, never a child of a scrolling page. */
class RemoteWorkspace(context: Context, private val remote: View, private val controls: View) : ViewGroup(context) {
    var controlHeightDp = 280
        set(value) { field = value; requestLayout() }
    var expanded = false
        set(value) { field = value; controls.visibility = if (value) GONE else VISIBLE; requestLayout() }
    private var sideBySide = false
    init { addView(remote); addView(controls); clipChildren = true }
    private fun dp(value: Int) = (value * resources.displayMetrics.density + .5f).toInt()
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        // An IME can make a portrait tablet's remaining window wider than it is tall.
        // Only use columns when the device configuration is actually landscape.
        sideBySide = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE &&
            w > h && w >= dp(600)
        val controlWidth = if (expanded) 0 else if (sideBySide) (w * .30f).toInt() else w
        val controlHeight = if (expanded) 0 else if (sideBySide) h else minOf(dp(controlHeightDp), (h * .62f).toInt())
        controls.measure(MeasureSpec.makeMeasureSpec(controlWidth, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(controlHeight, MeasureSpec.EXACTLY))
        remote.measure(MeasureSpec.makeMeasureSpec(if (sideBySide) w - controlWidth else w, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(if (sideBySide || expanded) h else h - controlHeight, MeasureSpec.EXACTLY))
        setMeasuredDimension(w, h)
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        remote.layout(0, 0, remote.measuredWidth, remote.measuredHeight)
        if (sideBySide) controls.layout(remote.measuredWidth, 0, width, height)
        else controls.layout(0, remote.measuredHeight, width, height)
    }
}
