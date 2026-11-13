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
        root.viewTreeObserver.dispatchOnPreDraw()
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
        // Match Android's pre-draw traversal after switching a previously GONE panel visible.
        root.viewTreeObserver.dispatchOnPreDraw()
        descendants(root).filterIsInstance<TextView>().filter { it.isShown }.forEach { it.onPreDraw() }
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        val dir = File("build/ui-previews").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    @Test fun nativeInputLeavesVideoVisibleWithReducedKeyboardWindow() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val decor = controller.get().window.decorView
            measure(decor, 360, 780)
            val screen = decor.findViewWithTag<RemoteScreenView>("remoteScreen")
            seedScreen(screen)
            val pad = descendants(decor).filterIsInstance<TrackPadView>().single()
            assertEquals((88 * pad.resources.displayMetrics.density).toInt(), pad.height)
            val padBounds = Rect(); assertTrue(pad.getGlobalVisibleRect(padBounds))
            assertEquals("Entire compact trackpad must fit", pad.height, padBounds.height())
            save(decor, "s25-mouse")
            val originalHeight = screen.height
            val typed = decor.findViewWithTag<android.widget.EditText>("phoneInput")
            typed.requestFocus(); typed.performClick()
            typed.setText("한글과 English를 휴대폰 자판으로 입력")
            measure(decor, 360, 780)
            assertTrue(screen.height > originalHeight)
            save(decor, "s25-input")
            measure(decor, 360, 470) // Available app window after a phone IME opens.
            assertTrue(screen.height >= 150 * screen.resources.displayMetrics.density)
            val inputBounds = Rect(); assertTrue(typed.getGlobalVisibleRect(inputBounds))
            assertEquals(typed.height, inputBounds.height())
            var parent = screen.parent
            while (parent is View) { assertFalse(parent is ScrollView); parent = parent.parent }
            save(decor, "s25-ime-window")
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
            measure(decor, 780, 360)
            val screen = decor.findViewWithTag<RemoteScreenView>("remoteScreen")
            val deck = decor.findViewWithTag<View>("controlDeck")
            seedScreen(screen)
            assertTrue(deck.left >= screen.right)
            assertTrue(screen.width > deck.width)
            assertEquals(screen.height, deck.height)
            save(decor, "s25-landscape")
            val typed = decor.findViewWithTag<android.widget.EditText>("phoneInput")
            typed.requestFocus(); typed.performClick()
            measure(decor, 780, 220)
            assertTrue(screen.height > 0)
            val bounds = Rect(); assertTrue(typed.getGlobalVisibleRect(bounds))
            assertEquals(typed.height, bounds.height())
            save(decor, "landscape-ime-window")
            measure(decor, 320, 600)
            assertTrue(screen.height > 0)
            assertTrue(screen.bottom <= deck.top)
            save(decor, "compact-input")
        } finally { controller.pause().stop().destroy() }
    }
    @Test
    @Config(qualifiers = "w800dp-h1280dp-port-xhdpi")
    fun portraitTabletKeepsFullWidthVideoWhenKeyboardMakesTheWindowWide() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val decor = controller.get().window.decorView
            measure(decor, 800, 1280)
            val screen = decor.findViewWithTag<RemoteScreenView>("remoteScreen")
            val deck = decor.findViewWithTag<View>("controlDeck")
            val typed = decor.findViewWithTag<android.widget.EditText>("phoneInput")
            seedScreen(screen)
            typed.requestFocus(); typed.performClick()
            typed.setText("태블릿에서도 PC 화면 전체 너비로 입력")
            for (availableHeight in listOf(700, 560)) {
                measure(decor, 800, availableHeight)
                val workspace = decor.findViewWithTag<View>("workspace")
                assertTrue("Reproduce an IME window wider than its remaining height", workspace.width > workspace.height)
                assertEquals("IME must not turn portrait video into a narrow column", workspace.width, screen.width)
                assertEquals(screen.width, deck.width)
                assertEquals(screen.bottom, deck.top)
                assertTrue(screen.height > 0)
                val bounds = Rect(); assertTrue(typed.getGlobalVisibleRect(bounds))
                assertEquals(typed.height, bounds.height())
                save(decor, "tablet-portrait-ime-$availableHeight")
            }
            measure(decor, 800, 1280)
            assertEquals(screen.width, deck.width)
            assertEquals(screen.bottom, deck.top)
            save(decor, "tablet-portrait-input")
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun settingsSavePerDevicePreferencesAndRemainReachableOnAPhone() {
        val preferences = org.robolectric.RuntimeEnvironment.getApplication().getSharedPreferences("pocketcoder", 0)
        preferences.edit().putString("quality", "balanced").putInt("videoFps", 60).putInt("mbps", 15).putBoolean("audioEnabled", false).commit()
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val decor = controller.get().window.decorView
            measure(decor, 360, 780)
            button(decor, "연결").performClick()
            measure(decor, 360, 780)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            val spinners = descendants(decor).filterIsInstance<android.widget.Spinner>()
            assertTrue(spinners[0].selectedItem.toString().startsWith("균형"))
            assertEquals("60 FPS", spinners[1].selectedItem.toString())
            val qr = button(decor, "QR 스캔 연결"); val connect = button(decor, "PC 연결")
            assertTrue("QR button must be a full-size touch target", qr.height > 0)
            assertEquals(connect.height, qr.height)
            assertEquals("QR and connect share one row at a 1:1 ratio", connect.width, qr.width)
            val address = descendants(decor).filterIsInstance<android.widget.EditText>().first { it.hint?.toString() == "PC Tailscale IP:포트" }
            assertTrue("address input must not collapse", address.height >= qr.height)
            assertEquals(button(decor, "조작권 가져오기").height, button(decor, "앱 ${BuildConfig.VERSION_NAME} · 업데이트 확인").height)
            spinners[1].setSelection(2)
            measure(decor, 360, 780)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals(30, preferences.getInt("videoFps", 0))
            save(decor, "settings-phone-top")
            val scroll = decor.findViewWithTag<ScrollView>("settingsScroll")
            scroll.fullScroll(View.FOCUS_DOWN)
            measure(decor, 360, 780)
            val bounds = Rect()
            assertTrue(button(decor, "앱 ${BuildConfig.VERSION_NAME} · 업데이트 확인").getGlobalVisibleRect(bounds))
            save(decor, "settings-phone-bottom")
        } finally { controller.pause().stop().destroy(); preferences.edit().clear().commit() }
    }

    @Test fun monitorChangeClearsTheOldFrameAndMjpegMarginsAreOpaque() {
        val view = RemoteScreenView(org.robolectric.RuntimeEnvironment.getApplication()) { _, _ -> }
        measure(view, 360, 600)
        view.setBackgroundColor(Color.RED) // Any transparent letterbox would expose stale content underneath.
        view.show(Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) })
        view.zoom = 3f
        view.beginDisplaySwitch()
        assertEquals(1f, view.zoom)
        var image = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(image))
        assertEquals(Color.rgb(11, 18, 31), image.getPixel(2, 2))
        view.show(Bitmap.createBitmap(1080, 1920, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) })
        image = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(image))
        assertEquals("Previous monitor must not show through the portrait letterbox", Color.rgb(11, 18, 31), image.getPixel(2, view.height / 2))
        assertEquals(Color.BLUE, image.getPixel(view.width / 2, view.height / 2))
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
