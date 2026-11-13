package com.pocketcoder.remote

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

internal data class AvailableUpdate(val version: String, val url: String)
internal object UpdateChecker {
    fun check(currentCode: Int): AvailableUpdate? {
        fun get(url: String): String {
            val connection = URL(url).openConnection() as HttpURLConnection
            return try {
                connection.connectTimeout = 5000; connection.readTimeout = 5000
                connection.setRequestProperty("User-Agent", "PocketCoder-update-check")
                require(connection.responseCode == 200)
                connection.inputStream.use { input ->
                    val bytes = java.io.ByteArrayOutputStream(); val buffer = ByteArray(4096)
                    while (true) { val size = input.read(buffer); if (size < 0) break; require(bytes.size() + size <= 262144); bytes.write(buffer, 0, size) }
                    bytes.toString("UTF-8")
                }
            } finally { connection.disconnect() }
        }
        val runs = JSONObject(get("https://api.github.com/repos/gguma109/PocketCoder/actions/workflows/build-android-apk.yml/runs?branch=main&status=success&per_page=1")).getJSONArray("workflow_runs")
        if (runs.length() == 0) return null
        val run = runs.getJSONObject(0); val sha = run.getString("head_sha")
        require(sha.matches(Regex("[a-f0-9]{40}")))
        val manifest = JSONObject(get("https://raw.githubusercontent.com/gguma109/PocketCoder/$sha/updates.json"))
        return if (manifest.getInt("androidVersionCode") > currentCode) AvailableUpdate(manifest.getString("version"), run.getString("html_url")) else null
    }
}
