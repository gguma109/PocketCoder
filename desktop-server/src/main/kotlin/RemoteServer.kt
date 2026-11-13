@file:Suppress("SameParameterValue")

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.awt.*
import java.awt.datatransfer.StringSelection
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import com.pocketcoder.protocol.MjpegReader
import com.pocketcoder.protocol.writeMjpegFrame
import com.pocketcoder.protocol.*
import java.io.DataOutputStream
import java.util.concurrent.atomic.AtomicReference
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** No arbitrary shell execution. Only authenticated screen capture and bounded GUI input. */
fun main(args: Array<String>) {
    if (args.contains("--self-test")) { selfTest(); return }
    if (args.contains("--help")) {
        println("Usage: java -jar pocketcoder-server.jar [--bind=127.0.0.1] [--port=8765] [--display=0]")
        println("Use --bind=<private LAN or Tailscale IPv4>. Use --fps=15 (1..30) for MJPEG streaming.")
        println("Tailscale binding defaults to same-account login, with no token. LAN uses --auth=token.")
        return
    }
    val requestedBind = option(args, "bind", "127.0.0.1")
    val bind = if (requestedBind == "tailscale") {
        val addresses = NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && (it.name.contains("tailscale", true) || it.displayName.orEmpty().contains("tailscale", true)) }
            .flatMap { it.inetAddresses.toList() }.mapNotNull { it.hostAddress }
            .filter(::isTailscaleAddress).distinct()
        require(addresses.size == 1) { "Cannot find one active Tailscale IPv4. Connect Tailscale, or use --bind=<Tailscale IPv4>." }
        addresses.single()
    } else requestedBind
    require(isPrivateBind(bind)) { "Bind must be loopback, private LAN, or Tailscale IPv4; wildcard/public addresses are refused" }
    val fps = option(args, "fps", "15").toIntOrNull()?.takeIf { it in 1..30 }
        ?: error("fps must be between 1 and 30")
    val port = option(args, "port", "8765").toIntOrNull()?.takeIf { it in 1024..65535 }
        ?: error("port must be between 1024 and 65535")
    val display = option(args, "display", "0").toIntOrNull()?.takeIf { it >= 0 }
        ?: error("display must be >= 0")
    require(!GraphicsEnvironment.isHeadless()) { "Desktop session required (headless not supported)" }
    val devices = GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices
    require(display < devices.size) { "Display $display not found. Displays: ${devices.size}" }
    val device = devices[display]
    val area = device.defaultConfiguration.bounds
    val robot = Robot(device).apply { autoDelay = 0; isAutoWaitForIdle = false }
    val authMode = option(args, "auth", if (isTailscaleAddress(bind)) "tailscale" else "token")
    require(authMode in listOf("tailscale", "token")) { "auth must be tailscale or token" }
    val token = if (authMode == "token") newToken() else null
    val verifyIdentity: (HttpExchange) -> Boolean = if (authMode == "tailscale") {
        require(isTailscaleAddress(bind)) { "Login-only mode requires a Tailscale bind address" }
        val lookup = tailscaleLookup()
        val auth = TailscaleAuth(bind, lookup(bind), lookup)
        val checkIdentity: (HttpExchange) -> Boolean = { ex -> auth.allows(ex) }
        checkIdentity
    } else { ex: HttpExchange -> bearerAuthorized(ex, token!!) }
    val authorize: (HttpExchange) -> Boolean = { ex ->
        ex.responseHeaders.set("X-PocketCoder-Version", "0.6.5")
        ex.responseHeaders.set("X-PocketCoder-Auth", authMode)
        verifyIdentity(ex)
    }
    val app = DesktopController(robot, area, devices, display)
    val requestedVideoFps = option(args, "video-fps", "120").toIntOrNull()?.takeIf { it == 30 || it == 60 || it == 120 }
        ?: error("video-fps must be 30, 60 or 120")
    val videoEncoder = VideoEncoder.detect(requestedVideoFps)
    val videoProcess = AtomicReference<Process?>()
    // Apply before the JDK HTTP server reads its configuration: stream small frames immediately.
    System.setProperty("sun.net.httpserver.nodelay", "true")
    val server = HttpServer.create(InetSocketAddress(bind, port), 32)
    server.executor = Executors.newFixedThreadPool(8) { r -> Thread(r, "pocketcoder-http").apply { isDaemon = true } }

    server.createContext("/health") { ex ->
        guarded(ex, authorize, "GET") {
            val current = app.currentBounds()
            send(ex, 200, "application/json; charset=utf-8", """{"ok":true,"version":"0.6.5","width":${current.width},"height":${current.height},"display":${app.selectedDisplay},"displayRevision":${app.displayRevision},"stream":"mjpeg","fps":$fps,"auth":"$authMode","video":"${if (videoEncoder != null) "h264" else "mjpeg"}","videoFps":${videoEncoder?.fps ?: fps},"capture":"${videoEncoder?.capture ?: "gdi"}","input":"stream","displays":${devices.size}}""".toByteArray())
        }
    }
    server.createContext("/frame") { ex ->
        guarded(ex, authorize, "GET") {
            val jpeg = app.jpeg()
            send(ex, 200, "image/jpeg", jpeg)
        }
    }
    // One viewer per desktop. A slow reader never builds an application frame queue.
    val streamSlot = Semaphore(1)
    server.createContext("/video") { ex ->
        guarded(ex, authorize, "GET") {
            val available = videoEncoder ?: run { sendError(ex, 404, "H.264 encoder unavailable"); return@guarded }
            val requested = decodeForm(ex.requestURI.rawQuery.orEmpty())["fps"]?.toIntOrNull() ?: 60
            val encoder = available.forFps(requested.coerceIn(30, 120))
            if (!streamSlot.tryAcquire()) { sendError(ex, 409, "A screen stream is already active"); return@guarded }
            var process: Process? = null
            try {
                val capture = app.currentBounds()
                val startedProcess = encoder.start(capture)
                process = startedProcess
                videoProcess.set(startedProcess)
                ex.responseHeaders.set("Content-Type", "application/x-pocketcoder-avc")
                ex.sendResponseHeaders(200, 0)
                DataOutputStream(ex.responseBody).use { output ->
                    writeVideoHeader(output, encoder.dimensions(capture))
                    val units = AccessUnitReader(startedProcess.inputStream.buffered(65536))
                    var lastAuth = System.nanoTime()
                    while (!Thread.currentThread().isInterrupted) {
                        if (System.nanoTime() - lastAuth > 1_000_000_000L) {
                            if (!authorize(ex)) break
                            lastAuth = System.nanoTime()
                        }
                        val unit = units.next() ?: break
                        writeVideoPacket(output, unit, System.nanoTime() / 1000)
                    }
                }
            } catch (_: java.io.IOException) {
            } finally {
                process?.destroyForcibly()
                videoProcess.compareAndSet(process, null)
                streamSlot.release()
            }
        }
    }
    server.createContext("/display") { ex ->
        guarded(ex, authorize, "POST") {
            val bytes = ex.requestBody.use { it.readNBytes(1025) }
            if (bytes.size > 1024) { sendError(ex, 413, "Display request too large"); return@guarded }
            try {
                val index = decodeForm(bytes.toString(Charsets.UTF_8))["index"]?.toIntOrNull()
                    ?: throw IllegalArgumentException("Missing display index")
                app.selectDisplay(index)
                videoProcess.getAndSet(null)?.destroyForcibly()
                send(ex, 200, "application/json", app.inputReceipt())
            } catch (_: IllegalArgumentException) { sendError(ex, 400, "Invalid display index") }
        }
    }
    val inputSlot = Semaphore(4)
    server.createContext("/input-stream") { ex ->
        guarded(ex, authorize, "POST") {
            if (!inputSlot.tryAcquire()) { sendError(ex, 409, "Input stream already active"); return@guarded }
            try {
                ex.requestBody.buffered(4096).use { input ->
                    var lastAuth = System.nanoTime()
                    while (true) {
                        val line = readInputLine(input) ?: break
                        if (System.nanoTime() - lastAuth > 1_000_000_000L) {
                            if (!authorize(ex)) break
                            lastAuth = System.nanoTime()
                        }
                        if (line.isNotEmpty()) {
                            try { app.perform(decodeForm(line)) }
                            catch (_: IllegalArgumentException) { /* Reject invalid event, preserve the stream. */ }
                        }
                    }
                }
                send(ex, 200, "application/json", """{"ok":true}""".toByteArray())
            } catch (_: java.io.IOException) {
            } finally { inputSlot.release() }
        }
    }
    server.createContext("/stream") { ex ->
        guarded(ex, authorize, "GET") {
            if (!streamSlot.tryAcquire()) { sendError(ex, 409, "A screen stream is already active"); return@guarded }
            try {
                ex.responseHeaders.set("Content-Type", "multipart/x-mixed-replace; boundary=pocketcoder-frame")
                ex.sendResponseHeaders(200, 0)
                ex.responseBody.use { output ->
                    while (!Thread.currentThread().isInterrupted) {
                        val started = System.nanoTime()
                        if (!authorize(ex)) break
                        writeMjpegFrame(output, app.jpeg())
                        val remaining = 1_000_000_000L / fps - (System.nanoTime() - started)
                        if (remaining > 0) Thread.sleep(remaining / 1_000_000, (remaining % 1_000_000).toInt())
                    }
                }
            } catch (_: java.io.IOException) {
                // Disconnects are expected. Do not try to append an HTTP error to an open stream.
            } finally { streamSlot.release() }
        }
    }
    server.createContext("/input") { ex ->
        guarded(ex, authorize, "POST") {
            val len = ex.requestHeaders.getFirst("Content-Length")?.toIntOrNull()
            if (len != null && len > 32768) { sendError(ex, 413, "Request too large"); return@guarded }
            val body = ex.requestBody.use { it.readNBytes(32769) }
            if (body.size > 32768) { sendError(ex, 413, "Request too large"); return@guarded }
            try {
                val form = decodeForm(body.toString(StandardCharsets.UTF_8))
                app.perform(form)
                send(ex, 200, "application/json", app.inputReceipt())
            } catch (bad: IllegalArgumentException) {
                sendError(ex, 400, bad.message ?: "Bad input")
            }
        }
    }
    Runtime.getRuntime().addShutdownHook(Thread { videoProcess.getAndSet(null)?.destroyForcibly(); server.stop(0) })
    server.start()
    println("\n=============================================")
    println(" POCKET CODER - PC server")
    println("=============================================")
    println(" Bind:    $bind:$port")
    println(" Display: $display (${area.width} x ${area.height})")
    if (authMode == "tailscale") {
        println(" Login: Tailscale account authentication. No access token needed.")
        println(" Only devices owned by the SAME Tailscale user are allowed.")
        println(" Enter IP:port once in the Android app; it reconnects automatically next launch.")
    } else {
        println(" Access token (LAN/legacy mode, new each restart):\n $token")
        println(" Enter IP:port and token in the Android app's LAN/legacy mode.")
    }
    println(" Stream:  MJPEG, target $fps fps (actual rate depends on CPU/network)")
    println(" Video:   ${videoEncoder?.codec ?: "MJPEG fallback (install FFmpeg for H.264)"}")
    println(" Capture: ${videoEncoder?.capture ?: "gdi"}; target ${videoEncoder?.fps ?: fps} fps")
    println(" Monitors: ${devices.size}; switch from the Android app")
    if (isTailscaleAddress(bind)) {
        println(" Tailscale address: connect the phone to the SAME tailnet, including over LTE/5G.")
        println(" Encryption is provided by Tailscale; this server itself uses HTTP, not TLS.")
        println(" NEVER open/forward this port to the public internet.")
    } else if (bind != "127.0.0.1" && bind != "localhost") {
        println(" WARNING: HTTP without encryption. Trusted private Wi-Fi only.")
        println(" NEVER open/forward this port to the public internet.")
    } else {
        println(" Loopback only. For phone access use --bind=<this PC's private LAN IPv4 address>")
    }
    println(" Press Ctrl+C to stop.\n")
}

