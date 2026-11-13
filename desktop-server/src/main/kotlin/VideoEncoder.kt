import com.pocketcoder.protocol.*
import java.awt.Rectangle
import java.io.*
import java.util.concurrent.TimeUnit

/** Fixed FFmpeg screen-encoder commands only. No shell and no remotely supplied arguments. */
internal class VideoEncoder(private val executable: String, val codec: String) {
    val fps = 30
    fun dimensions(area: Rectangle): VideoHeader {
        val ratio = minOf(1.0, 1280.0 / area.width, 720.0 / area.height)
        return VideoHeader(maxOf(2, (area.width * ratio).toInt() / 2 * 2), maxOf(2, (area.height * ratio).toInt() / 2 * 2), fps)
    }
    fun start(area: Rectangle): Process {
        val size = dimensions(area)
        val command = mutableListOf(executable, "-hide_banner", "-loglevel", "error", "-nostdin",
            "-f", "gdigrab", "-framerate", "$fps", "-draw_mouse", "1", "-offset_x", "${area.x}", "-offset_y", "${area.y}",
            "-video_size", "${area.width}x${area.height}", "-i", "desktop", "-an", "-vf", "scale=${size.width}:${size.height}")
        command += encodingOptions(codec)
        command += listOf("-pix_fmt", "yuv420p", "-bsf:v", "h264_metadata=aud=insert", "-flush_packets", "1", "-f", "h264", "pipe:1")
        return ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.INHERIT).start()
    }
    companion object {
        private fun encodingOptions(codec: String): List<String> =
            listOf("-c:v", codec) + if (codec == "h264_nvenc") {
                listOf("-preset", "p1", "-tune", "ull", "-rc", "cbr", "-b:v", "2500k", "-maxrate", "2500k", "-bufsize", "170k", "-g", "30", "-bf", "0")
            } else {
                listOf("-preset", "ultrafast", "-tune", "zerolatency", "-b:v", "2500k", "-maxrate", "2500k", "-bufsize", "170k", "-g", "30", "-bf", "0")
            }
        fun detect(): VideoEncoder? {
            if (!System.getProperty("os.name").startsWith("Windows", true)) return null
            val roots = listOf(File("ffmpeg.exe"), File("bin/ffmpeg.exe")) +
                (System.getenv("PATH") ?: "").split(File.pathSeparator).map { File(it.trim('"'), "ffmpeg.exe") }
            val executable = roots.firstOrNull { it.isFile }?.absolutePath ?: return null
            for (codec in listOf("h264_nvenc", "libx264")) {
                val command = listOf(executable, "-hide_banner", "-loglevel", "error", "-f", "lavfi", "-i", "color=size=128x72:rate=30", "-frames:v", "2") +
                    encodingOptions(codec) + listOf("-f", "null", "-")
                val probe = ProcessBuilder(command).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
                try { if (probe.waitFor(6, TimeUnit.SECONDS) && probe.exitValue() == 0) return VideoEncoder(executable, codec) }
                finally { probe.destroyForcibly() }
            }
            return null
        }
    }
}
