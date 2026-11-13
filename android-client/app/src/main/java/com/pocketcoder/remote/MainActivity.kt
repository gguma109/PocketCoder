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
    private lateinit var workspace: RemoteWorkspace
    private lateinit var settingsOverlay: FrameLayout
    private lateinit var fullscreenButton: TextView
    private lateinit var controlScroll: ScrollView
    private lateinit var mousePane: LinearLayout
    private lateinit var composer: LinearLayout
    private lateinit var shortcutStrip: HorizontalScrollView
    private lateinit var mouseToggleButton: TextView
    private var mouseExpanded = true
    private lateinit var clearDraftButton: TextView
    private val compactLandscape get() = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    private lateinit var trackpad: TrackPadView
    private lateinit var dragButton: TextView
    private lateinit var controlButton: TextView
    private lateinit var metricsDetail: TextView
    private var controlling = true
    private var controllerLabel = ""
    private val prefs get() = getSharedPreferences("pocketcoder", MODE_PRIVATE)
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
            onDisplays = { count, selected -> updateMonitors(count, selected) },
            displayFps = { (windowManager.defaultDisplay.refreshRate + .5f).toInt().coerceIn(30, 120) },
            onDisplayChanging = { screen.beginDisplaySwitch() },
            profile = { com.pocketcoder.protocol.VideoProfile(prefs.getInt("videoFps", 120), prefs.getString("quality", "native")!!, prefs.getInt("mbps", 40)) },
            onMetrics = { updateMetrics(it) },
            onControl = { mine, label ->
                controlling = mine; controllerLabel = label
                controlButton.text = if (mine) "조작 중" else "조작권"
                controlButton.background = round(if (mine) gold else keyBg, 8)
                controlButton.setTextColor(if (mine) navy else white)
                if (!mine) { trackpad.cancelDrag(); trackpad.dragMode = false; dragButton.text = "드래그" }
            },
            audioEnabled = { prefs.getBoolean("audioEnabled", true) },
            audioVolume = { prefs.getFloat("audioVolume", .7f) }
        )
        buildLayout()
        checkForUpdates(false)
        typed.setText(savedInstanceState?.getString("draft", "") ?: "")
        setMouseExpanded(savedInstanceState?.getBoolean("mouseExpanded", true) ?: true)
        val remembered = getSharedPreferences("pocketcoder", MODE_PRIVATE).getString("autoAddress", null)
        if (!remembered.isNullOrBlank()) {
            address.setText(remembered)
            client.connect(remembered)
        }
    }

    private fun buildLayout() {
        val outer = FrameLayout(this).apply { tag = "appRoot"; setBackgroundColor(navy); fitsSystemWindows = true; isFocusableInTouchMode = true }
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

        screen = RemoteScreenView(this) { x, y -> send("tap", "x" to "$x", "y" to "$y") }.apply { tag = "remoteScreen"; onPresentedFrame = { client.framePresented() } }
        val controls = LinearLayout(this).apply {
            tag = "controlDeck"; orientation = LinearLayout.VERTICAL
            background = round(panel, 14); setPadding(dp(6), dp(6), dp(6), dp(4))
        }
        val mouseHeader = row().apply { gravity = Gravity.CENTER_VERTICAL }
        mouseHeader.addView(text("마우스", 11f, muted), LinearLayout.LayoutParams(0, dp(28), 1f))
        mouseToggleButton = tile("접기", white, keyBg, 56, 28) { toggleMouse() }
        mouseToggleButton.tag = "mouseToggle"
        dragButton = tile("드래그", white, keyBg, 64, 28) {
            if (!controlling) { Toast.makeText(this, "먼저 조작권을 가져오세요", Toast.LENGTH_SHORT).show() }
            else {
                trackpad.cancelDrag(); trackpad.dragMode = !trackpad.dragMode
                dragButton.text = if (trackpad.dragMode) "드래그 ON" else "드래그"
                dragButton.background = round(if (trackpad.dragMode) gold else keyBg, 8)
                dragButton.setTextColor(if (trackpad.dragMode) navy else white)
            }
        }
        mouseHeader.addView(dragButton, LinearLayout.LayoutParams(dp(64), dp(28)).apply { rightMargin = dp(4) })
        controlButton = tile("조작권", white, keyBg, 64, 28) { client.claimControl() }
        mouseHeader.addView(controlButton, LinearLayout.LayoutParams(dp(64), dp(28)).apply { rightMargin = dp(4) })
        mouseHeader.addView(mouseToggleButton)
        controls.addView(mouseHeader, LinearLayout.LayoutParams(match, dp(32)))
        val shortcuts = row()
        listOf("ESC", "TAB", "PC ⌫", "Delete", "ENTER", "Ctrl+C", "Ctrl+V", "Ctrl+X", "Ctrl+A", "Ctrl+Z", "CTRL", "ALT", "SHIFT", "CMD", "←", "↑", "↓", "→", "PG↑", "PG↓").forEach { label ->
            val width = when { label.startsWith("Ctrl+") -> 66; label in listOf("SHIFT", "CTRL", "PC ⌫", "Delete", "ENTER") -> 56; else -> 44 }
            val button = tile(label, white, keyBg, width, 34) {
                if (label.startsWith("Ctrl+")) {
                    client.send(mapOf("type" to "key", "key" to label.substringAfter('+'), "ctrl" to "1"))
                } else if (label in listOf("CTRL", "ALT", "SHIFT", "CMD")) toggleModifier(label)
                else pressKey(when (label) { "PC ⌫" -> "BACKSPACE"; "Delete" -> "DELETE"; "←" -> "LEFT"; "↑" -> "UP"; "↓" -> "DOWN"; "→" -> "RIGHT"; "PG↑" -> "PAGEUP"; "PG↓" -> "PAGEDOWN"; else -> label })
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
        trackpad = TrackPadView(this,
            onMove = { dx, dy -> send("move", "dx" to "$dx", "dy" to "$dy") },
            onClick = { button -> send(if (button == "double") "double" else "click", "button" to if (button == "double") "left" else button) },
            onWheel = { send("wheel", "amount" to "$it") },
            onButton = { down -> send(if (down) "down" else "up", "button" to "left") }
        ).apply { sensitivity = prefs.getFloat("sensitivity", 2.1f); acceleration = prefs.getBoolean("acceleration", false) }
        padRow.addView(trackpad, LinearLayout.LayoutParams(0, dp(88), 1f))
        val mouseButtons = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        listOf("좌클릭", "우클릭").forEachIndexed { index, label ->
            mouseButtons.addView(tile(label, if (index == 0) navy else white, if (index == 0) gold else keyBg, 68, 42) {
                send("click", "button" to if (index == 0) "left" else "right")
            }, LinearLayout.LayoutParams(dp(68), dp(42)).apply { bottomMargin = if (index == 0) dp(4) else 0 })
        }
        padRow.addView(mouseButtons, LinearLayout.LayoutParams(dp(68), dp(88)).apply { leftMargin = dp(6) })
        mousePane.addView(padRow)
        panes.addView(mousePane)

        composer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; tag = "composer" }
        val composeRow = row().apply { gravity = Gravity.CENTER_VERTICAL }
        typed = entry("여기에 입력", false).apply {
            setSingleLine(false); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            maxLines = 3; imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
            setPadding(dp(10), dp(6), dp(10), dp(6)); showSoftInputOnFocus = true
            tag = "phoneInput"
            setOnFocusChangeListener { _, focused -> if (focused) setMouseExpanded(false) }
            setOnClickListener { setMouseExpanded(false) }
        }
        val composerHeight = if (compactLandscape) 32 else 44
        composeRow.addView(typed, LinearLayout.LayoutParams(0, dp(composerHeight), 1f))
        composeRow.addView(tile("전송", navy, gold, 52, composerHeight) { sendTyped() }, LinearLayout.LayoutParams(dp(52), dp(composerHeight)).apply { leftMargin = dp(5) })
        composeRow.addView(tile("↵", white, keyBg, 36, composerHeight) { pressKey("ENTER") }.apply { textSize = 18f; contentDescription = "Enter" }, LinearLayout.LayoutParams(dp(36), dp(composerHeight)).apply { leftMargin = dp(4) })
        clearDraftButton = tile("비우기", muted, keyBg, 44, composerHeight) { typed.text.clear() }.apply { contentDescription = "입력창 비우기" }
        composeRow.addView(clearDraftButton, LinearLayout.LayoutParams(dp(44), dp(composerHeight)).apply { leftMargin = dp(4) })
        composer.addView(composeRow, LinearLayout.LayoutParams(match, dp(composerHeight + 8)))
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
        config.addView(address, stacked(48, 4))
        secret = entry("PC 서버에 표시된 인증 토큰", true).apply {
            visibility = View.GONE; isSaveEnabled = false
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        }
        legacyMode = CheckBox(this).apply {
            text = "고급: LAN / 이전 서버 연결"; textSize = 11f; setTextColor(muted)
            setOnCheckedChangeListener { _, checked -> secret.visibility = if (checked) View.VISIBLE else View.GONE; if (!checked) secret.text.clear() }
        }
        config.addView(legacyMode)
        config.addView(secret, stacked(48, 8))
        // QR scan and connect are the two ways to connect, so they share one row at the same size.
        val connectRow = row()
        connectRow.addView(tile("QR 스캔 연결", white, keyBg, -1, 48) {
            com.google.zxing.integration.android.IntentIntegrator(this)
                .setDesiredBarcodeFormats(com.google.zxing.integration.android.IntentIntegrator.QR_CODE)
                .setPrompt("PC 제어 창의 연결 QR을 비춰주세요")
                .setBeepEnabled(false).setOrientationLocked(false).initiateScan()
        }.apply { textSize = 13f }, LinearLayout.LayoutParams(0, dp(48), 1f))
        connectButton = tile("PC 연결", navy, gold, -1, 48) { toggleConnection() }.apply { textSize = 13f }
        connectRow.addView(connectButton, LinearLayout.LayoutParams(0, dp(48), 1f).apply { leftMargin = dp(8) })
        config.addView(connectRow, stacked(48, 0))
        addRemoteSettings()
        settingsOverlay.addView(ScrollView(this).apply { tag = "settingsScroll"; addView(config) }, FrameLayout.LayoutParams(match, wrap, Gravity.CENTER).apply { setMargins(dp(16), dp(16), dp(16), dp(16)) })
        outer.addView(settingsOverlay, FrameLayout.LayoutParams(match, match))
        setMouseExpanded(true)
        outer.requestFocus()
    }

    private fun updateMetrics(metrics: LiveMetrics) {
        if (!connected) return
        val input = if (controlling) metrics.inputState else "보기 전용"
        status.text = "${if (controlling) "●" else "○"} ${metrics.fps.toInt()} FPS · 왕복 ${metrics.rttMs}ms · $input"
        metricsDetail.text = "표시 ${"%.1f".format(metrics.fps)} FPS · 수신 ${"%.1f".format(metrics.mbps)}Mbps\n" +
            "네트워크 왕복 ${metrics.rttMs}ms · 입력 응답 ${metrics.inputMs}ms · 앱 내 디코드 ${metrics.decodeMs}ms\n" +
            "동시 시청 ${metrics.viewers}대 · 소리 ${metrics.audioState}\n" +
            if (controlling) "이 기기가 조작 중" else "보기 전용 · 조작 중인 기기: $controllerLabel"
    }
    private fun addRemoteSettings() {
        config.addView(View(this).apply { setBackgroundColor(keyBg) }, LinearLayout.LayoutParams(match, dp(1)).apply { topMargin = dp(18); bottomMargin = dp(14) })
        config.addView(text("기기별 영상 · 입력 · 소리", 14f, white, true), bottomMargin(6))
        fun choice(label: String, labels: List<String>, selected: Int, apply: (Int) -> Unit) {
            val line = row().apply { gravity = Gravity.CENTER_VERTICAL }
            line.addView(text(label, 12f, muted), LinearLayout.LayoutParams(dp(92), dp(40)))
            val spinner = Spinner(this)
            spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
            spinner.setSelection(selected.coerceIn(labels.indices), false)
            var initial = true
            spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    (view as? TextView)?.setTextColor(white)
                    if (initial) { initial = false; return }
                    apply(position)
                }
                override fun onNothingSelected(parent: AdapterView<*>?) { }
            }
            line.addView(spinner, LinearLayout.LayoutParams(0, dp(40), 1f)); config.addView(line)
        }
        val qualities = listOf("native", "balanced", "data")
        choice("화질", listOf("원본 · 최고 선명도", "균형 · 최대 1920", "데이터 절약 · 최대 1280"), qualities.indexOf(prefs.getString("quality", "native"))) {
            prefs.edit().putString("quality", qualities[it]).apply(); client.refreshVideoProfile()
        }
        val fps = listOf(120, 60, 30)
        choice("목표 FPS", listOf("자동 · 최대 120", "60 FPS", "30 FPS"), fps.indexOf(prefs.getInt("videoFps", 120))) {
            prefs.edit().putInt("videoFps", fps[it]).apply(); client.refreshVideoProfile()
        }
        val bandwidth = listOf(5, 15, 40, 80)
        choice("전송 상한", bandwidth.map { "$it Mbps" }, bandwidth.indexOf(prefs.getInt("mbps", 40))) {
            prefs.edit().putInt("mbps", bandwidth[it]).apply(); client.refreshVideoProfile()
        }
        val sensitivityLabel = text("마우스 감도: ${prefs.getFloat("sensitivity", 2.1f)}", 12f, muted)
        config.addView(sensitivityLabel)
        config.addView(SeekBar(this).apply {
            max = 35; progress = ((prefs.getFloat("sensitivity", 2.1f) - .5f) * 10).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                    if (fromUser) { val gain = .5f + value / 10f; trackpad.sensitivity = gain; prefs.edit().putFloat("sensitivity", gain).apply(); sensitivityLabel.text = "마우스 감도: ${"%.1f".format(gain)}" }
                }
                override fun onStartTrackingTouch(bar: SeekBar?) { }
                override fun onStopTrackingTouch(bar: SeekBar?) { }
            })
        }, LinearLayout.LayoutParams(match, dp(36)))
        config.addView(CheckBox(this).apply {
            text = "마우스 가속 사용"; setTextColor(white); isChecked = prefs.getBoolean("acceleration", false)
            setOnCheckedChangeListener { _, on -> trackpad.acceleration = on; prefs.edit().putBoolean("acceleration", on).apply() }
        })
        config.addView(CheckBox(this).apply {
            text = "PC 사운드 듣기 (마이크 수음 없음)"; setTextColor(white); isChecked = prefs.getBoolean("audioEnabled", true)
            setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean("audioEnabled", on).apply(); client.refreshAudio() }
        })
        config.addView(text("PC 사운드 음량", 12f, muted))
        config.addView(SeekBar(this).apply {
            max = 100; progress = (prefs.getFloat("audioVolume", .7f) * 100).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) { if (fromUser) { prefs.edit().putFloat("audioVolume", value / 100f).apply(); client.refreshAudio() } }
                override fun onStartTrackingTouch(bar: SeekBar?) { }
                override fun onStopTrackingTouch(bar: SeekBar?) { }
            })
        }, LinearLayout.LayoutParams(match, dp(36)))
        metricsDetail = text("연결하면 실제 FPS · 왕복 지연 · 입력 상태가 표시됩니다", 11f, muted)
        config.addView(metricsDetail, bottomMargin(8))
        config.addView(tile("조작권 가져오기", white, keyBg, -1, 44) { client.claimControl() }, stacked(44, 8))
        config.addView(tile("앱 ${BuildConfig.VERSION_NAME} · 업데이트 확인", muted, keyBg, -1, 44) { checkForUpdates(true) }, stacked(44, 0))
    }
    private fun checkForUpdates(manual: Boolean) {
        if (!manual && BuildConfig.DEBUG) return
        val previous = prefs.getLong("updateChecked", 0)
        if (!manual && System.currentTimeMillis() - previous < 86400000) return
        prefs.edit().putLong("updateChecked", System.currentTimeMillis()).apply()
        Thread({
            try {
                val update = UpdateChecker.check(BuildConfig.VERSION_CODE)
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    if (update != null) android.app.AlertDialog.Builder(this).setTitle("PocketCoder ${update.version} 업데이트")
                        .setMessage("새 버전이 있습니다. 지금 내려받아 설치할까요?\n설정은 그대로 유지됩니다.")
                        .setPositiveButton("업데이트") { _, _ -> startUpdate(update) }
                        .setNegativeButton("나중에", null).show()
                    else if (manual) Toast.makeText(this, "현재 최신 버전입니다", Toast.LENGTH_SHORT).show()
                }
            } catch (_: Exception) { if (manual) runOnUiThread { Toast.makeText(this, "업데이트 확인 실패 · 인터넷 연결을 확인하세요", Toast.LENGTH_SHORT).show() } }
        }, "pocketcoder-update").apply { isDaemon = true; start() }
    }
    private var pendingUpdate: AvailableUpdate? = null
    private fun startUpdate(update: AvailableUpdate) {
        if (!packageManager.canRequestPackageInstalls()) {
            // One-time Android permission; the download resumes automatically when the user comes back.
            pendingUpdate = update
            Toast.makeText(this, "‘이 출처의 앱 설치 허용’을 켠 뒤 돌아오세요", Toast.LENGTH_LONG).show()
            startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, android.net.Uri.parse("package:$packageName")))
            return
        }
        pendingUpdate = null
        val progress = TextView(this).apply { setPadding(dp(24), dp(16), dp(24), dp(8)); text = "다운로드 준비 중…" }
        val dialog = android.app.AlertDialog.Builder(this).setTitle("PocketCoder ${update.version} 다운로드").setView(progress).setCancelable(false).show()
        Thread({
            val apk = java.io.File(cacheDir, "update.apk")
            try {
                UpdateChecker.download(update, apk) { percent -> runOnUiThread { progress.text = "다운로드 중… $percent%" } }
                runOnUiThread { progress.text = "설치 화면을 여는 중…" }
                UpdateInstaller.install(this, apk)
                runOnUiThread { if (!isDestroyed) dialog.dismiss() }
            } catch (e: Exception) {
                apk.delete()
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    dialog.dismiss()
                    Toast.makeText(this, "업데이트 실패: ${e.message ?: "인터넷 연결을 확인하세요"}", Toast.LENGTH_LONG).show()
                }
            }
        }, "pocketcoder-update-download").apply { isDaemon = true; start() }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        val result = com.google.zxing.integration.android.IntentIntegrator.parseActivityResult(requestCode, resultCode, data)
        if (result != null) {
            if (result.contents != null) try {
                val host = com.pocketcoder.protocol.parseConnectionQr(result.contents)
                address.setText(host); prefs.edit().putString("address", host).apply()
                client.connect(host)
            } catch (_: Exception) { Toast.makeText(this, "PocketCoder PC 연결 QR이 아닙니다", Toast.LENGTH_LONG).show() }
            return
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    private fun setMouseExpanded(expanded: Boolean) {
        mouseExpanded = expanded
        if (!::workspace.isInitialized) return
        controlScroll.visibility = if (expanded) View.VISIBLE else View.GONE
        mouseToggleButton.text = if (expanded) "접기" else "펼치기"
        shortcutStrip.visibility = if (compactLandscape && !expanded) View.GONE else View.VISIBLE
        workspace.controlHeightDp = if (expanded) 244 else if (compactLandscape) 88 else 132
    }

    private fun toggleMouse() {
        val expanded = !mouseExpanded
        if (expanded) {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(typed.windowToken, 0)
            typed.clearFocus()
            window.decorView.findViewWithTag<View>("appRoot").requestFocus()
        }
        setMouseExpanded(expanded)
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
        val wasConnected = connected
        connected = isOnline
        status.text = (if (isOnline) "●  " else "○  ") + message
        status.setTextColor(if (isOnline) gold else muted)
        connectButton.text = if (isOnline) "연결 해제" else "PC 연결"
        headerButton.text = if (isOnline) "설정" else "연결"
        if (isOnline && !wasConnected) settingsOverlay.visibility = View.GONE
    }

    private fun toggleModifier(mod: String) {
        if (!modifiers.add(mod)) modifiers.remove(mod)
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
    }

    private fun send(type: String, vararg values: Pair<String, String>) =
        client.send(mapOf("type" to type) + values.toMap())

    private fun updateMonitors(count: Int, selected: Int) {
        monitorRow.removeAllViews()
        for (index in 0 until count.coerceIn(1, 16)) {
            monitorRow.addView(tile("모니터 ${index + 1}", if (index == selected) navy else white,
                if (index == selected) gold else keyBg, 86, 34) { client.selectDisplay(index) },
                LinearLayout.LayoutParams(dp(86), dp(34)).apply { rightMargin = dp(4) })
        }
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
    /** Fixed-height params with a bottom gap; bottomMargin() alone would collapse buttons/inputs to wrap_content. */
    private fun stacked(heightDp: Int, bottom: Int) = LinearLayout.LayoutParams(match, dp(heightDp)).apply { bottomMargin = dp(bottom) }
    private fun bottomMargin(n: Int) = LinearLayout.LayoutParams(match, wrap).apply { bottomMargin = dp(n) }
    private fun leftMargin(n: Int) = LinearLayout.LayoutParams(wrap, wrap).apply { leftMargin = dp(n) }
    private fun dp(n: Int) = (resources.displayMetrics.density * n + 0.5f).toInt()
    private val match get() = android.view.ViewGroup.LayoutParams.MATCH_PARENT
    private val wrap get() = android.view.ViewGroup.LayoutParams.WRAP_CONTENT

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("draft", typed.text.toString())
        outState.putBoolean("mouseExpanded", mouseExpanded)
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

    override fun onStart() {
        super.onStart()
        client.resume()
        pendingUpdate?.let { if (packageManager.canRequestPackageInstalls()) startUpdate(it) }
    }

    override fun onStop() {
        trackpad.cancelDrag()
        client.pause()
        super.onStop()
    }

    override fun onDestroy() {
        client.shutdown()
        super.onDestroy()
    }
}
