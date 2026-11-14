import java.io.Closeable
import java.io.File
import java.awt.Rectangle
import com.google.gson.Gson
import com.google.gson.JsonObject

/** One read-only native helper shared by authenticated viewers; no cursor data in logs. */
internal class CursorHub : Closeable {
    private val executable = File("PocketCoder-Cursor.exe")
    val available get() = executable.isFile && System.getProperty("os.name").startsWith("Windows")
    private var process: Process? = null
    @Volatile private var generation = 0
    private var lastStart = 0L
    @Volatile private var snapshot: JsonObject? = null
    @Volatile private var updated = 0L
    @Synchronized fun state(bounds: Rectangle, display: Int, revision: Long, knownShape: String): JsonObject {
        if (process?.isAlive != true && available && System.nanoTime() - lastStart > 1_000_000_000L) start()
        val source = snapshot
        val result = JsonObject()
        result.addProperty("ready", source != null && System.nanoTime() - updated < 2_000_000_000L)
        result.addProperty("display", display); result.addProperty("revision", revision)
        if (source != null) {
            val x = source["x"].asInt; val y = source["y"].asInt
            result.addProperty("x", ((x.toLong() - bounds.x) * 10000 / maxOf(1, bounds.width - 1)).coerceIn(0, 10000))
            result.addProperty("y", ((y.toLong() - bounds.y) * 10000 / maxOf(1, bounds.height - 1)).coerceIn(0, 10000))
            result.addProperty("visible", source["visible"].asBoolean && bounds.contains(x,y))
            val shapeId = "$generation:${source["shapeId"].asString}"
            result.addProperty("shapeId", shapeId)
            if (shapeId != knownShape) result.add("shape", source["shape"])
        }
        return result
    }
    private fun start() {
        snapshot = null; val current = ++generation; lastStart = System.nanoTime()
        val capture = ProcessBuilder(executable.absolutePath, "--parent", ProcessHandle.current().pid().toString()).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        process = capture
        Thread({
            var previousShape: com.google.gson.JsonElement? = null
            try { capture.inputStream.bufferedReader().use { reader ->
                while (capture.isAlive) {
                    val line = reader.readLine() ?: break
                    require(line.length < 524288)
                    val state = Gson().fromJson(line, JsonObject::class.java)
                    if (state["shape"]?.isJsonNull == false) previousShape = state["shape"]
                    state.add("shape", previousShape)
                    if (current == generation) { snapshot = state; updated = System.nanoTime() }
                }
            }} catch (_: Exception) {} finally { capture.destroyForcibly() }
        }, "pocketcoder-cursor-capture").apply { isDaemon = true; start() }
    }
    @Synchronized override fun close() { generation++; process?.destroyForcibly(); process = null; snapshot = null }
}
