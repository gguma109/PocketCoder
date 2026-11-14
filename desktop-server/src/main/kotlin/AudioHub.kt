import com.pocketcoder.protocol.*
import java.io.Closeable
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

internal class AudioHub : Closeable {
    private val executable = File("PocketCoder-Audio.exe")
    val available: Boolean get() = executable.isFile && System.getProperty("os.name").startsWith("Windows")
    private var process: Process? = null
    private var generation = 0
    private var format = CompletableFuture<AudioHeader>()
    private val subscriptions = mutableListOf<Subscription>()
    @Synchronized fun subscribe(): Subscription {
        require(available && subscriptions.size < 4) { "Playback capture unavailable or full" }
        if (process == null) start()
        return Subscription(format).also { subscriptions += it }
    }
    private fun start() {
        val current = ++generation
        format = CompletableFuture()
        val header = format
        val capture = ProcessBuilder(executable.absolutePath, "--parent", ProcessHandle.current().pid().toString()).redirectError(ProcessBuilder.Redirect.INHERIT).start()
        process = capture
        Thread({
            try {
                val reader = AudioReader(capture.inputStream.buffered(65536))
                header.complete(reader.header)
                while (capture.isAlive) {
                    val packet = reader.next()
                    synchronized(this) { if (current == generation) subscriptions.forEach { it.offer(packet) } }
                }
            } catch (e: Exception) { header.completeExceptionally(e) }
            finally {
                capture.destroyForcibly()
                synchronized(this) {
                    if (generation == current) { process = null; subscriptions.forEach { it.end() }; subscriptions.clear() }
                }
            }
        }, "pocketcoder-playback-capture").apply { isDaemon = true; start() }
    }
    internal inner class Subscription(private val format: CompletableFuture<AudioHeader>) : Closeable {
        private val packets = ArrayBlockingQueue<ByteArray>(8)
        @Volatile private var closed = false
        fun header(): AudioHeader = format.get(5, TimeUnit.SECONDS)
        fun offer(bytes: ByteArray) { if (!closed && !packets.offer(bytes)) { packets.poll(); packets.offer(bytes) } }
        fun next(): ByteArray? { while (!closed) { packets.poll(1, TimeUnit.SECONDS)?.let { return it } }; return null }
        fun end() { closed = true; packets.clear() }
        override fun close() {
            end()
            synchronized(this@AudioHub) {
                subscriptions.remove(this)
                if (subscriptions.isEmpty()) { generation++; process?.destroyForcibly(); process = null }
            }
        }
    }
    @Synchronized override fun close() { generation++; process?.destroyForcibly(); process = null; subscriptions.forEach { it.end() }; subscriptions.clear() }
}
