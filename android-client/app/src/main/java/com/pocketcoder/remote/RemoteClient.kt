package com.pocketcoder.remote

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
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
    private val onFrame: (Bitmap) -> Unit
) {
    private data class Session(val id: Int, val base: String, val token: String, val input: InputBuffer = InputBuffer())
    private data class Frame(val session: Int, val bitmap: Bitmap)
    private val main = Handler(Looper.getMainLooper())
    private val frameWorker = Executors.newSingleThreadExecutor()
    private val inputWorker = Executors.newSingleThreadExecutor()
    private val closeWorker = Executors.newSingleThreadExecutor()
    private val epoch = AtomicInteger(0)
    private val frameConnection = AtomicReference<HttpURLConnection?>()
    private val pendingFrame = AtomicReference<Frame?>()
    private val deliveryPosted = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var active = false
    @Volatile private var session: Session? = null

    fun connect(rawHost: String, auth: String) {
        val host = rawHost.trim().removePrefix("http://").trimEnd('/')
        if (!host.matches(Regex("[a-zA-Z0-9.:-]+")) || auth.trim().length < 20) {
            onState(false, "PC 주소 또는 인증 토큰을 확인하세요")
            return
        }
        disconnect(silent = true)
        val current = Session(epoch.incrementAndGet(), "http://$host", auth.trim())
        session = current
        state(current, false, "PC 접속 확인 중…")
        frameWorker.execute {
            try {
                val streaming = withFrameConnection(open(current, "/health")) { c ->
                    if (c.responseCode != 200) throw IOException("서버 인증 실패 (HTTP ${c.responseCode})")
                    val json = c.inputStream.use { JSONObject(it.bufferedReader(Charsets.UTF_8).readText()) }
                    json.optString("stream") == "mjpeg"
                }
                if (!isCurrent(current)) return@execute
                active = true
                state(current, true, if (streaming) "연결됨 · MJPEG 스트리밍" else "연결됨 · 호환 화면 모드")
                var failures = 0
                while (isCurrent(current) && active && !Thread.currentThread().isInterrupted) {
                    try {
                        if (streaming) {
                            withFrameConnection(open(current, "/stream")) { c ->
                                if (c.responseCode != 200) throw IOException("스트림 요청 실패: ${c.responseCode}")
                                if (!c.contentType.orEmpty().startsWith("multipart/x-mixed-replace")) {
                                    throw IOException("지원하지 않는 화면 스트림")
                                }
                                c.inputStream.buffered(32 * 1024).use { input ->
                                    val reader = MjpegReader(input)
                                    while (isCurrent(current) && active) {
                                        val bytes = reader.nextFrame() ?: throw IOException("화면 스트림 종료")
                                        deliver(current, decode(bytes))
                                        failures = 0
                                    }
                                }
                            }
                        } else {
                            val bitmap = withFrameConnection(open(current, "/frame")) { c ->
                                if (c.responseCode != 200) throw IOException("화면 요청 실패: ${c.responseCode}")
                                decode(c.inputStream.use { it.readBytes() })
                            }
                            deliver(current, bitmap)
                            failures = 0
                            Thread.sleep(220)
                        }
                    } catch (e: Exception) {
                        if (!isCurrent(current) || e is InterruptedException) break
                        if (++failures >= 4) throw IOException("PC 화면 연결 끊김: ${e.message}")
                        Thread.sleep(450)
                    }
                }
            } catch (e: Exception) {
                if (isCurrent(current)) {
                    active = false
                    state(current, false, e.message ?: "연결 실패")
                }
            }
        }
    }

    private fun decode(bytes: ByteArray): Bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        ?: throw IOException("이미지 디코딩 실패")

    // The UI owns at most one pending frame. Replace old pending frames rather than queueing them.
    private fun deliver(current: Session, bitmap: Bitmap) {
        if (!isCurrent(current)) { bitmap.recycle(); return }
        pendingFrame.getAndSet(Frame(current.id, bitmap))?.bitmap?.recycle()
        postDelivery()
    }

    private fun postDelivery() {
        if (!deliveryPosted.compareAndSet(false, true)) return
        main.post {
            val frame = pendingFrame.getAndSet(null)
            if (frame != null) {
                if (epoch.get() == frame.session) onFrame(frame.bitmap) else frame.bitmap.recycle()
            }
            deliveryPosted.set(false)
            if (pendingFrame.get() != null) postDelivery()
        }
    }

    fun send(fields: Map<String, String>) {
        val current = session ?: return
        if (!active || !isCurrent(current)) return
        if (!current.input.offer(fields)) {
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
                    if (isCurrent(current) && active) state(current, true, "입력 오류: ${e.message}")
                }
            }
        }
    }

    fun disconnect(silent: Boolean = false) {
        active = false
        val id = epoch.incrementAndGet()
        session = null
        pendingFrame.getAndSet(null)?.bitmap?.recycle()
        val connection = frameConnection.getAndSet(null)
        if (connection != null) closeWorker.execute { connection.disconnect() }
        if (!silent) main.post { if (epoch.get() == id) onState(false, "연결 안 됨") }
    }

    fun shutdown() {
        disconnect(silent = true)
        frameWorker.shutdownNow()
        inputWorker.shutdownNow()
        closeWorker.shutdown()
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
            setRequestProperty("Authorization", "Bearer ${current.token}")
            setRequestProperty("Cache-Control", "no-store")
        }
}
