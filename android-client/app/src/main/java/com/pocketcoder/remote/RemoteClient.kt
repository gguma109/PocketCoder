package com.pocketcoder.remote

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.view.Surface
import com.pocketcoder.protocol.InputBuffer
import com.pocketcoder.protocol.MjpegReader
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** HTTP over a trusted LAN or an encrypted Tailscale tunnel. Token only in Bearer headers. */
class RemoteClient(
    private val onState: (Boolean, String) -> Unit,
    private val onFrame: (Bitmap) -> Unit,
    private val onLoginConnected: (String) -> Unit = {},
    private val videoSurface: () -> Surface? = { null },
    private val onVideoSize: (Int, Int) -> Unit = { _, _ -> },
    private val onDisplays: (Int, Int) -> Unit = { _, _ -> },
    private val displayFps: () -> Int = { 60 },
    private val onDisplayChanging: () -> Unit = {},
    private val profile: () -> com.pocketcoder.protocol.VideoProfile = { com.pocketcoder.protocol.VideoProfile() },
    private val onMetrics: (LiveMetrics) -> Unit = {},
    private val onControl: (Boolean, String) -> Unit = { _, _ -> },
    private val audioEnabled: () -> Boolean = { false },
    private val audioVolume: () -> Float = { .7f }
) {
    private data class Session(val id: Int, val base: String, val token: String, val input: InputBuffer = InputBuffer()) {
        val clientId = java.util.UUID.randomUUID().toString()
        @Volatile var controlSupported = false
        @Volatile var canControl = true
        @Volatile var audioAvailable = false
        var statsTask: java.util.concurrent.ScheduledFuture<*>? = null
        @Volatile var streamInput = false
        @Volatile var displayCount = 1
        @Volatile var displayRevision = -1L
        @Volatile var selectedDisplay = 0
        @Volatile var switching = false
        val videoGeneration = AtomicInteger(0)
        val inputConnection = AtomicReference<InputSocket?>()
    }
    private data class Frame(val session: Int, val generation: Int, val bitmap: Bitmap)
    private val main = Handler(Looper.getMainLooper())
    private val metrics = MetricsCounter()
    private val statusWorker = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "pocketcoder-status").apply { isDaemon = true } }
    private val audioPlayer = AudioPlayer { metrics.audioState = it }
    fun framePresented() { metrics.frame() }
    private val frameWorker = Executors.newSingleThreadExecutor()
    private val inputWorker = Executors.newSingleThreadExecutor()
    private val monitorWorker = Executors.newSingleThreadExecutor()
    private val videoConnection = AtomicReference<VideoConnection?>()
    private val closeWorker = Executors.newSingleThreadExecutor()
    private val epoch = AtomicInteger(0)
    private val frameConnection = AtomicReference<HttpURLConnection?>()
    private val pendingFrame = AtomicReference<Frame?>()
    private val deliveryPosted = java.util.concurrent.atomic.AtomicBoolean(false)
    private data class Target(val host: String, val token: String, val legacy: Boolean)
    @Volatile private var target: Target? = null
    @Volatile private var foreground = false
    @Volatile private var active = false
    @Volatile private var session: Session? = null

    fun connect(rawHost: String, auth: String = "", legacyMode: Boolean = false) {
        val host = rawHost.trim().removePrefix("http://").trimEnd('/')
        if (!host.matches(Regex("[a-zA-Z0-9.:-]+")) || (legacyMode && auth.trim().length < 20)) {
            onState(false, if (legacyMode) "PC 주소 또는 인증 토큰을 확인하세요" else "PC Tailscale 주소를 확인하세요")
            return
        }
        target = Target(host, auth, legacyMode)
        disconnect(silent = true, forget = false)
        if (!foreground) return
        val current = Session(epoch.incrementAndGet(), "http://$host", if (legacyMode) auth.trim() else "")
        session = current
        state(current, false, "PC 접속 확인 중…")
        frameWorker.execute {
            try {
                var h264 = false
                var targetFps = 30
                val streaming = withFrameConnection(open(current, "/health")) { c ->
                    if (c.responseCode == 401 && !legacyMode) {
                        val mode = c.getHeaderField("X-PocketCoder-Auth")
                        val version = c.getHeaderField("X-PocketCoder-Version")
                        throw IOException(when {
                            mode == "tailscale" -> "서버 $version 에서 기기 인증을 거부했습니다. PC Tailscale 상태를 확인하세요."
                            mode == "token" -> "서버가 LAN 토큰 모드입니다. run-tailscale-windows.bat로 실행하세요."
                            else -> "서버에서 인증을 거부했습니다. PC 서버를 0.3.1 이상으로 업데이트하세요."
                        })
                    }
                    if (c.responseCode != 200) throw IOException("서버 인증 실패 (HTTP ${c.responseCode})")
                    val json = c.inputStream.use { JSONObject(it.bufferedReader(Charsets.UTF_8).readText()) }
                    if (!legacyMode && json.optString("auth") != "tailscale") throw IOException("Tailscale 로그인 서버 0.3.0으로 업데이트하세요")
                    current.controlSupported = json.optBoolean("controlLease", false)
                    current.canControl = !current.controlSupported
                    current.audioAvailable = json.optBoolean("audio", false)
                    current.streamInput = json.optString("input") == "stream"
                    h264 = json.optString("video") == "h264"
                    targetFps = minOf(json.optInt("videoFps", 30).coerceIn(1, 120), displayFps().coerceIn(30, 120))
                    val count = json.optInt("displays", 1)
                    val selected = json.optInt("display", 0)
                    current.displayCount = count
                    current.selectedDisplay = selected
                    current.displayRevision = json.optLong("displayRevision", -1)
                    main.post { if (isCurrent(current)) onDisplays(count, selected) }
                    json.optString("stream") == "mjpeg"
                }
                if (!isCurrent(current)) return@execute
                active = true
                if (current.controlSupported) updateControl(current, controlRequest(current, "join"))
                current.statsTask = statusWorker.scheduleAtFixedRate({ if (isCurrent(current) && active) pollStatus(current) }, 0, 1, java.util.concurrent.TimeUnit.SECONDS)
                refreshAudio()
                if (!legacyMode) main.post { if (isCurrent(current)) onLoginConnected(host) }
                state(current, true, if (h264) "연결됨 · H.264 · 목표 ${targetFps}fps" else if (streaming) "연결됨 · MJPEG 스트리밍" else "연결됨 · 호환 화면 모드")
                val supportsH264 = h264
                var previousGeneration = current.videoGeneration.get()
                var failures = 0
                while (isCurrent(current) && active && !Thread.currentThread().isInterrupted) {
                    if (current.switching) { Thread.sleep(10); continue }
                    val generation = current.videoGeneration.get()
                    if (generation != previousGeneration) {
                        previousGeneration = generation; failures = 0; h264 = supportsH264
                    }
                    val videoRunning = { isCurrent(current) && active && !current.switching && current.videoGeneration.get() == generation }
                    val decodedFrame = java.util.concurrent.atomic.AtomicBoolean(false)
                    try {
                        if (h264) {
                            val surface = videoSurface()?.takeIf { it.isValid }
                            if (surface == null) { Thread.sleep(20); continue }
                            val c = VideoConnection(current.base, "/video?${profile().copy(fps = minOf(targetFps, profile().fps)).query}", current.token)
                            videoConnection.set(c)
                            try {
                                if (!videoRunning()) continue
                                decodeH264(c.body().buffered(65536), surface, videoRunning, { w, h ->
                                    decodedFrame.set(true)
                                    main.post { if (videoRunning()) {
                                        onVideoSize(w, h)
                                        onState(true, "연결됨 · H.264 · 목표 ${targetFps}fps")
                                    } }
                                }, onPacket = { metrics.packet(it) }, onDecoded = { metrics.decode = it })
                            } finally { videoConnection.compareAndSet(c, null); c.close() }
                        } else if (streaming) {
                            withFrameConnection(open(current, "/stream")) { c ->
                                if (c.responseCode != 200) throw IOException("스트림 요청 실패: ${c.responseCode}")
                                if (!c.contentType.orEmpty().startsWith("multipart/x-mixed-replace")) {
                                    throw IOException("지원하지 않는 화면 스트림")
                                }
                                c.inputStream.buffered(32 * 1024).use { input ->
                                    val reader = MjpegReader(input)
                                    while (videoRunning()) {
                                        val bytes = reader.nextFrame() ?: throw IOException("화면 스트림 종료")
                                        metrics.packet(bytes.size)
                                        deliver(current, decode(bytes), generation)
                                        failures = 0
                                    }
                                }
                            }
                        } else {
                            val bitmap = withFrameConnection(open(current, "/frame")) { c ->
                                if (c.responseCode != 200) throw IOException("화면 요청 실패: ${c.responseCode}")
                                decode(c.inputStream.use { it.readBytes() })
                            }
                            deliver(current, bitmap, generation)
                            failures = 0
                            Thread.sleep(220)
                        }
                    } catch (e: Exception) {
                        if (!isCurrent(current) || e is InterruptedException) break
                        if (!videoRunning()) continue
                        if (decodedFrame.get()) failures = 0
                        if (e is VideoBusyException) { Thread.sleep(30); continue }
                        if (h264 && ++failures >= 3 && targetFps > 60) {
                            targetFps = 60; failures = 0
                            state(current, true, "H.264 60fps로 영상 재시도 중")
                            continue
                        }
                        if (h264 && failures >= 3) {
                            h264 = false; failures = 0
                            state(current, true, "MJPEG 호환 모드 · 영상 디코더를 확인하세요")
                        }
                        else if (!h264 && ++failures >= 4) throw IOException("PC 화면 연결 끊김: ${e.message}")
                        Thread.sleep(450)
                    }
                }
            } catch (e: Exception) {
                if (isCurrent(current)) {
                    active = false
                    state(current, false, "${e.message ?: "연결 실패"} · 자동 재연결 중")
                    main.postDelayed({
                        val retry = target
                        if (foreground && isCurrent(current) && !active && retry != null) {
                            connect(retry.host, retry.token, retry.legacy)
                        }
                    }, 1500)
                }
            }
        }
    }

    private fun decode(bytes: ByteArray): Bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        ?: throw IOException("이미지 디코딩 실패")

    // The UI owns at most one pending frame. Replace old pending frames rather than queueing them.
    private fun deliver(current: Session, bitmap: Bitmap, generation: Int) {
        if (!isCurrent(current) || current.switching || current.videoGeneration.get() != generation) { bitmap.recycle(); return }
        pendingFrame.getAndSet(Frame(current.id, generation, bitmap))?.bitmap?.recycle()
        postDelivery()
    }

    private fun postDelivery() {
        if (!deliveryPosted.compareAndSet(false, true)) return
        main.post {
            val frame = pendingFrame.getAndSet(null)
            if (frame != null) {
                if (epoch.get() == frame.session && session?.videoGeneration?.get() == frame.generation && session?.switching == false) onFrame(frame.bitmap) else frame.bitmap.recycle()
            }
            deliveryPosted.set(false)
            if (pendingFrame.get() != null) postDelivery()
        }
    }

    fun send(fields: Map<String, String>) {
        val current = session ?: return
        if (!active || !isCurrent(current) || current.switching || !current.canControl) return
        if (!current.input.offer(fields + mapOf("screen" to current.selectedDisplay.toString(), "client" to if (current.controlSupported) current.clientId else ""))) {
            state(current, true, "입력 지연 · 네트워크 상태를 확인하세요")
            return
        }
        if (!current.input.startDrain()) return
        inputWorker.execute {
            while (isCurrent(current) && active) {
                val next = current.input.next() ?: break
                try {
                    val encoded = next.entries.joinToString("&") {
                        "${URLEncoder.encode(it.key, "UTF-8")}=${URLEncoder.encode(it.value, "UTF-8")}"
                    }.toByteArray(Charsets.UTF_8)
                    if (current.streamInput) {
                        var c = current.inputConnection.get()
                        if (c == null || !c.isHealthy) {
                            c?.close()
                            metrics.inputState = "연결 중"
                            c = InputSocket(current.base, current.token, onAck = { metrics.input = it; metrics.inputState = "정상" }) { index, revision ->
                                updateDisplay(current, index, revision)
                            }
                            current.inputConnection.set(c)
                        }
                        if (!isCurrent(current)) { current.inputConnection.getAndSet(null)?.close(); break }
                        c.send(encoded)
                        continue
                    }
                    val c = open(current, "/input", "POST")
                    try {
                        c.doOutput = true
                        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
                        c.setFixedLengthStreamingMode(encoded.size)
                        c.outputStream.use { it.write(encoded) }
                        if (c.responseCode != 200) throw IOException("입력 거부: HTTP ${c.responseCode}")
                        c.inputStream.use { it.readBytes() }
                    } finally { c.disconnect() }
                } catch (e: Exception) {
                    current.inputConnection.getAndSet(null)?.close()
                    metrics.inputState = "복구 중"
                    if (isCurrent(current) && active) state(current, true, "입력 오류: ${e.message}")
                }
            }
        }
    }

    fun disconnect(silent: Boolean = false, forget: Boolean = true) {
        if (forget) target = null
        active = false
        val id = epoch.incrementAndGet()
        val previous = session
        session = null
        previous?.statsTask?.cancel(true)
        if (previous?.controlSupported == true) {
            try { monitorWorker.execute { try { controlRequest(previous, "release") } catch (_: Exception) { } } } catch (_: java.util.concurrent.RejectedExecutionException) { }
        }
        audioPlayer.stop()
        previous?.inputConnection?.getAndSet(null)?.close()
        pendingFrame.getAndSet(null)?.bitmap?.recycle()
        videoConnection.getAndSet(null)?.close()
        val connection = frameConnection.getAndSet(null)
        if (connection != null) closeWorker.execute { connection.disconnect() }
        if (!silent) main.post { if (epoch.get() == id) onState(false, "연결 안 됨") }
    }

    fun selectDisplay(index: Int) {
        val current = session ?: return
        if (!active || !current.canControl || (index == current.selectedDisplay && !current.switching)) return
        current.switching = true
        val generation = current.videoGeneration.incrementAndGet()
        pendingFrame.getAndSet(null)?.bitmap?.recycle()
        videoConnection.getAndSet(null)?.close()
        frameConnection.getAndSet(null)?.let { c -> closeWorker.execute { c.disconnect() } }
        onDisplayChanging()
        state(current, true, "모니터 ${index + 1} 전환 중…")
        monitorWorker.execute {
            if (!isCurrent(current) || current.videoGeneration.get() != generation) return@execute
            val c = open(current, "/display", "POST")
            try {
                val bytes = "index=$index&client=${if (current.controlSupported) current.clientId else ""}".toByteArray(Charsets.UTF_8)
                c.doOutput = true
                c.setFixedLengthStreamingMode(bytes.size)
                c.outputStream.use { it.write(bytes) }
                if (c.responseCode != 200) throw IOException("모니터 변경 실패: ${c.responseCode}")
                val receipt = c.inputStream.use { JSONObject(it.bufferedReader().readText()) }
                if (isCurrent(current) && current.videoGeneration.get() == generation) {
                    current.selectedDisplay = index
                    current.displayRevision = receipt.optLong("displayRevision", current.displayRevision)
                    main.post { if (isCurrent(current) && current.videoGeneration.get() == generation) onDisplays(current.displayCount, index) }
                }
            } catch (e: Exception) { state(current, true, e.message ?: "모니터 변경 실패") }
            finally {
                c.disconnect()
                if (current.videoGeneration.get() == generation) current.switching = false
            }
        }
    }

    private fun updateDisplay(current: Session, index: Int, revision: Long) {
        main.post {
            if (isCurrent(current) && !current.switching && revision >= current.displayRevision) {
                current.displayRevision = revision
                if (current.selectedDisplay != index) {
                    current.selectedDisplay = index
                    current.videoGeneration.incrementAndGet()
                    videoConnection.getAndSet(null)?.close()
                    pendingFrame.getAndSet(null)?.bitmap?.recycle()
                    onDisplayChanging(); onDisplays(current.displayCount, index)
                }
            }
        }
    }
    private fun controlRequest(current: Session, action: String): JSONObject {
        val label = URLEncoder.encode(android.os.Build.MODEL.take(40), "UTF-8")
        val bytes = "action=$action&client=${current.clientId}&label=$label".toByteArray(Charsets.UTF_8)
        val c = open(current, "/control", "POST")
        return try {
            c.doOutput = true; c.setFixedLengthStreamingMode(bytes.size)
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
            c.outputStream.use { it.write(bytes) }
            if (c.responseCode != 200) throw IOException("조작권 요청 실패: ${c.responseCode}")
            c.inputStream.use { JSONObject(it.bufferedReader().readText()) }
        } finally { c.disconnect() }
    }
    private fun updateControl(current: Session, state: JSONObject) {
        if (!isCurrent(current)) return
        current.canControl = state.optBoolean("mine", false)
        val label = state.optString("label", "다른 기기")
        if (!current.canControl) metrics.inputState = "보기 전용"
        main.post { if (isCurrent(current)) onControl(current.canControl, label) }
    }
    fun claimControl() {
        val current = session ?: return
        if (!current.controlSupported) return
        monitorWorker.execute { try { updateControl(current, controlRequest(current, "claim")) } catch (e: Exception) { state(current, true, e.message ?: "조작권 요청 실패") } }
    }
    private fun pollStatus(current: Session) {
        val started = System.nanoTime()
        val c = open(current, if (current.controlSupported) "/status?client=${current.clientId}" else "/health")
        try {
            if (c.responseCode != 200) throw IOException("상태 확인 실패")
            val status = c.inputStream.use { JSONObject(it.bufferedReader().readText()) }
            if (!isCurrent(current)) return
            metrics.rtt = (System.nanoTime() - started) / 1_000_000
            metrics.viewers = status.optInt("viewers", 1)
            updateDisplay(current, status.optInt("display", current.selectedDisplay), status.optLong("displayRevision", current.displayRevision))
            if (current.controlSupported) status.optJSONObject("control")?.let { updateControl(current, it) }
            val snapshot = metrics.snapshot()
            main.post { if (isCurrent(current)) onMetrics(snapshot) }
        } catch (_: Exception) {
            if (isCurrent(current)) { metrics.inputState = "연결 확인 중"; val snapshot = metrics.snapshot(); main.post { if (isCurrent(current)) onMetrics(snapshot) } }
        } finally { c.disconnect() }
    }
    fun refreshVideoProfile() {
        val current = session ?: return
        if (!active) return
        current.videoGeneration.incrementAndGet()
        videoConnection.getAndSet(null)?.close()
        onDisplayChanging()
    }
    fun refreshAudio() {
        val current = session
        audioPlayer.volume = audioVolume().coerceIn(0f, 1f)
        if (current != null && active && current.audioAvailable && audioEnabled()) audioPlayer.start(current.base, current.token)
        else { audioPlayer.stop(); if (current != null && audioEnabled() && !current.audioAvailable) metrics.audioState = "PC 모듈 업데이트 필요" }
    }

    fun pause() {
        foreground = false
        disconnect(silent = true, forget = false)
        onState(false, "앱 복귀 시 자동 연결")
    }

    fun resume() {
        foreground = true
        val previous = target ?: return
        connect(previous.host, previous.token, previous.legacy)
    }

    fun shutdown() {
        disconnect(silent = true)
        frameWorker.shutdownNow()
        inputWorker.shutdownNow()
        monitorWorker.shutdownNow()
        closeWorker.shutdown()
        statusWorker.shutdownNow()
    }

    private fun isCurrent(current: Session) = epoch.get() == current.id
    private fun state(current: Session, online: Boolean, message: String) {
        main.post { if (isCurrent(current)) onState(online, message) }
    }

    private fun <T> withFrameConnection(c: HttpURLConnection, block: (HttpURLConnection) -> T): T {
        frameConnection.set(c)
        return try { block(c) } finally {
            frameConnection.compareAndSet(c, null)
            c.disconnect()
        }
    }

    private fun open(current: Session, path: String, method: String = "GET"): HttpURLConnection =
        (URL(current.base + path).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            requestMethod = method
            connectTimeout = 3000
            readTimeout = 5000
            useCaches = false
            if (current.token.isNotEmpty()) setRequestProperty("Authorization", "Bearer ${current.token}")
            setRequestProperty("Cache-Control", "no-store")
        }
}
