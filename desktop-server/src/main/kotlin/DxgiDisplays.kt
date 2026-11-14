import com.google.gson.JsonParser
import java.awt.Rectangle
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** [monitorIndex] is the EnumDisplayMonitors position used by FFmpeg gfxcapture, or -1 when unknown. */
internal data class DxgiOutput(val index: Int, val rotation: Int, val monitorIndex: Int = -1)

/** Read-only DXGI enumeration: match actual desktop bounds, never guess DISPLAYn numbering. */
internal fun dxgiDisplays(): Map<Rectangle, DxgiOutput> = try {
    val script = VideoEncoder::class.java.getResourceAsStream("/dxgi-displays.ps1")!!.use { it.readBytes().toString(Charsets.UTF_8) }
    val powershell = File(System.getenv("SystemRoot") ?: "C:\\Windows", "System32/WindowsPowerShell/v1.0/powershell.exe")
    val encoded = java.util.Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE))
    val process = ProcessBuilder(powershell.absolutePath, "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded)
        .redirectError(ProcessBuilder.Redirect.DISCARD).start()
    try {
        val bytes = CompletableFuture.supplyAsync { process.inputStream.use { it.readNBytes(65537) } }.get(8, TimeUnit.SECONDS)
        require(bytes.size <= 65536 && process.waitFor(1, TimeUnit.SECONDS) && process.exitValue() == 0)
        val root = JsonParser.parseString(bytes.toString(Charsets.UTF_8).trim().removePrefix("\uFEFF"))
        val entries = if (root.isJsonArray) root.asJsonArray.toList() else listOf(root)
        entries.associate { element ->
            val o = element.asJsonObject
            val area = Rectangle(o["X"].asInt, o["Y"].asInt, o["Width"].asInt, o["Height"].asInt)
            val output = DxgiOutput(o["Index"].asInt, o["Rotation"].asInt, o["MonitorIndex"]?.asInt ?: -1)
            require(area.width > 0 && area.height > 0 && output.index in 0..31 && output.rotation in 1..4)
            area to output
        }
    } finally { process.destroyForcibly() }
} catch (_: Exception) { emptyMap() }