private fun option(args: Array<String>, name: String, default: String): String =
    args.firstOrNull { it.startsWith("--$name=") }?.substringAfter('=') ?: default

internal fun isPrivateBind(value: String): Boolean {
    if (!value.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+"))) return false
    val parts = value.split('.').map { it.toIntOrNull() ?: return false }
    if (parts.any { it !in 0..255 }) return false
    val address = InetAddress.getByAddress(parts.map { it.toByte() }.toByteArray())
    return address.isLoopbackAddress || address.isSiteLocalAddress || isTailscaleAddress(value)
}

internal fun isTailscaleAddress(value: String): Boolean {
    val parts = value.split('.').map { it.toIntOrNull() ?: return false }
    return parts.size == 4 && parts.all { it in 0..255 } && parts[0] == 100 && parts[1] in 64..127
}

private fun newToken(): String = ByteArray(32).also(SecureRandom()::nextBytes)
    .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

private fun bearerAuthorized(ex: HttpExchange, token: String): Boolean {
    val actual = ex.requestHeaders.getFirst("Authorization")?.removePrefix("Bearer ") ?: ""
    return MessageDigest.isEqual(actual.toByteArray(), token.toByteArray())
}

private fun guarded(ex: HttpExchange, token: String, method: String, block: () -> Unit) =
    guarded(ex, { bearerAuthorized(it, token) }, method, block)

private fun guarded(ex: HttpExchange, authorize: (HttpExchange) -> Boolean, method: String, block: () -> Unit) {
    try {
        if (ex.requestMethod != method) { sendError(ex, 405, "Method not allowed"); return }
        if (!authorize(ex)) {
            sendError(ex, 401, "Not authorized. Connect PC and phone to Tailscale with the same personal account."); return
        }
        ex.responseHeaders.set("Cache-Control", "no-store")
        block()
    } catch (e: Exception) {
        System.err.println("Request failed: ${e.javaClass.simpleName}: ${e.message}")
        try { sendError(ex, 500, "Internal server error") } catch (_: Exception) { }
    } finally {
        ex.close()
    }
}

private fun sendError(ex: HttpExchange, status: Int, message: String) =
    send(ex, status, "text/plain; charset=utf-8", message.toByteArray(StandardCharsets.UTF_8))

private fun send(ex: HttpExchange, status: Int, contentType: String, bytes: ByteArray) {
    ex.responseHeaders.set("Content-Type", contentType)
    ex.responseHeaders.set("Cache-Control", "no-store")
    ex.sendResponseHeaders(status, bytes.size.toLong())
    ex.responseBody.use { it.write(bytes) }
}

internal fun decodeForm(body: String): Map<String, String> =
    if (body.isBlank()) emptyMap() else body.split('&').associate {
        val key = it.substringBefore('=')
        val value = it.substringAfter('=', "")
        URLDecoder.decode(key, "UTF-8") to URLDecoder.decode(value, "UTF-8")
    }

internal fun keyCode(raw: String): Int? {
    val key = raw.uppercase()
    if (key.length == 1 && key[0] in 'A'..'Z') return KeyEvent.VK_A + (key[0] - 'A')
    if (key.length == 1 && key[0] in '0'..'9') return KeyEvent.VK_0 + (key[0] - '0')
    return when (key) {
        "ENTER" -> KeyEvent.VK_ENTER; "ESC", "ESCAPE" -> KeyEvent.VK_ESCAPE
        "TAB" -> KeyEvent.VK_TAB; "BACKSPACE" -> KeyEvent.VK_BACK_SPACE
        "DELETE" -> KeyEvent.VK_DELETE; "SPACE" -> KeyEvent.VK_SPACE
        "UP" -> KeyEvent.VK_UP; "DOWN" -> KeyEvent.VK_DOWN
        "LEFT" -> KeyEvent.VK_LEFT; "RIGHT" -> KeyEvent.VK_RIGHT
        "HOME" -> KeyEvent.VK_HOME; "END" -> KeyEvent.VK_END
        "PAGEUP" -> KeyEvent.VK_PAGE_UP; "PAGEDOWN" -> KeyEvent.VK_PAGE_DOWN
        "-" -> KeyEvent.VK_MINUS; "=" -> KeyEvent.VK_EQUALS
        "[" -> KeyEvent.VK_OPEN_BRACKET; "]" -> KeyEvent.VK_CLOSE_BRACKET
        ";" -> KeyEvent.VK_SEMICOLON; "/" -> KeyEvent.VK_SLASH
        "," -> KeyEvent.VK_COMMA; "." -> KeyEvent.VK_PERIOD
        "\\" -> KeyEvent.VK_BACK_SLASH; "'" -> KeyEvent.VK_QUOTE
        "`" -> KeyEvent.VK_BACK_QUOTE
        else -> if (key.matches(Regex("F(?:[1-9]|1[0-2])"))) KeyEvent.VK_F1 + key.substring(1).toInt() - 1 else null
    }
}

private class DesktopController(private val robot: Robot, initialBounds: Rectangle,
    private val devices: Array<GraphicsDevice>, initialDisplay: Int) {
    private val lock = Any()
    @Volatile private var bounds = initialBounds
    @Volatile var selectedDisplay = initialDisplay
        private set
    private val motion = MouseMotion()
    private val motionWorker = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "pocketcoder-mouse").apply { isDaemon = true }
    }
    init {
        motionWorker.scheduleAtFixedRate({
            try { synchronized(lock) { applyMotion(motion.step()) } } catch (_: Exception) { }
        }, 0, 8, java.util.concurrent.TimeUnit.MILLISECONDS)
    }
    private fun applyMotion(delta: Pair<Int, Int>) {
        if (delta.first == 0 && delta.second == 0) return
        val p = MouseInfo.getPointerInfo().location
        robot.mouseMove((p.x + delta.first).coerceIn(bounds.x, bounds.x + bounds.width - 1),
            (p.y + delta.second).coerceIn(bounds.y, bounds.y + bounds.height - 1))
    }
    @Volatile var displayRevision = System.currentTimeMillis()
        private set
    fun inputReceipt(): ByteArray = synchronized(lock) {
        """{"ok":true,"display":$selectedDisplay,"displayRevision":$displayRevision}""".toByteArray()
    }
    fun currentBounds(): Rectangle = Rectangle(bounds)
    fun selectDisplay(index: Int) = synchronized(lock) {
        require(index in devices.indices)
        motion.clear()
        bounds = devices[index].defaultConfiguration.bounds
        selectedDisplay = index
        displayRevision++
    }
    // Screen capture has its own Robot so capture cannot block mouse/keyboard input.
    private val captureRobot = Robot()
    private fun bounded(form: Map<String, String>, field: String, min: Int, max: Int): Int =
        (form[field]?.toIntOrNull() ?: throw IllegalArgumentException("Missing/invalid $field"))
            .also { require(it in min..max) { "$field out of range" } }

    fun jpeg(): ByteArray {
        val bounds = currentBounds()
        // Compatibility capture also preserves native pixels for readable text.
        val image = captureRobot.createScreenCapture(bounds)
        val scale = 1.0
        val width = max(1, (image.width * scale).roundToInt())
        val height = max(1, (image.height * scale).roundToInt())
        val resized = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        resized.createGraphics().useGraphics { g ->
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.drawImage(image, 0, 0, width, height, null)
            // Robot captures do not include the OS cursor. Overlay its current position.
            val pointer = MouseInfo.getPointerInfo()?.location
            if (pointer != null && bounds.contains(pointer)) {
                val x = (pointer.x - bounds.x) * scale
                val y = (pointer.y - bounds.y) * scale
                val cursor = java.awt.geom.Path2D.Double().apply {
                    moveTo(x, y); lineTo(x, y + 18); lineTo(x + 5, y + 13)
                    lineTo(x + 10, y + 21); lineTo(x + 13, y + 19)
                    lineTo(x + 8, y + 11); lineTo(x + 15, y + 10); closePath()
                }
                g.color = Color.WHITE; g.fill(cursor)
                g.color = Color.BLACK; g.stroke = BasicStroke(1.5f); g.draw(cursor)
            }
        }
        val output = ByteArrayOutputStream(160_000)
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        try {
            val imageOut = ImageIO.createImageOutputStream(output)
            writer.output = imageOut
            val params = writer.defaultWriteParam
            if (params.canWriteCompressed()) {
                params.compressionMode = ImageWriteParam.MODE_EXPLICIT
                params.compressionQuality = 0.95f
            }
            writer.write(null, javax.imageio.IIOImage(resized, null, null), params)
            imageOut.close()
            return output.toByteArray()
        } finally { writer.dispose() }
    }

    fun perform(form: Map<String, String>) = synchronized(lock) {
        // Ignore late events from the previously selected monitor after a switch.
        form["screen"]?.let { if (it.toIntOrNull() != selectedDisplay) return@synchronized }
        if (form["type"] != "move") applyMotion(motion.step(flush = true))
        when (form["type"] ?: "") {
            "move" -> {
                val dx = bounded(form, "dx", -2500, 2500)
                val dy = bounded(form, "dy", -2500, 2500)
                motion.offer(dx, dy)
            }
            "tap" -> {
                val x = bounded(form, "x", 0, 10000)
                val y = bounded(form, "y", 0, 10000)
                robot.mouseMove(bounds.x + ((bounds.width - 1) * x / 10000.0).roundToInt(),
                    bounds.y + ((bounds.height - 1) * y / 10000.0).roundToInt())
                click(InputEvent.BUTTON1_DOWN_MASK)
            }
            "click", "double", "down", "up" -> {
                val button = when (form["button"] ?: "left") {
                    "left" -> InputEvent.BUTTON1_DOWN_MASK
                    "right" -> InputEvent.BUTTON3_DOWN_MASK
                    "middle" -> InputEvent.BUTTON2_DOWN_MASK
                    else -> throw IllegalArgumentException("Unknown button")
                }
                when (form["type"]) {
                    "click" -> click(button)
                    "double" -> { click(button); robot.delay(70); click(button) }
                    "down" -> robot.mousePress(button)
                    "up" -> robot.mouseRelease(button)
                }
            }
            "wheel" -> robot.mouseWheel(bounded(form, "amount", -15, 15))
            "key" -> {
                val code = keyCode(form["key"] ?: "") ?: throw IllegalArgumentException("Unknown key")
                val mods = listOf("ctrl" to KeyEvent.VK_CONTROL, "alt" to KeyEvent.VK_ALT,
                    "shift" to KeyEvent.VK_SHIFT, "meta" to KeyEvent.VK_META)
                    .filter { form[it.first] == "1" }.map { it.second }
                mods.forEach(robot::keyPress)
                try { robot.keyPress(code); robot.keyRelease(code) }
                finally { mods.asReversed().forEach(robot::keyRelease) }
            }
            "text" -> {
                val value = form["value"] ?: throw IllegalArgumentException("Missing value")
                require(value.isNotBlank() && value.length <= 1000) { "Text must contain 1..1000 characters" }
                pasteUnicode(value)
            }
            else -> throw IllegalArgumentException("Unknown type")
        }
    }

    private fun click(button: Int) { robot.mousePress(button); robot.mouseRelease(button) }

    private fun pasteUnicode(text: String) {
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        val original = try { clipboard.getContents(null) } catch (_: Exception) { null }
        clipboard.setContents(StringSelection(text), null)
        val modifier = if (System.getProperty("os.name").lowercase().contains("mac")) KeyEvent.VK_META else KeyEvent.VK_CONTROL
        try {
            robot.keyPress(modifier)
            try { robot.keyPress(KeyEvent.VK_V); robot.keyRelease(KeyEvent.VK_V) }
            finally { robot.keyRelease(modifier) }
            robot.delay(180)
        } finally {
            if (original != null) try { clipboard.setContents(original, null) } catch (_: Exception) { }
        }
    }
}

