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
        val remembered = getSharedPreferences("pocketcoder", MODE_PRIVATE).getString("autoAddress", null)
        if (!remembered.isNullOrBlank()) {
            address.setText(remembered)
            client.connect(remembered)
        }
    }

    private fun buildLayout() {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(navy)
            isFillViewport = true
            isVerticalScrollBarEnabled = false
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(14), dp(12), dp(20))
        }
        scroll.addView(root)
        setContentView(scroll)

        val header = row()
        val titleColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleColumn.addView(text("POCKET / CODER", 21f, gold, true))
        titleColumn.addView(text("REMOTE DEVELOPMENT CONSOLE  ·  KOTLIN", 10f, muted, true))
        header.addView(titleColumn, LinearLayout.LayoutParams(0, wrap, 1f))
        headerButton = tile("설정", gold, panel, 72, 39) {
            config.visibility = if (config.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        header.addView(headerButton)
        root.addView(header, bottomMargin(10))

        config = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = round(panel, 13)
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        config.addView(text("PC·휴대폰 Tailscale에 같은 계정으로 로그인하세요", 12f, muted), bottomMargin(8))
        config.addView(text("토큰 없이 연결 · PC 주소는 처음 한 번만 입력", 11f, gold), bottomMargin(8))
        address = entry("PC Tailscale IP:포트", false)
        address.setText(getSharedPreferences("pocketcoder", MODE_PRIVATE).getString("address", ""))
        config.addView(address, bottomMargin(6))
        secret = entry("PC 서버에 표시된 인증 토큰", true)
        secret.visibility = View.GONE
        secret.isSaveEnabled = false
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            secret.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        }
        legacyMode = CheckBox(this).apply {
            text = "고급: LAN / 이전 서버 연결"
            textSize = 11f
            setTextColor(muted)
            setOnCheckedChangeListener { _, checked ->
                secret.visibility = if (checked) View.VISIBLE else View.GONE
                if (!checked) secret.text.clear()
            }
        }
        config.addView(legacyMode)
        config.addView(secret, bottomMargin(8))
        connectButton = tile("PC 연결", navy, gold, -1, 44) { toggleConnection() }
        config.addView(connectButton)
        root.addView(config, bottomMargin(9))
        monitorRow = row()
        val monitorScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(monitorRow) }
        root.addView(monitorScroll, bottomMargin(7))

        val statusRow = row().apply { gravity = Gravity.CENTER_VERTICAL }
        status = text("●  연결 안 됨", 12f, muted)
        statusRow.addView(status, LinearLayout.LayoutParams(0, wrap, 1f))
        statusRow.addView(tile("−", white, keyBg, 35, 32) { screen.zoom -= 0.25f })
        statusRow.addView(tile("+", white, keyBg, 35, 32) { screen.zoom += 0.25f }, leftMargin(5))
        root.addView(statusRow, bottomMargin(7))

        screen = RemoteScreenView(this) { x, y -> send("tap", "x" to "$x", "y" to "$y") }
        screen.background = round(panel, 12)
        root.addView(screen, LinearLayout.LayoutParams(match, dp(222)).apply { bottomMargin = dp(9) })

        root.addView(text("SHORTCUT KEYS", 10f, muted, true), bottomMargin(5))
        val shortcutScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val shortcutRow = row()
        listOf("ESC", "TAB", "CTRL", "ALT", "SHIFT", "CMD", "←", "↑", "↓", "→", "PG↑", "PG↓").forEach { label ->
            val shortcutWidth = when (label) { "SHIFT" -> 62; "CTRL", "ALT" -> 55; else -> 46 }
            val button = tile(label, white, keyBg, shortcutWidth, 38) {
                when (label) {
                    "CTRL", "ALT", "SHIFT", "CMD" -> toggleModifier(label)
                    else -> pressKey(when(label) {
                        "←" -> "LEFT"; "→" -> "RIGHT"; "↑" -> "UP"; "↓" -> "DOWN"
                        "PG↑" -> "PAGEUP"; "PG↓" -> "PAGEDOWN"; else -> label
                    })
                }
            }
            if (label in setOf("CTRL", "ALT", "SHIFT", "CMD")) modifierButtons[label] = button
            shortcutRow.addView(button, LinearLayout.LayoutParams(dp(shortcutWidth), dp(38)).apply { leftMargin = dp(3) })
        }
        shortcutScroll.addView(shortcutRow)
        root.addView(shortcutScroll, bottomMargin(10))

        val padLabel = row().apply { gravity = Gravity.CENTER_VERTICAL }
        padLabel.addView(text("MOUSE TRACKPAD", 10f, muted, true), LinearLayout.LayoutParams(0, wrap, 1f))
        padLabel.addView(text("두 손가락 = 스크롤", 10f, muted))
        root.addView(padLabel, bottomMargin(5))

        val padRow = row()
        val touchPad = TrackPadView(this,
            onMove = { dx, dy -> send("move", "dx" to "$dx", "dy" to "$dy") },
            onClick = { button ->
                if (button == "double") send("double", "button" to "left")
                else send("click", "button" to button)
            },
            onWheel = { amount -> send("wheel", "amount" to "$amount") }
        )
        padRow.addView(touchPad, LinearLayout.LayoutParams(0, dp(118), 1f))
        val mouseActions = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        mouseActions.addView(tile("L", navy, gold, 56, 26) { send("click", "button" to "left") },
            LinearLayout.LayoutParams(dp(56), dp(26)).apply { bottomMargin = dp(4) })
        mouseActions.addView(tile("R", white, keyBg, 56, 26) { send("click", "button" to "right") },
            LinearLayout.LayoutParams(dp(56), dp(26)).apply { bottomMargin = dp(4) })
        mouseActions.addView(tile("⇡", white, keyBg, 56, 26) { send("wheel", "amount" to "-3") },
            LinearLayout.LayoutParams(dp(56), dp(26)).apply { bottomMargin = dp(4) })
        mouseActions.addView(tile("⇣", white, keyBg, 56, 26) { send("wheel", "amount" to "3") },
            LinearLayout.LayoutParams(dp(56), dp(26)))
        padRow.addView(mouseActions, leftMargin(6))
        root.addView(padRow, bottomMargin(11))

        root.addView(text("DEVELOPER KEYBOARD", 10f, muted, true), bottomMargin(5))
        val keyboardTools = row()
        languageButton = tile("한/영 · 영문", navy, gold, 100, 36) { toggleLanguage() }
        keyboardTools.addView(languageButton)
        keyboardTools.addView(tile("지우기 ⌫", white, keyBg, 90, 36) { erase() }, leftMargin(5))
        keyboardTools.addView(tile("Delete", white, keyBg, 70, 36) { pressKey("DELETE") }, leftMargin(5))
        root.addView(keyboardTools, bottomMargin(6))
        keyboardRow(root, "1 2 3 4 5 6 7 8 9 0")
        keyboardRow(root, "Q W E R T Y U I O P")
        keyboardRow(root, "A S D F G H J K L")
        keyboardRow(root, "Z X C V B N M , . /")
        val lastRow = row()
        lastRow.addView(key("["), weightedKeyParams(1f))
        lastRow.addView(key("]"), weightedKeyParams(1f))
        lastRow.addView(key("SPACE"), weightedKeyParams(4f))
        lastRow.addView(key("'"), weightedKeyParams(1f))
        lastRow.addView(key("⌫"), weightedKeyParams(1.4f))
        lastRow.addView(key("↵"), weightedKeyParams(1.4f))
        root.addView(lastRow, bottomMargin(11))

        root.addView(text("한글 / 여러 줄 명령 입력 (PC에 붙여넣기)", 11f, muted), bottomMargin(5))
        val textRow = row()
        typed = entry("Claude·Codex 명령을 여기에 입력", false).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            maxLines = 4
            imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
        }
        textRow.addView(typed, LinearLayout.LayoutParams(0, dp(48), 1f))
        textRow.addView(tile("전송", navy, gold, 63, 48) { sendTyped() }, leftMargin(6))
        root.addView(textRow, bottomMargin(8))
        root.addView(tile("입력창 비우기", white, keyBg, 120, 32) { typed.text.clear() }, bottomMargin(6))
        root.addView(text("외부 접속은 PC·휴대폰 Tailscale 연결이 필요합니다. 서버 포트를 인터넷에 직접 노출하지 마세요.", 10f, muted))
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
        headerButton.text = if (isOnline) "해제" else "설정"
        headerButton.setOnClickListener {
            if (connected) client.disconnect() else
                config.visibility = if (config.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        if (isOnline) config.visibility = View.GONE else config.visibility = View.VISIBLE
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
        parent.addView(horizontal, bottomMargin(4))
    }

    private fun weightedKeyParams(weight: Float) = LinearLayout.LayoutParams(0, dp(42), weight).apply {
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
        if (label.length == 1 && Hangul.keys.containsKey(label[0])) letterButtons[label] = view
        return view
    }

    private fun updateMonitors(count: Int, selected: Int) {
        monitorRow.removeAllViews()
        for (index in 0 until count.coerceIn(1, 16)) {
            monitorRow.addView(tile("모니터 ${index + 1}", if (index == selected) navy else white,
                if (index == selected) gold else keyBg, 94, 34) { client.selectDisplay(index) }, leftMargin(4))
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

    override fun onDestroy() {
        client.shutdown()
        super.onDestroy()
    }
}
