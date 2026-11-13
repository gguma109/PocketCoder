package com.pocketcoder.remote

import android.graphics.*
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w360dp-h780dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ResponsiveUiTest {
    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private fun button(root: View, label: String) = descendants(root).filterIsInstance<TextView>().single { it.text.toString() == label }
    private fun measure(root: View, widthDp: Int, heightDp: Int) {
        val density = root.resources.displayMetrics.density
        val w = (widthDp * density).toInt(); val h = (heightDp * density).toInt()
        root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, w, h)
    }
    private fun seedScreen(screen: RemoteScreenView) {
        val bitmap = Bitmap.createBitmap(1280, 720, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(12, 18, 28))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE; textSize = 27f; color = Color.rgb(132, 202, 179) }
        canvas.drawText("MainActivity.kt                         PocketCoder", 36f, 52f, paint)
        paint.color = Color.rgb(208, 216, 229)
        listOf("class MainActivity : Activity() {", "    override fun onCreate(state: Bundle?) {", "        super.onCreate(state)", "        showRemoteWorkspace()", "    }", "}", "", "$ gradle assembleDebug", "BUILD SUCCESSFUL").forEachIndexed { i, line ->
            canvas.drawText(line, 36f, 112f + i * 48, paint)
        }
        screen.show(bitmap)
    }
    private fun save(root: View, name: String) {
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        val dir = File("build/ui-previews").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    @Test fun s25KeyboardKeepsTheVideoVisibleWhenControlsScroll() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val decor = controller.get().window.decorView
            measure(decor, 360, 780)
            val screen = decor.findViewWithTag<RemoteScreenView>("remoteScreen")
            seedScreen(screen)
            button(decor, "키보드").performClick()
            measure(decor, 360, 780)
            val before = Rect(); screen.getGlobalVisibleRect(before)
            assertTrue(screen.height >= 150 * screen.resources.displayMetrics.density)
            val controls = decor.findViewWithTag<ScrollView>("controlScroll")
            controls.scrollTo(0, 10000)
            val after = Rect(); screen.getGlobalVisibleRect(after)
            assertEquals("Scrolling controls must never move the remote screen", before, after)
            var parent = screen.parent
            while (parent is View) { assertFalse("Video must not be inside a ScrollView", parent is ScrollView); parent = parent.parent }
            val erase = button(decor, "지우기 ⌫")
            val delete = button(decor, "Delete")
            assertTrue(erase.height >= 34 * erase.resources.displayMetrics.density)
            assertTrue(delete.width >= 70 * delete.resources.displayMetrics.density)
            controls.scrollTo(0, 0)
            save(decor, "s25-keyboard")
            button(decor, "한/영 · 영문").performClick()
            measure(decor, 360, 780)
            save(decor, "s25-korean")
            button(decor, "마우스").performClick()
            measure(decor, 360, 780)
            save(decor, "s25-mouse")
            val originalHeight = screen.height
            button(decor, "전체").performClick()
            measure(decor, 360, 780)
            assertTrue(screen.height > originalHeight)
            save(decor, "s25-expanded")
        } finally { controller.pause().stop().destroy() }
    }
    @Test
    @Config(qualifiers = "w780dp-h360dp-land-xhdpi")
    fun landscapeHasVideoBesideControlsAndSmallWindowsStillKeepVideo() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val decor = controller.get().window.decorView
            button(decor, "키보드").performClick()
            measure(decor, 780, 360)
            val screen = decor.findViewWithTag<RemoteScreenView>("remoteScreen")
            val deck = decor.findViewWithTag<View>("controlDeck")
            seedScreen(screen)
            assertTrue(deck.left >= screen.right)
            assertTrue(screen.width > deck.width)
            assertEquals(screen.height, deck.height)
            save(decor, "s25-landscape")
            measure(decor, 320, 600)
            assertTrue(screen.height > 0)
            assertTrue(screen.bottom <= deck.top)
            save(decor, "compact-keyboard")
        } finally { controller.pause().stop().destroy() }
    }
    @Test fun draggingZoomedVideoPansWithoutClickingAndFitResetsIt() {
        val view = RemoteScreenView(org.robolectric.RuntimeEnvironment.getApplication()) { _, _ -> clicks++ }
        measure(view, 360, 240)
        seedScreen(view)
        view.zoom = 2f
        fun touch(action: Int, x: Float, time: Long) {
            val e = MotionEvent.obtain(100, time, action, x, 100f, 0)
            view.onTouchEvent(e); e.recycle()
        }
        touch(MotionEvent.ACTION_DOWN, 150f, 100)
        touch(MotionEvent.ACTION_MOVE, 240f, 150)
        touch(MotionEvent.ACTION_UP, 240f, 200)
        assertEquals(0, clicks)
        view.resetZoom()
        assertEquals(1f, view.zoom)
        touch(MotionEvent.ACTION_DOWN, 150f, 300)
        touch(MotionEvent.ACTION_UP, 150f, 350)
        assertEquals(1, clicks)
    }
    private var clicks = 0
}