private inline fun Graphics2D.useGraphics(block: (Graphics2D) -> Unit) = try { block(this) } finally { dispose() }

private fun selfTest() {
    VideoEncoder.selfTest()
    tailscaleAuthSelfTest()
    check(decodeForm("type=text&value=%ED%95%9C%EA%B8%80+OK")["value"] == "한글 OK")
    check(keyCode("ESC") == KeyEvent.VK_ESCAPE)
    check(keyCode("F12") == KeyEvent.VK_F12)
    check(keyCode("z") == KeyEvent.VK_Z)
    check(keyCode("not-an-input") == null)
    check(newToken().length >= 40)
    check(isPrivateBind("127.0.0.1"))
    check(isPrivateBind("192.168.0.10"))
    check(isPrivateBind("10.0.0.2"))
    check(isPrivateBind("172.16.0.2"))
    check(!isPrivateBind("0.0.0.0"))
    check(!isPrivateBind("8.8.8.8"))
    check(!isPrivateBind("172.32.0.2"))
    check(!isPrivateBind("999.0.0.1"))
    check(isPrivateBind("100.64.0.0"))
    check(isPrivateBind("100.127.255.255"))
    check(isPrivateBind("100.82.90.67"))
    check(!isPrivateBind("100.63.255.255"))
    check(!isPrivateBind("100.128.0.0"))
    check(!isTailscaleAddress("100.82.90.67.attacker.example"))
    val korean = "한".repeat(1000)
    val encoded = "type=text&value=" + java.net.URLEncoder.encode(korean, "UTF-8")
    check(encoded.toByteArray(StandardCharsets.UTF_8).size <= 32768)
    check(decodeForm(encoded)["value"] == korean)
    protocolSelfTest(encoded, korean)
    println("PASS: HTTP auth/methods / Tailscale same-user identity and fail-closed checks / Unicode / keys / LAN and Tailscale bind / MJPEG transport")
}

