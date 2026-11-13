import com.google.gson.JsonParser
import com.sun.net.httpserver.HttpExchange
import java.io.File
import java.net.InetSocketAddress
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

internal data class TailIdentity(val userId: String)

/** Identity is read from the local Tailscale daemon, never from client-supplied headers. */
internal fun parseTailIdentity(json: String, address: String): TailIdentity {
    val root = JsonParser.parseString(json).asJsonObject
    val profile = root.getAsJsonObject("UserProfile") ?: error("Missing Tailscale user")
    val node = root.getAsJsonObject("Node") ?: error("Missing Tailscale node")
    val user = profile.get("ID")?.asString ?: error("Missing Tailscale user ID")
    require(user.matches(Regex("[1-9][0-9]*")) && node.get("User")?.asString == user)
    require(node.get("MachineAuthorized")?.asBoolean == true) { "Tailscale device is not authorized" }
    val tags = node.get("Tags")
    require(tags == null || tags.isJsonNull || tags.asJsonArray.size() == 0) { "Tagged devices cannot use personal login" }
    require(node.getAsJsonArray("Addresses")?.any { it.asString == "$address/32" } == true) { "Tailscale address mismatch" }
    val expiry = node.get("KeyExpiry")?.takeUnless { it.isJsonNull }?.asString
    if (expiry != null && !expiry.startsWith("0001-")) {
        require(Instant.parse(expiry).isAfter(Instant.now())) { "Tailscale device login expired" }
    }
    return TailIdentity(user)
}

internal class TailscaleAuth(
    private val bind: String,
    private val owner: TailIdentity,
    private val lookup: (String) -> TailIdentity,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 }
) {
    private data class Cached(val allowed: Boolean, val until: Long)
    private val cache = ConcurrentHashMap<String, Cached>()

    fun allows(local: InetSocketAddress, remote: InetSocketAddress): Boolean {
        val ip = remote.address?.hostAddress ?: return false
        if (local.address?.hostAddress != bind || !isTailscaleAddress(ip)) return false
        val now = clock()
        cache[ip]?.takeIf { it.until > now }?.let { return it.allowed }
        // Limit the cache as well as the HTTP worker pool. Fail closed if the local daemon fails.
        if (cache.size > 128) cache.clear()
        val allowed = try { lookup(ip).userId == owner.userId } catch (_: Exception) { false }
        cache[ip] = Cached(allowed, now + if (allowed) 5000 else 1000)
        return allowed
    }

    fun allows(ex: HttpExchange): Boolean = allows(ex.localAddress, ex.remoteAddress)
}

internal fun tailscaleLookup(): (String) -> TailIdentity {
    val windows = System.getProperty("os.name").startsWith("Windows", true)
    val executable = if (windows) {
        File(System.getenv("ProgramFiles") ?: "C:\\Program Files", "Tailscale/tailscale.exe").absolutePath
    } else {
        listOf("/usr/local/bin/tailscale", "/usr/bin/tailscale", "/Applications/Tailscale.app/Contents/MacOS/Tailscale")
            .firstOrNull { File(it).canExecute() } ?: error("Install the Tailscale CLI")
    }
    require(File(executable).canExecute()) { "Install Tailscale on this PC before starting login mode" }
    return { ip ->
        require(isTailscaleAddress(ip))
        // Fixed local CLI command, numeric IP only; no shell or user-supplied commands.
        val process = ProcessBuilder(executable, "whois", "--json", ip)
            .redirectError(ProcessBuilder.Redirect.DISCARD).start()
        try {
            val bytes = java.util.concurrent.CompletableFuture.supplyAsync {
                process.inputStream.use { it.readNBytes(262145) }
            }
            val output = bytes.get(3, TimeUnit.SECONDS)
            require(output.size <= 262144 && process.waitFor(1, TimeUnit.SECONDS) && process.exitValue() == 0) {
                "Tailscale identity lookup failed"
            }
            parseTailIdentity(output.toString(Charsets.UTF_8), ip)
        } finally { process.destroyForcibly() }
    }
}

internal fun tailscaleAuthSelfTest() {
    fun identity(user: String = "42", tags: String = "[]", authorized: Boolean = true, ip: String = "100.64.0.2/32") =
        """{"UserProfile":{"ID":$user},"Node":{"User":$user,"MachineAuthorized":$authorized,"Tags":$tags,"Addresses":["$ip"]}}"""
    check(parseTailIdentity(identity(), "100.64.0.2").userId == "42")
    check(parseTailIdentity(identity().replace("\"Tags\":[]", "\"Tags\":null"), "100.64.0.2").userId == "42")
    for (bad in listOf(identity(tags = "[\"tag:server\"]"), identity(authorized = false), identity(ip = "100.64.0.3/32"), "{}")) {
        check(runCatching { parseTailIdentity(bad, "100.64.0.2") }.isFailure)
    }
    val local = InetSocketAddress("100.64.0.1", 8765)
    val peer = InetSocketAddress("100.64.0.2", 12345)
    var now = 0L
    var user = "42"
    val auth = TailscaleAuth("100.64.0.1", TailIdentity("42"), { TailIdentity(user) }, { now })
    check(auth.allows(local, peer))
    user = "99"; now = 5001
    check(!auth.allows(local, peer))
    check(!auth.allows(local, InetSocketAddress("192.168.0.2", 12345)))
    check(!auth.allows(InetSocketAddress("127.0.0.1", 8765), peer))
    check(!TailscaleAuth("100.64.0.1", TailIdentity("42"), { error("Daemon stopped") }).allows(local, peer))
    check(runCatching { parseTailIdentity(identity().replace("\"MachineAuthorized\":true", "\"KeyExpiry\":\"2000-01-01T00:00:00Z\",\"MachineAuthorized\":true"), "100.64.0.2") }.isFailure)
}
