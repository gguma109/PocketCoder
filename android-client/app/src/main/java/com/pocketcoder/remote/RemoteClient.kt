package com.pocketcoder.remote

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** LAN-only HTTP protocol. Authentication via Bearer header; no token in URL or logs. */
class RemoteClient(
    private val onState: (Boolean, String) -> Unit,
    private val onFrame: (Bitmap) -> Unit
) {
    private val main = Handler(Looper.getMainLooper())
    private val frameWorker = Executors.newSingleThreadExecutor()
    private val inputWorker = Executors.newSingleThreadExecutor()
    private val epoch = AtomicInteger(0)
    @Volatile private var active = false
    @Volatile private var base = ""
    @Volatile private var token = ""

    fun connect(rawHost: String, auth: String) {
        val host = rawHost.trim().removePrefix("http://").trimEnd('/')
        if (host.isBlank() || auth.length < 20 || host.contains('/') || host.contains('@')) {
            onState(false, "PC 주소 또는 인증 토큰을 확인하세요")
            return
        }
        // Explicitly HTTP: suitable for trusted LAN only, not internet/WAN.
        if (!host.matches(Regex("[a-zA-Z0-9.:-]+"))) {
            onState(false, "주소는 IP:포트 형식으로 입력하세요")
            return
        }
        disconnect(silent = true)
        base = "http://$host"
        token = auth.trim()
        val session = epoch.incrementAndGet()
        val sessionBase = base
        val sessionToken = token
        main.post { onState(false, "PC 접속 확인 중…") }
        frameWorker.execute {
            try {
                open("/health", "GET", sessionBase, sessionToken).useConnection { c ->
                    if (c.responseCode != 200) throw IOException("서버 인증 실패 (HTTP ${c.responseCode})")
                    c.inputStream.use { it.readBytes() }
                }
                if (epoch.get() != session) return@execute
                active = true
                main.post { if (epoch.get() == session) onState(true, "연결됨 · 화면 수신 중") }
                var failures = 0
                while (active && epoch.get() == session && !Thread.currentThread().isInterrupted) {
                    try {
                        val bitmap = open("/frame", "GET", sessionBase, sessionToken).useConnection { c ->
                            if (c.responseCode != 200) throw IOException("화면 요청 실패: ${c.responseCode}")
                            val bytes = c.inputStream.use { it.readBytes() }
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                ?: throw IOException("이미지 디코딩 실패")
                        }
                        main.post { if (epoch.get() == session) onFrame(bitmap) else bitmap.recycle() }
                        failures = 0
                        Thread.sleep(220) // approximately 3–4 fps depending on capture latency
                    } catch (e: Exception) {
                        if (e is InterruptedException) break
                        failures++
                        if (failures >= 4) throw IOException("PC 화면 연결 끊김: ${e.message}")
                        Thread.sleep(450)
                    }
                }
            } catch (e: Exception) {
                if (epoch.get() == session) {
                    active = false
                    main.post { if (epoch.get() == session) onState(false, e.message ?: "연결 실패") }
                }
            }
        }
    }

    fun send(fields: Map<String, String>) {
        if (!active) return
        val session = epoch.get()
        val sessionBase = base
        val sessionToken = token
        inputWorker.execute {
            if (!active || epoch.get() != session) return@execute
            try {
                val encoded = fields.entries.joinToString("&") {
                    "${URLEncoder.encode(it.key, "UTF-8")}=${URLEncoder.encode(it.value, "UTF-8")}"
                }.toByteArray(Charsets.UTF_8)
                open("/input", "POST", sessionBase, sessionToken).useConnection { c ->
                    c.doOutput = true
                    c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
                    c.outputStream.use { it.write(encoded) }
                    if (c.responseCode != 200) throw IOException("입력 거부: HTTP ${c.responseCode}")
                    c.inputStream.close()
                }
            } catch (e: Exception) {
                main.post { if (epoch.get() == session) onState(true, "입력 오류: ${e.message}") }
            }
        }
    }

    fun disconnect(silent: Boolean = false) {
        active = false
        epoch.incrementAndGet()
        token = ""
        if (!silent) main.post { onState(false, "연결 안 됨") }
    }

    fun shutdown() {
        disconnect(silent = true)
        frameWorker.shutdownNow()
        inputWorker.shutdownNow()
    }

    private fun open(path: String, method: String, sessionBase: String, sessionToken: String): HttpURLConnection =
        (URL(sessionBase + path).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            requestMethod = method
            connectTimeout = 2500
            readTimeout = 3500
            useCaches = false
            setRequestProperty("Authorization", "Bearer $sessionToken")
            setRequestProperty("Cache-Control", "no-store")
        }
}

private inline fun <T> HttpURLConnection.useConnection(block: (HttpURLConnection) -> T): T =
    try { block(this) } finally { disconnect() }
