package com.pocketcoder.remote

import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** An APK published on a GitHub Release, described by the release's pocketcoder-update.json. */
internal data class AvailableUpdate(val version: String, val versionCode: Int, val apkUrl: String, val sha256: String, val size: Long)

internal object UpdateChecker {
    private const val REPO = "gguma109/PocketCoder"
    const val DOWNLOAD_PREFIX = "https://github.com/$REPO/releases/download/"
    private const val MAX_APK_BYTES = 64L * 1024 * 1024

    fun check(currentCode: Int): AvailableUpdate? {
        val release = String(get("https://api.github.com/repos/$REPO/releases/latest", 1_048_576), Charsets.UTF_8)
        val assets = parseReleaseAssets(release) ?: return null
        val manifestUrl = assets["pocketcoder-update.json"] ?: return null
        val update = parseManifest(String(get(manifestUrl, 65_536), Charsets.UTF_8), assets)
        return if (update.versionCode > currentCode) update else null
    }

    /** Maps asset name to download URL, keeping only this repository's release downloads. */
    fun parseReleaseAssets(releaseJson: String): Map<String, String>? {
        val release = JSONObject(releaseJson)
        if (release.optBoolean("draft") || release.optBoolean("prerelease")) return null
        val list = release.optJSONArray("assets") ?: return null
        val result = linkedMapOf<String, String>()
        for (i in 0 until list.length()) {
            val asset = list.getJSONObject(i)
            val url = asset.optString("browser_download_url")
            if (url.startsWith(DOWNLOAD_PREFIX)) result[asset.getString("name")] = url
        }
        return result
    }

    /** The APK URL always comes from the release's own asset list, never from the manifest text. */
    fun parseManifest(manifestJson: String, assets: Map<String, String>): AvailableUpdate {
        val manifest = JSONObject(manifestJson)
        val apk = manifest.getJSONObject("apk")
        val name = apk.getString("name")
        val sha = apk.getString("sha256").lowercase()
        val size = apk.getLong("size")
        require(name.matches(Regex("PocketCoder-[0-9.]+\\.apk"))) { "Unexpected APK name" }
        require(sha.matches(Regex("[0-9a-f]{64}"))) { "Invalid SHA-256" }
        require(size in 1..MAX_APK_BYTES) { "Invalid APK size" }
        val url = assets[name] ?: throw IOException("APK asset missing from release")
        return AvailableUpdate(manifest.getString("version"), manifest.getInt("androidVersionCode"), url, sha, size)
    }

    /** Downloads to [target] and deletes it unless size and SHA-256 match the manifest. */
    fun download(update: AvailableUpdate, target: File, progress: (Int) -> Unit) {
        require(update.apkUrl.startsWith(DOWNLOAD_PREFIX))
        val digest = MessageDigest.getInstance("SHA-256")
        val connection = open(update.apkUrl, 30_000)
        try {
            var total = 0L; var lastPercent = -1
            connection.inputStream.use { input -> target.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer); if (n < 0) break
                    total += n; if (total > update.size) throw IOException("APK larger than expected")
                    digest.update(buffer, 0, n); output.write(buffer, 0, n)
                    val percent = (total * 100 / update.size).toInt()
                    if (percent != lastPercent) { lastPercent = percent; progress(percent) }
                }
            } }
            if (total != update.size) throw IOException("APK download incomplete")
            if (hex(digest.digest()) != update.sha256) throw IOException("APK checksum mismatch")
        } catch (e: Exception) { target.delete(); throw e } finally { connection.disconnect() }
    }

    fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    private fun open(url: String, timeout: Int): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = timeout; connection.readTimeout = timeout
        connection.setRequestProperty("User-Agent", "PocketCoder-update-check")
        if (connection.responseCode != 200) { connection.disconnect(); throw IOException("HTTP ${connection.responseCode}") }
        return connection
    }

    private fun get(url: String, limit: Int): ByteArray {
        val connection = open(url, 8000)
        return try {
            connection.inputStream.use { input ->
                val bytes = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                while (true) { val n = input.read(buffer); if (n < 0) break; if (bytes.size() + n > limit) throw IOException("Response too large"); bytes.write(buffer, 0, n) }
                bytes.toByteArray()
            }
        } finally { connection.disconnect() }
    }
}
