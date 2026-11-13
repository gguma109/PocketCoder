package com.pocketcoder.remote

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.*
import com.pocketcoder.protocol.Hangul

/** Kotlin-only Android remote-development console: no WebView, ads, tracking, or analytics. */
class MainActivity : Activity() {
    private val navy = Color.rgb(9, 13, 21)
    private val panel = Color.rgb(17, 25, 39)
    private val keyBg = Color.rgb(34, 49, 70)
    private val gold = Color.rgb(255, 202, 105)
    private val white = Color.rgb(230, 237, 245)
    private val muted = Color.rgb(144, 161, 184)
    private lateinit var client: RemoteClient
    private lateinit var screen: RemoteScreenView
    private lateinit var address: EditText
    private lateinit var secret: EditText
    private lateinit var legacyMode: CheckBox
    private lateinit var typed: EditText
    private lateinit var status: TextView
    private lateinit var config: LinearLayout
    private lateinit var connectButton: TextView
    private lateinit var headerButton: TextView
    private lateinit var monitorRow: LinearLayout
    private lateinit var languageButton: TextView
    private lateinit var workspace: RemoteWorkspace
    private lateinit var settingsOverlay: FrameLayout
    private lateinit var fullscreenButton: TextView
    private lateinit var controlScroll: ScrollView
    private lateinit var mousePane: LinearLayout
    private lateinit var keyboardPane: LinearLayout
    private lateinit var textPane: LinearLayout
    private lateinit var composer: LinearLayout
    private lateinit var shortcutStrip: HorizontalScrollView
    private lateinit var clearDraftButton: TextView
    private val compactLandscape get() = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    private val modeButtons = mutableMapOf<Int, TextView>()
    private var activeMode = 0
    private val letterButtons = mutableMapOf<String, TextView>()
    private var koreanMode = false
    private var connected = false
    private val modifiers = linkedSetOf<String>()
    private val modifierButtons = mutableMapOf<String, TextView>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = navy
        window.navigationBarColor = navy
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        client = RemoteClient(
            onState = { on, message -> runOnUiThread { showState(on, message) } },
            onFrame = { bitmap -> runOnUiThread { screen.show(bitmap) } },
            onLoginConnected = { host ->
                getSharedPreferences("pocketcoder", MODE_PRIVATE).edit().putString("autoAddress", host).apply()
            },
            videoSurface = { if (::screen.isInitialized) screen.videoSurface else null },
            onVideoSize = { w, h -> screen.showVideo(w, h) },
            onDisplays = { count, selected -> updateMonitors(count, selected) }
        )
        buildLayout()
        typed.setText(savedInstanceState?.getString("draft", "") ?: "")
        if (savedInstanceState?.getBoolean("korean", false) == true) toggleLanguage()
        selectMode(savedInstanceState?.getInt("mode", 0) ?: 0)
        val remembered = getSharedPreferences("pocketcoder", MODE_PRIVATE).getString("autoAddress", null)
        if (!remembered.isNullOrBlank()) {
            address.setText(remembered)
            client.connect(remembered)
        }
    }

    private fun buildLayout() {
        val outer = FrameLayout(this).apply { tag = "appRoot"; setBackgroundColor(navy); fitsSystemWindows = true }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(4), dp(10), dp(6))
        }
        outer.addView(root, FrameLayout.LayoutParams(match, match))
        setContentView(outer)

        val header = row().apply { gravity = Gravity.CENTER_VERTICAL }
        val title = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        title.addView(text("POCKET CODER", 16f, gold, true))
        status = text("●  연결 안 됨", 10f, muted).apply {
            setSingleLine(true); ellipsize = android.text.TextUtils.TruncateAt.END
        }
        title.addView(status)
        header.addView(title, LinearLayout.LayoutParams(0, match, 1f))
        fullscreenButton = tile("전체", white, keyBg, 48, if (compactLandscape) 32 else 38) { toggleExpanded() }
        header.addView(fullscreenButton)
        headerButton = tile("연결", navy, gold, 58, if (compactLandscape) 32 else 38) { settingsOverlay.visibility = View.VISIBLE }
        header.addView(headerButton, LinearLayout.LayoutParams(dp(58), dp(if (compactLandscape) 32 else 38)).apply { leftMargin = dp(6) })
        root.addView(header, LinearLayout.LayoutParams(match, dp(if (compactLandscape) 34 else 46)))

        val monitorBar = row().apply { gravity = Gravity.CENTER_VERTICAL }
        monitorRow = row()
        monitorBar.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false; addView(monitorRow)
        }, LinearLayout.LayoutParams(0, dp(38), 1f))
        monitorBar.addView(tile("−", white, keyBg, 36, 34) { screen.zoom -= .25f })
        monitorBar.addView(tile("맞춤", muted, panel, 44, 34) { screen.resetZoom() })
        monitorBar.addView(tile("+", white, keyBg, 36, 34) { screen.zoom += .25f })
        root.addView(monitorBar, LinearLayout.LayoutParams(match, dp(if (compactLandscape) 36 else 40)))

        screen = RemoteScreenView(this) { x, y -> send("tap", "x" to "$x", "y" to "$y") }.apply { tag = "remoteScreen" }
        val controls = LinearLayout(this).apply {
            tag = "controlDeck"; orientation = LinearLayout.VERTICAL
            background = round(panel, 14); setPadding(dp(6), dp(6), dp(6), dp(4))
        }
        val modes = row()
        listOf("마우스", "키보드", "텍스트").forEachIndexed { index, label ->
            val button = tile(label, white, keyBg, -1, 36) { selectMode(index) }
            modeButtons[index] = button
            modes.addView(button, LinearLayout.LayoutParams(0, dp(if (compactLandscape) 28 else 36), 1f).apply { setMargins(dp(2), 0, dp(2), 0) })
        }
        controls.addView(modes, LinearLayout.LayoutParams(match, dp(if (compactLandscape) 32 else 38)))
        val shortcuts = row()
        listOf("ESC", "TAB", "CTRL", "ALT", "SHIFT", "CMD", "←", "↑", "↓", "→", "PG↑", "PG↓").forEach { label ->
            val width = if (label in listOf("SHIFT", "CTRL")) 54 else 44
            val button = tile(label, white, keyBg, width, 34) {
                if (label in listOf("CTRL", "ALT", "SHIFT", "CMD")) toggleModifier(label)
                else pressKey(when (label) { "←" -> "LEFT"; "↑" -> "UP"; "↓" -> "DOWN"; "→" -> "RIGHT"; "PG↑" -> "PAGEUP"; "PG↓" -> "PAGEDOWN"; else -> label })
            }
            if (label in listOf("CTRL", "ALT", "SHIFT", "CMD")) modifierButtons[label] = button
            shortcuts.addView(button, LinearLayout.LayoutParams(dp(width), dp(34)).apply { rightMargin = dp(4) })
        }
        shortcutStrip = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(shortcuts) }
        controls.addView(shortcutStrip, LinearLayout.LayoutParams(match, dp(36)))

        val panes = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        controlScroll = ScrollView(this).apply {
            tag = "controlScroll"; isFillViewport = true; isVerticalScrollBarEnabled = false; addView(panes)
        }
        controls.addView(controlScroll, LinearLayout.LayoutParams(match, 0, 1f))
        mousePane = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        mousePane.addView(text("탭으로 클릭 · 길게 눌러 우클릭 · 두 손가락 스크롤", 10f, muted), bottomMargin(5))
        val padRow = row()
        padRow.addView(TrackPadView(this,
            onMove = { dx, dy -> send("move", "dx" to "$dx", "dy" to "$dy") },
            onClick = { button -> send(if (button == "double") "double" else "click", "button" to if (button == "double") "left" else button) },
            onWheel = { send("wheel", "amount" to "$it") }
        ), LinearLayout.LayoutParams(0, dp(146), 1f))
        val mouseButtons = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        listOf("좌클릭", "우클릭", "스크롤 ↑", "스크롤 ↓").forEachIndexed { index, label ->
            mouseButtons.addView(tile(label, if (index == 0) navy else white, if (index == 0) gold else keyBg, 68, 34) {
                when (index) { 0 -> send("click", "button" to "left"); 1 -> send("click", "button" to "right"); 2 -> send("wheel", "amount" to "-3"); else -> send("wheel", "amount" to "3") }
            }, LinearLayout.LayoutParams(dp(68), dp(34)).apply { bottomMargin = dp(3) })
        }
        padRow.addView(mouseButtons, LinearLayout.LayoutParams(dp(68), dp(146)).apply { leftMargin = dp(6) })
        mousePane.addView(padRow)
        panes.addView(mousePane)

        keyboardPane = LinearLayout(this).apply { tag = "keyboardPane"; orientation = LinearLayout.VERTICAL }
        val keyboardTools = row()
        languageButton = tile("한/영 · 영문", navy, gold, -1, 36) { toggleLanguage() }
        val eraseButton = tile("지우기 ⌫", white, keyBg, -1, 36) { erase() }
        val deleteButton = tile("Delete", white, keyBg, -1, 36) { pressKey("DELETE") }
        listOf(languageButton, eraseButton, deleteButton).forEach { button ->
            keyboardTools.addView(button, LinearLayout.LayoutParams(0, dp(if (compactLandscape) 28 else 36), 1f).apply { setMargins(dp(2), 0, dp(2), 0) })
        }
        keyboardPane.addView(keyboardTools, LinearLayout.LayoutParams(match, dp(if (compactLandscape) 32 else 38)))
        keyboardRow(keyboardPane, "1 2 3 4 5 6 7 8 9 0")
        keyboardRow(keyboardPane, "Q W E R T Y U I O P")
        keyboardRow(keyboardPane, "A S D F G H J K L")
        keyboardRow(keyboardPane, "Z X C V B N M , . /")
        val last = row()
        listOf("[" to 1f, "]" to 1f, "SPACE" to 3.8f, "'" to 1f, "⌫" to 1.4f, "↵" to 1.4f).forEach { (label, weight) -> last.addView(key(label), weightedKeyParams(weight)) }
        keyboardPane.addView(last, bottomMargin(3))
        panes.addView(keyboardPane)

        textPane = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        textPane.addView(text("긴 명령이나 한글을 작성해 PC에 붙여넣습니다.", 12f, muted), bottomMargin(8))
        textPane.addView(tile("휴대폰 키보드 열기", white, keyBg, -1, 42) {
            typed.showSoftInputOnFocus = true; typed.requestFocus()
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(typed, InputMethodManager.SHOW_IMPLICIT)
        })
        textPane.addView(tile("PC Enter", navy, gold, -1, 38) { pressKey("ENTER") }, LinearLayout.LayoutParams(match, dp(38)).apply { topMargin = dp(8) })
        panes.addView(textPane)

        composer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; tag = "composer" }
        val composeRow = row().apply { gravity = Gravity.CENTER_VERTICAL }
        typed = entry("Claude·Codex 명령 또는 한글 입력", false).apply {
            setSingleLine(false); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            maxLines = 3; imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
            setPadding(dp(10), dp(6), dp(10), dp(6)); showSoftInputOnFocus = false
        }
        val composerHeight = if (compactLandscape) 32 else 44
        composeRow.addView(typed, LinearLayout.LayoutParams(0, dp(composerHeight), 1f))
        composeRow.addView(tile("전송", navy, gold, 52, composerHeight) { sendTyped() }, LinearLayout.LayoutParams(dp(52), dp(composerHeight)).apply { leftMargin = dp(5) })
        composer.addView(composeRow, LinearLayout.LayoutParams(match, dp(composerHeight + 4)))
        clearDraftButton = tile("입력창 비우기", muted, panel, -1, 28) { typed.text.clear() }
        composer.addView(clearDraftButton)
        controls.addView(composer)
        workspace = RemoteWorkspace(this, screen, controls).apply { tag = "workspace" }
        root.addView(workspace, LinearLayout.LayoutParams(match, 0, 1f))
        updateMonitors(1, 0)

        settingsOverlay = FrameLayout(this).apply {
            tag = "settingsOverlay"; setBackgroundColor(Color.argb(210, 0, 0, 0)); visibility = View.GONE
            setOnClickListener { visibility = View.GONE }
        }
        config = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = round(panel, 16)
            setPadding(dp(16), dp(16), dp(16), dp(16)); setOnClickListener { }
        }
        val settingsTitle = row()
        settingsTitle.addView(text("PC 연결 설정", 17f, white, true), LinearLayout.LayoutParams(0, dp(38), 1f))
        settingsTitle.addView(tile("닫기", white, keyBg, 48, 34) { settingsOverlay.visibility = View.GONE })
        config.addView(settingsTitle, bottomMargin(10))
        config.addView(text("PC와 휴대폰의 Tailscale에 같은 계정으로 로그인", 11f, muted), bottomMargin(10))
        address = entry("PC Tailscale IP:포트", false)
        address.setText(getSharedPreferences("pocketcoder", MODE_PRIVATE).getString("address", ""))
        config.addView(address, bottomMargin(8))
        secret = entry("PC 서버에 표시된 인증 토큰", true).apply {
            visibility = View.GONE; isSaveEnabled = false
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        }
        legacyMode = CheckBox(this).apply {
            text = "고급: LAN / 이전 서버 연결"; textSize = 11f; setTextColor(muted)
            setOnCheckedChangeListener { _, checked -> secret.visibility = if (checked) View.VISIBLE else View.GONE; if (!checked) secret.text.clear() }
        }
        config.addView(legacyMode)
        config.addView(secret, bottomMargin(8))
        connectButton = tile("PC 연결", navy, gold, -1, 44) { toggleConnection() }
        config.addView(connectButton)
        settingsOverlay.addView(ScrollView(this).apply { addView(config) }, FrameLayout.LayoutParams(match, wrap, Gravity.CENTER).apply { setMargins(dp(16), dp(16), dp(16), dp(16)) })
        outer.addView(settingsOverlay, FrameLayout.LayoutParams(match, match))
        selectMode(activeMode)
    }

    private fun selectMode(mode: Int) {
        activeMode = mode
        mousePane.visibility = if (mode == 0) View.VISIBLE else View.GONE
        keyboardPane.visibility = if (mode == 1) View.VISIBLE else View.GONE
        textPane.visibility = if (mode == 2) View.VISIBLE else View.GONE
        composer.visibility = if (mode == 0 || (compactLandscape && mode == 1 && !koreanMode)) View.GONE else View.VISIBLE
        clearDraftButton.visibility = if (compactLandscape && mode == 1) View.GONE else View.VISIBLE
        shortcutStrip.visibility = if (compactLandscape && mode == 1) View.GONE else View.VISIBLE
        typed.showSoftInputOnFocus = mode == 2
        if (mode != 2) {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(typed.windowToken, 0)
            typed.clearFocus()
        }
        modeButtons.forEach { (index, view) ->
            view.setTextColor(if (index == mode) navy else muted)
            view.background = round(if (index == mode) gold else keyBg, 9)
        }
        workspace.controlHeightDp = when (mode) { 1 -> 420; 2 -> 300; else -> 280 }
        controlScroll.scrollTo(0, 0)
    }

    private fun toggleExpanded() {
        workspace.expanded = !workspace.expanded
        fullscreenButton.text = if (workspace.expanded) "복귀" else "전체"
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(typed.windowToken, 0)
    }

    private fun toggleConnection() {
        if (connected) { client.disconnect(); return }
        val host = address.text.toString()
        getSharedPreferences("pocketcoder", MODE_PRIVATE).edit().putString("address", host.trim()).apply()
        getSharedPreferences("pocketcoder", MODE_PRIVATE).edit().remove("autoAddress").apply()
        client.connect(host, secret.text.toString(), legacyMode.isChecked)
    }

    private fun showState(isOnline: Boolean, message: String) {
        connected = isOnline
        status.text = (if (isOnline) "●  " else "○  ") + message
        status.setTextColor(if (isOnline) gold else muted)
        connectButton.text = if (isOnline) "연결 해제" else "PC 연결"
        headerButton.text = if (isOnline) "설정" else "연결"
        if (isOnline) settingsOverlay.visibility = View.GONE
    }

    private fun toggleModifier(mod: String) {
        if (!modifiers.add(mod)) modifiers.remove(mod)
        if (koreanMode) letterButtons.forEach { (letter, view) -> view.text = Hangul.key(letter[0], "SHIFT" in modifiers).toString() }
        modifierButtons.forEach { (label, view) ->
            val on = label in modifiers
            view.setTextColor(if (on) navy else white)
            view.background = round(if (on) gold else keyBg, 8)
        }
    }

    private fun pressKey(name: String) {
        val fields = mutableMapOf("key" to name)
        for ((label, field) in listOf("CTRL" to "ctrl", "ALT" to "alt", "SHIFT" to "shift", "CMD" to "meta")) {
            if (label in modifiers) fields[field] = "1"
        }
        client.send(mapOf("type" to "key") + fields)
        if (modifiers.isNotEmpty()) {
            modifiers.toList().forEach { toggleModifier(it) }
        }
    }

    private fun sendTyped() {
        if (!connected) { Toast.makeText(this, "먼저 PC를 연결하세요", Toast.LENGTH_SHORT).show(); return }
        val value = typed.text.toString()
        if (value.isBlank()) { Toast.makeText(this, "먼저 입력할 문장을 작성하세요", Toast.LENGTH_SHORT).show(); return }
        if (value.length > 1000) { Toast.makeText(this, "한 번에 1000자까지 전송할 수 있습니다", Toast.LENGTH_SHORT).show(); return }
        send("text", "value" to value)
        typed.text.clear()
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(typed.windowToken, 0)
        typed.clearFocus()
    }

    private fun send(type: String, vararg values: Pair<String, String>) =
        client.send(mapOf("type" to type) + values.toMap())

    private fun keyboardRow(parent: LinearLayout, letters: String) {
        val horizontal = row()
        letters.split(' ').forEach { label ->
            val k = key(label)
            horizontal.addView(k, weightedKeyParams(1f))
        }
        parent.addView(horizontal, bottomMargin(if (compactLandscape) 1 else 3))
    }

    private fun weightedKeyParams(weight: Float) = LinearLayout.LayoutParams(0, dp(if (compactLandscape) 28 else 36), weight).apply {
        leftMargin = dp(2); rightMargin = dp(2)
    }

    private fun key(label: String): TextView {
        val view = text(label, if (label == "SPACE") 11f else 14f, white, true).apply {
            gravity = Gravity.CENTER
            background = round(if (label == "↵") Color.rgb(195, 147, 64) else keyBg, 6)
            setOnClickListener {
                if (koreanMode && modifiers.none { it in setOf("CTRL", "ALT", "CMD") }) {
                    when (label) {
                        "⌫" -> erase()
                        "↵" -> { sendTyped(); pressKey("ENTER") }
                        else -> {
                            val value = if (label == "SPACE") ' ' else if (label.length == 1) Hangul.key(label[0], "SHIFT" in modifiers) ?: label[0] else null
                            if (value != null) insertHangul(value)
                        }
                    }
                    return@setOnClickListener
                }
                val name = when (label) { "↵" -> "ENTER"; "⌫" -> "BACKSPACE"; "SPACE" -> "SPACE"; else -> label }
                pressKey(name)
            }
        }
        view.setPadding(dp(1), 0, dp(1), 0)
        view.setSingleLine(true)
        view.includeFontPadding = false
        if (label.length == 1 && Hangul.keys.containsKey(label[0])) letterButtons[label] = view
        return view
    }

    private fun updateMonitors(count: Int, selected: Int) {
        monitorRow.removeAllViews()
        for (index in 0 until count.coerceIn(1, 16)) {
            monitorRow.addView(tile("모니터 ${index + 1}", if (index == selected) navy else white,
                if (index == selected) gold else keyBg, 86, 34) { client.selectDisplay(index) },
                LinearLayout.LayoutParams(dp(86), dp(34)).apply { rightMargin = dp(4) })
        }
    }

    private fun toggleLanguage() {
        koreanMode = !koreanMode
        languageButton.text = if (koreanMode) "한/영 · 한글" else "한/영 · 영문"
        letterButtons.forEach { (letter, view) -> view.text = if (koreanMode) Hangul.key(letter[0], "SHIFT" in modifiers).toString() else letter }
        if (koreanMode) {
            Toast.makeText(this, "한글은 입력창에서 조합한 뒤 전송하세요", Toast.LENGTH_SHORT).show()
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(typed.windowToken, 0)
        }
        selectMode(activeMode)
    }

    private fun insertHangul(value: Char) {
        val start = minOf(typed.selectionStart, typed.selectionEnd).coerceAtLeast(0)
        val end = maxOf(typed.selectionStart, typed.selectionEnd).coerceAtLeast(start)
        val composed = Hangul.append(typed.text.substring(0, start), value)
        typed.text.replace(0, end, composed)
        typed.setSelection(composed.length)
        if ("SHIFT" in modifiers) toggleModifier("SHIFT")
    }

    private fun erase() {
        if (!koreanMode) { pressKey("BACKSPACE"); return }
        val start = minOf(typed.selectionStart, typed.selectionEnd).coerceAtLeast(0)
        val end = maxOf(typed.selectionStart, typed.selectionEnd).coerceAtLeast(start)
        if (start != end) { typed.text.delete(start, end); return }
        val composed = Hangul.backspace(typed.text.substring(0, start))
        typed.text.replace(0, start, composed)
        typed.setSelection(composed.length)
    }

    private fun entry(hint: String, password: Boolean): EditText = EditText(this).apply {
        setSingleLine(true)
        textSize = 13f
        setTextColor(white)
        setHintTextColor(muted)
        setPadding(dp(12), 0, dp(12), 0)
        background = round(Color.rgb(31, 44, 62), 9)
        setHint(hint)
        inputType = if (password) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        layoutParams = LinearLayout.LayoutParams(match, dp(43))
    }

    private fun tile(label: String, fg: Int, bg: Int, widthDp: Int, heightDp: Int, click: () -> Unit): TextView =
        text(label, 12f, fg, true).apply {
            gravity = Gravity.CENTER
            background = round(bg, 9)
            layoutParams = LinearLayout.LayoutParams(if (widthDp < 0) match else dp(widthDp), dp(heightDp))
            setSingleLine(true)
            includeFontPadding = false
            setPadding(dp(3), 0, dp(3), 0)
            ellipsize = android.text.TextUtils.TruncateAt.END
            setOnClickListener { click() }
        }

    private fun text(s: String, sp: Float, color: Int, bold: Boolean = false): TextView = TextView(this).apply {
        text = s
        textSize = sp
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun round(color: Int, radiusDp: Int): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun row(): LinearLayout = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    private fun bottomMargin(n: Int) = LinearLayout.LayoutParams(match, wrap).apply { bottomMargin = dp(n) }
    private fun leftMargin(n: Int) = LinearLayout.LayoutParams(wrap, wrap).apply { leftMargin = dp(n) }
    private fun dp(n: Int) = (resources.displayMetrics.density * n + 0.5f).toInt()
    private val match get() = android.view.ViewGroup.LayoutParams.MATCH_PARENT
    private val wrap get() = android.view.ViewGroup.LayoutParams.WRAP_CONTENT

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("draft", typed.text.toString())
        outState.putInt("mode", activeMode)
        outState.putBoolean("korean", koreanMode)
        super.onSaveInstanceState(outState)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            settingsOverlay.visibility == View.VISIBLE -> settingsOverlay.visibility = View.GONE
            workspace.expanded -> toggleExpanded()
            else -> super.onBackPressed()
        }
    }

    override fun onDestroy() {
        client.shutdown()
        super.onDestroy()
    }
}
