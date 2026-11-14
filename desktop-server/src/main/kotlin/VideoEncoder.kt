import com.pocketcoder.protocol.*
import java.awt.Rectangle
import java.io.*
import java.util.concurrent.TimeUnit

/** Fixed FFmpeg screen-encoder commands only. No shell and no remotely supplied arguments. */
internal class VideoEncoder(private val executable: String, val codec: String, requestedFps: Int = 60,
    private val outputs: Map<Rectangle, DxgiOutput> = emptyMap(), private val profile: VideoProfile = VideoProfile(requestedFps),
    private val gfxCapture: Boolean = false) {
    val fps = if (codec == "h264_nvenc" && outputs.isNotEmpty()) requestedFps else 30
    fun forFps(requested: Int) = VideoEncoder(executable, codec, minOf(fps, requested), outputs, profile.copy(fps = minOf(fps, requested)), gfxCapture)
    fun forProfile(requested: VideoProfile) = VideoEncoder(executable, codec, minOf(fps, requested.fps), outputs, requested.copy(fps = minOf(fps, requested.fps)), gfxCapture)
    val capture = if (outputs.isNotEmpty()) "dxgi" else "gdi"
    fun dimensions(area: Rectangle): VideoHeader {
        require(area.width in 2..8192 && area.height in 2..8192) { "Unsupported display dimensions" }
        val ratio = minOf(1.0, profile.maxEdge.toDouble() / maxOf(area.width, area.height))
        return VideoHeader(maxOf(2, (area.width * ratio).toInt() / 2 * 2), maxOf(2, (area.height * ratio).toInt() / 2 * 2), fps)
    }
    fun start(area: Rectangle): Process {
        return ProcessBuilder(command(area)).redirectError(ProcessBuilder.Redirect.INHERIT).start()
    }
    internal fun command(area: Rectangle): List<String> {
        val size = dimensions(area)
        val command = mutableListOf(executable, "-hide_banner", "-loglevel", "error", "-nostdin")
        val output = outputs[area]
        if (output != null && !profile.drawCursor && gfxCapture && output.monitorIndex >= 0) {
            // Cursorless viewer profile: Windows Graphics Capture with capture_cursor=0. Desktop Duplication keeps a
            // software-composited cursor in the image (measured on a rotated monitor even with draw_mouse=0), which the
            // PC viewer then showed next to its own native cursor. WGC returns the monitor already rotated and only
            // delivers changed frames, so fps= restores a constant rate for the encoder and players.
            val filters = mutableListOf("gfxcapture=monitor_idx=${output.monitorIndex}:capture_cursor=0:display_border=0:max_framerate=$fps", "fps=$fps")
            if (size.width != area.width || size.height != area.height)
                filters += listOf("hwdownload", "format=bgra", "scale=${size.width}:${size.height}:flags=lanczos", "format=yuv420p")
            filters += "setpts=N/($fps*TB)"
            command += listOf("-filter_complex", filters.joinToString(","))
        } else if (output != null) {
            val rotated = output.rotation == 2 || output.rotation == 4
            val width = if (rotated) size.height else size.width
            val height = if (rotated) size.width else size.height
            val filters = mutableListOf("ddagrab=output_idx=${output.index}:framerate=$fps:draw_mouse=${if (profile.drawCursor) 1 else 0}")
            val needsResize = size.width != area.width || size.height != area.height
            if (needsResize || output.rotation in 2..4) {
                // Some D3D11 drivers reject scale_d3d11 output textures. Native capture stays entirely on GPU;
                // only resize/rotation downloads BGRA and converts it, preserving NVENC encoding.
                filters += listOf("hwdownload", "format=bgra")
                if (needsResize) filters += "scale=$width:$height:flags=lanczos"
                filters += "format=yuv420p"
                when (output.rotation) { 2 -> filters += "transpose=clock"; 3 -> filters += listOf("hflip", "vflip"); 4 -> filters += "transpose=cclock" }
            }
            filters += "setpts=N/($fps*TB)"
            command += listOf("-filter_complex", filters.joinToString(","))
        } else {
            command += listOf("-f", "gdigrab", "-framerate", "$fps", "-draw_mouse", if (profile.drawCursor) "1" else "0", "-offset_x", "${area.x}", "-offset_y", "${area.y}",
                "-video_size", "${area.width}x${area.height}", "-i", "desktop", "-vf", "scale=${size.width}:${size.height}", "-pix_fmt", "yuv420p")
        }
        command += listOf("-an") + encodingOptions(codec, fps, profile)
        command += listOf("-fps_mode", "passthrough", "-bsf:v", "h264_metadata=aud=insert", "-flush_packets", "1", "-f", "h264", "pipe:1")
        return command
    }
    companion object {
        private fun encodingOptions(codec: String, fps: Int, profile: VideoProfile = VideoProfile(fps)): List<String> =
            listOf("-c:v", codec) + if (codec == "h264_nvenc") {
                listOf("-profile:v", "high", "-preset", "p4", "-tune", "ull", "-rc", "vbr", "-cq", "${profile.cq}", "-rc-lookahead", "0", "-delay", "0", "-b:v", "${profile.mbps * 650}k", "-maxrate", "${profile.mbps * 1000}k", "-bufsize", "${profile.mbps * 50}k", "-g", "$fps", "-bf", "0")
            } else if (codec == "libx264") {
                listOf("-preset", "veryfast", "-tune", "zerolatency", "-crf", "${profile.cq}", "-maxrate", "${profile.mbps * 1000}k", "-bufsize", "${profile.mbps * 50}k", "-g", "30", "-bf", "0")
            } else {
                listOf("-b:v", "${profile.mbps * 650}k", "-g", "$fps", "-bf", "0")
            }
        fun detect(requestedFps: Int = 60): VideoEncoder? {
            if (!System.getProperty("os.name").startsWith("Windows", true)) return null
            val roots = listOf(File("ffmpeg.exe"), File("bin/ffmpeg.exe")) +
                (System.getenv("PATH") ?: "").split(File.pathSeparator).map { File(it.trim('"'), "ffmpeg.exe") }
            val executable = roots.firstOrNull { it.isFile }?.absolutePath ?: return null
            for (codec in listOf("h264_nvenc", "libopenh264", "libx264", "h264_mf")) {
                val command = listOf(executable, "-hide_banner", "-loglevel", "error", "-f", "lavfi", "-i", "color=size=1280x720:rate=30", "-frames:v", "2") +
                    encodingOptions(codec, requestedFps) + listOf("-f", "null", "-")
                val probe = ProcessBuilder(command).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
                try {
                    if (probe.waitFor(6, TimeUnit.SECONDS) && probe.exitValue() == 0) {
                        val scale = ProcessBuilder(executable, "-hide_banner", "-h", "filter=ddagrab").redirectErrorStream(true).start()
                        val help = try {
                            java.util.concurrent.CompletableFuture.supplyAsync { scale.inputStream.use { it.readNBytes(16384).toString(Charsets.UTF_8) } }.get(3, TimeUnit.SECONDS)
                        } catch (_: Exception) { "" } finally { scale.destroyForcibly() }
                        val displays = if (codec == "h264_nvenc" && help.contains("Desktop Duplication")) dxgiDisplays() else emptyMap()
                        return VideoEncoder(executable, codec, requestedFps, displays, gfxCapture = displays.isNotEmpty() && gfxCaptureWorks(executable))
                    }
                }
                finally { probe.destroyForcibly() }
            }
            return null
        }
        /** One cursorless WGC frame from the first monitor; false on older Windows/FFmpeg or when capture is refused. */
        private fun gfxCaptureWorks(executable: String): Boolean {
            val probe = ProcessBuilder(executable, "-hide_banner", "-loglevel", "error", "-nostdin", "-filter_complex",
                "gfxcapture=monitor_idx=0:capture_cursor=0:display_border=0:max_framerate=30", "-frames:v", "1", "-f", "null", "-")
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
            return try { probe.waitFor(8, TimeUnit.SECONDS) && probe.exitValue() == 0 } catch (_: Exception) { false } finally { probe.destroyForcibly() }
        }
        fun selfTest() {
            val primary = Rectangle(0, 0, 1920, 1080)
            val portrait = Rectangle(1920, 0, 1080, 1920)
            val encoder = VideoEncoder("ffmpeg", "h264_nvenc", 60, mapOf(primary to DxgiOutput(0, 1), portrait to DxgiOutput(1, 4)))
            check(encoder.dimensions(primary) == VideoHeader(1920, 1080, 60))
            check(encoder.dimensions(portrait) == VideoHeader(1080, 1920, 60))
            val flat = encoder.command(primary).joinToString(" ")
            check(flat.contains("output_idx=0:framerate=60") && !flat.contains("hwdownload"))
            val rotated = encoder.command(portrait).joinToString(" ")
            check(rotated.contains("format=bgra") && rotated.contains("transpose=cclock"))
            check(flat.contains("draw_mouse=1"))
            check(encoder.forProfile(VideoProfile(drawCursor = false)).command(primary).joinToString(" ").contains("draw_mouse=0"))
            check(encoder.forProfile(VideoProfile(drawCursor = false)).command(portrait).joinToString(" ").contains("draw_mouse=0"))
            check(flat.contains("-cq 12") && flat.contains("-maxrate 40000k"))
            check(encoder.dimensions(Rectangle(0, 0, 3840, 2160)) == VideoHeader(3840, 2160, 60))
            check(VideoEncoder("ffmpeg", "libx264").fps == 30)
            val outputsWithMonitors = mapOf(primary to DxgiOutput(0, 1, 0), portrait to DxgiOutput(1, 4, 1))
            val wgc = VideoEncoder("ffmpeg", "h264_nvenc", 60, outputsWithMonitors, gfxCapture = true).forProfile(VideoProfile(drawCursor = false))
            val wgcPortrait = wgc.command(portrait).joinToString(" ")
            check(wgcPortrait.contains("gfxcapture=monitor_idx=1:capture_cursor=0") && wgcPortrait.contains("fps=60") && !wgcPortrait.contains("transpose") && !wgcPortrait.contains("ddagrab"))
            check(wgc.command(primary).joinToString(" ").contains("gfxcapture=monitor_idx=0:capture_cursor=0"))
            check(wgc.forProfile(VideoProfile(quality = "data", drawCursor = false)).command(primary).joinToString(" ").contains("scale=1280:720"))
            // Phones keep the DDA path with the cursor drawn in; unknown monitor index also falls back to DDA.
            check(VideoEncoder("ffmpeg", "h264_nvenc", 60, outputsWithMonitors, gfxCapture = true).command(portrait).joinToString(" ").contains("ddagrab=output_idx=1"))
            check(VideoEncoder("ffmpeg", "h264_nvenc", 60, mapOf(portrait to DxgiOutput(1, 4)), gfxCapture = true).forProfile(VideoProfile(drawCursor = false)).command(portrait).joinToString(" ").contains("draw_mouse=0"))
            check(VideoEncoder("ffmpeg", "libx264").forProfile(VideoProfile(drawCursor = false)).command(primary).joinToString(" ").contains("-draw_mouse 0"))
        }
    }
}
