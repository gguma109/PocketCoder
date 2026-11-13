import com.pocketcoder.protocol.*
import java.awt.Rectangle
import java.io.Closeable
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

internal data class HubPacket(val bytes: ByteArray, val timeUs: Long)

/** One encoder per active profile. Viewers share immutable packets, never an unbounded queue. */
internal class VideoHub(private val encoder: VideoEncoder) : Closeable {
    internal data class Key(val bounds: Rectangle, val profile: VideoProfile)
    private val groups = mutableMapOf<Key, Group>()
    @Synchronized fun subscribe(area: Rectangle, profile: VideoProfile): Subscription {
        val key = Key(Rectangle(area), profile)
        val group = groups[key] ?: run {
            require(groups.size < 4) { "Too many active video profiles" }
            Group(key, encoder.forProfile(profile)).also { groups[key] = it; it.start() }
        }
        return group.add()
    }
    @Synchronized fun viewers(): Int = groups.values.sumOf { it.count() }
    @Synchronized fun reset() { groups.values.toList().forEach { it.stop() }; groups.clear() }
    override fun close() = reset()
    internal inner class Subscription(private val group: Group, val header: VideoHeader) : Closeable {
        private val queue = ArrayBlockingQueue<HubPacket>(120)
        private val bytes = AtomicInteger()
        @Volatile private var closed = false
        private var started = false
        @Synchronized fun offer(packet: HubPacket, keyframe: Boolean) {
            if (closed) return
            if (!started && !keyframe) return
            started = true
            if (bytes.get() + packet.bytes.size > 8_388_608 || !queue.offer(packet)) {
                closed = true; queue.clear(); bytes.set(0); return
            }
            bytes.addAndGet(packet.bytes.size)
        }
        fun next(): HubPacket? {
            while (!closed) {
                val packet = queue.poll(1, TimeUnit.SECONDS) ?: continue
                bytes.addAndGet(-packet.bytes.size); return packet
            }
            return null
        }
        fun end() { closed = true; queue.clear(); bytes.set(0) }
        override fun close() { end(); group.remove(this) }
    }
    internal inner class Group(private val key: Key, private val selected: VideoEncoder) {
        private val subscribers = mutableListOf<Subscription>()
        private val gop = mutableListOf<HubPacket>()
        private var gopBytes = 0
        private var process: Process? = null
        @Volatile private var stopped = false
        fun start() {
            process = selected.start(key.bounds)
            Thread({
                try {
                    val reader = AccessUnitReader(process!!.inputStream.buffered(65536))
                    while (!stopped) {
                        val bytes = reader.next() ?: break
                        val keyframe = nalUnits(bytes).any { (it[0].toInt() and 31) == 5 }
                        val packet = HubPacket(bytes, System.nanoTime() / 1000)
                        synchronized(this) {
                            if (keyframe) { gop.clear(); gopBytes = 0 }
                            if (keyframe || gop.isNotEmpty()) {
                                gop += packet; gopBytes += bytes.size
                                if (gop.size > 240 || gopBytes > 8_388_608) { gop.clear(); gopBytes = 0 }
                            }
                            subscribers.toList().forEach { it.offer(packet, keyframe) }
                        }
                    }
                } catch (_: Exception) { }
                finally { synchronized(this) { subscribers.forEach { it.end() }; gop.clear() }; process?.destroyForcibly(); synchronized(this@VideoHub) { groups.remove(key, this) } }
            }, "pocketcoder-video-hub").apply { isDaemon = true; start() }
        }
        @Synchronized fun add(): Subscription {
            val subscription = Subscription(this, selected.dimensions(key.bounds))
            gop.forEachIndexed { index, packet -> subscription.offer(packet, index == 0) }
            subscribers += subscription
            return subscription
        }
        @Synchronized fun count() = subscribers.size
        fun remove(subscription: Subscription) {
            synchronized(this@VideoHub) {
                synchronized(this) { subscribers.remove(subscription) }
                if (count() == 0) { stop(); groups.remove(key, this) }
            }
        }
        @Synchronized fun stop() { stopped = true; subscribers.forEach { it.end() }; process?.destroyForcibly() }
    }
}
