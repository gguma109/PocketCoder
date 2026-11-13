import com.pocketcoder.protocol.*
import java.awt.Rectangle
import java.io.*
import java.util.concurrent.TimeUnit

/** Fixed FFmpeg screen-encoder commands only. No shell and no remotely supplied arguments. */
internal class VideoEncoder(private val executable: String, val codec: String, requestedFps: Int = 60,
    private val outputs: Map<Rectangle, DxgiOutput> = emptyMap()) {
    val fps = if (codec == "h264_nvenc" && outputs.isNotEmpty()) requestedFps else 30
    val capture = if (outputs.isNotEmpty()) "dxgi" else "gdi"
    fun dimensions(area: Rectangle): VideoHeader {
        val ratio = minOf(1.0, 1280.0 / area.width, 720.0 / area.height)
        return VideoHeader(maxOf(2, (area.width * ratio).toInt() / 2 * 2), maxOf(2, (area.height * ratio).toInt() / 2 * 2), fps)
    }
    fun start(area: Rectangle): Process {
        return ProcessBuilder(command(area)).redirectError(ProcessBuilder.Redirect.INHERIT).start()
    }
    internal fun command(area: Rectangle): List<String> {
        val size = dimensions(area)
        val command = mutableListOf(executable, "-hide_banner", "-loglevel", "error", "-nostdin")
        val output = outputs[area]
        if (output != null) {
            val rotated = output.rotation == 2 || output.rotation == 4
            val width = if (rotated) size.height else size.width
            val height = if (rotated) size.width else size.height
            val rotationFilter = when (output.rotation) {
                2 -> ",hwdownload,format=nv12,format=yuv420p,transpose=clock"
                3 -> ",hwdownload,format=nv12,format=yuv420p,hflip,vflip"
                4 -> ",hwdownload,format=nv12,format=yuv420p,transpose=cclock"
                else -> ""
            }
            command += listOf("-filter_complex", "ddagrab=output_idx=${output.index}:framerate=$fps:draw_mouse=1," +
                "scale_d3d11=width=$width:height=$height:format=nv12$rotationFilter")
        } else {
            command += listOf("-f", "gdigrab", "-framerate", "$fps", "-draw_mouse", "1", "-offset_x", "${area.x}", "-offset_y", "${area.y}",
                "-video_size", "${area.width}x${area.height}", "-i", "desktop", "-vf", "scale=${size.width}:${size.height}", "-pix_fmt", "yuv420p")
        }
        command += listOf("-an") + encodingOptions(codec, fps)
        command += listOf("-fps_mode", "passthrough", "-bsf:v", "h264_metadata=aud=insert", "-flush_packets", "1", "-f", "h264", "pipe:1")
        return command
    }
    companion object {
        private fun encodingOptions(codec: String, fps: Int): List<String> =
            listOf("-c:v", codec) + if (codec == "h264_nvenc") {
                listOf("-preset", "p1", "-tune", "ull", "-rc", "cbr", "-b:v", "${fps * 2500 / 30}k", "-maxrate", "${fps * 2500 / 30}k", "-bufsize", "170k", "-g", "$fps", "-bf", "0")
            } else {
                listOf("-preset", "ultrafast", "-tune", "zerolatency", "-b:v", "2500k", "-maxrate", "2500k", "-bufsize", "170k", "-g", "30", "-bf", "0")
            }
        fun detect(requestedFps: Int = 60): VideoEncoder? {
            if (!System.getProperty("os.name").startsWith("Windows", true)) return null
            val roots = listOf(File("ffmpeg.exe"), File("bin/ffmpeg.exe")) +
                (System.getenv("PATH") ?: "").split(File.pathSeparator).map { File(it.trim('"'), "ffmpeg.exe") }
            val executable = roots.firstOrNull { it.isFile }?.absolutePath ?: return null
            for (codec in listOf("h264_nvenc", "libx264")) {
                val command = listOf(executable, "-hide_banner", "-loglevel", "error", "-f", "lavfi", "-i", "color=size=1280x720:rate=30", "-frames:v", "2") +
                    encodingOptions(codec, requestedFps) + listOf("-f", "null", "-")
                val probe = ProcessBuilder(command).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
                try {
                    if (probe.waitFor(6, TimeUnit.SECONDS) && probe.exitValue() == 0) {
                        val scale = ProcessBuilder(executable, "-hide_banner", "-h", "filter=scale_d3d11").redirectErrorStream(true).start()
                        val help = try {
                            java.util.concurrent.CompletableFuture.supplyAsync { scale.inputStream.use { it.readNBytes(16384).toString(Charsets.UTF_8) } }.get(3, TimeUnit.SECONDS)
                        } catch (_: Exception) { "" } finally { scale.destroyForcibly() }
                        val displays = if (codec == "h264_nvenc" && help.contains("Scale video using Direct3D11")) dxgiDisplays() else emptyMap()
                        return VideoEncoder(executable, codec, requestedFps, displays)
                    }
                }
                finally { probe.destroyForcibly() }
            }
            return null
        }
        fun selfTest() {
            val primary = Rectangle(0, 0, 1920, 1080)
            val portrait = Rectangle(1920, 0, 1080, 1920)
            val encoder = VideoEncoder("ffmpeg", "h264_nvenc", 60, mapOf(primary to DxgiOutput(0, 1), portrait to DxgiOutput(1, 4)))
            check(encoder.dimensions(primary) == VideoHeader(1280, 720, 60))
            check(encoder.dimensions(portrait) == VideoHeader(404, 720, 60))
            val flat = encoder.command(primary).joinToString(" ")
            check(flat.contains("output_idx=0:framerate=60") && !flat.contains("hwdownload"))
            val rotated = encoder.command(portrait).joinToString(" ")
            check(rotated.contains("width=720:height=404") && rotated.contains("transpose=cclock"))
            check(VideoEncoder("ffmpeg", "libx264").fps == 30)
        }
    }
}