/** Real loopback HTTP transport test, without screen capture or GUI input. */
private fun protocolSelfTest(encoded: String, expected: String) {
    val token = newToken()
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val firstInput = java.util.concurrent.CountDownLatch(1)
    val receivedInputs = java.util.Collections.synchronizedList(mutableListOf<String>())
    server.createContext("/input-stream") { ex ->
        guarded(ex, token, "POST") {
            ex.requestBody.buffered().use { input ->
                while (true) {
                    val line = readInputLine(input) ?: break
                    receivedInputs.add(line)
                    firstInput.countDown()
                }
            }
            send(ex, 200, "application/json", "{\"ok\":true}".toByteArray())
        }
    }
    server.createContext("/health") { ex ->
        guarded(ex, token, "GET") { send(ex, 200, "application/json", "{\"ok\":true}".toByteArray()) }
    }
    server.createContext("/input") { ex ->
        guarded(ex, token, "POST") {
            val form = decodeForm(ex.requestBody.use { it.readBytes() }.toString(StandardCharsets.UTF_8))
            check(form["type"] == "text" && form["value"] == expected)
            send(ex, 200, "application/json", "{\"ok\":true}".toByteArray())
        }
    }
    val jpeg = ByteArrayOutputStream().also {
        ImageIO.write(BufferedImage(16, 9, BufferedImage.TYPE_INT_RGB), "jpeg", it)
    }.toByteArray()
    server.createContext("/stream") { ex ->
        guarded(ex, token, "GET") {
            ex.responseHeaders.set("Content-Type", "multipart/x-mixed-replace; boundary=pocketcoder-frame")
            ex.sendResponseHeaders(200, 0)
            ex.responseBody.use { output -> repeat(3) { writeMjpegFrame(output, jpeg) } }
        }
    }
    server.start()
    try {
        fun request(path: String, method: String, auth: String?, body: String? = null): Int {
            val connection = URL("http://127.0.0.1:${server.address.port}$path").openConnection() as HttpURLConnection
            try {
                connection.requestMethod = method
                connection.connectTimeout = 2000
                connection.readTimeout = 2000
                if (auth != null) connection.setRequestProperty("Authorization", "Bearer $auth")
                if (body != null) {
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
                    connection.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
                }
                val status = connection.responseCode
                (if (status >= 400) connection.errorStream else connection.inputStream)?.use { it.readBytes() }
                return status
            } finally { connection.disconnect() }
        }
        check(request("/health", "GET", null) == 401)
        check(request("/health", "GET", "invalid-token") == 401)
        check(request("/health", "POST", token) == 405)
        check(request("/health", "GET", token) == 200)
        check(request("/input", "POST", token, encoded) == 200)
        val inputConnection = URL("http://127.0.0.1:${server.address.port}/input-stream").openConnection() as HttpURLConnection
        try {
            inputConnection.requestMethod = "POST"
            inputConnection.doOutput = true
            inputConnection.setChunkedStreamingMode(1024)
            inputConnection.setRequestProperty("Authorization", "Bearer $token")
            inputConnection.outputStream.use { output ->
                output.write((encoded + "\n").toByteArray(Charsets.UTF_8)); output.flush()
                check(firstInput.await(2, java.util.concurrent.TimeUnit.SECONDS)) { "Input was buffered until request end" }
                output.write("type=key&key=DELETE\n".toByteArray()); output.flush()
            }
            check(inputConnection.responseCode == 200)
            inputConnection.inputStream.use { it.readBytes() }
            check(receivedInputs == listOf(encoded, "type=key&key=DELETE"))
        } finally { inputConnection.disconnect() }
        check(request("/stream", "GET", null) == 401)
        val stream = URL("http://127.0.0.1:${server.address.port}/stream").openConnection() as HttpURLConnection
        try {
            stream.setRequestProperty("Authorization", "Bearer $token")
            stream.readTimeout = 2000
            check(stream.responseCode == 200)
            stream.inputStream.buffered().use { input ->
                val reader = MjpegReader(input)
                repeat(3) {
                    val bytes = reader.nextFrame() ?: error("Missing streamed frame")
                    check(bytes.contentEquals(jpeg))
                    val decoded = ImageIO.read(bytes.inputStream())
                    check(decoded.width == 16 && decoded.height == 9)
                }
                check(reader.nextFrame() == null)
            }
        } finally { stream.disconnect() }
    } finally { server.stop(0) }
}
