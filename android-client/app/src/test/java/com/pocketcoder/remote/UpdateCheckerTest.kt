package com.pocketcoder.remote

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class UpdateCheckerTest {
    private val base = UpdateChecker.DOWNLOAD_PREFIX + "v0.7.2/"
    private val release = """{"draft":false,"prerelease":false,"assets":[
        {"name":"PocketCoder-0.7.2.apk","browser_download_url":"${base}PocketCoder-0.7.2.apk"},
        {"name":"pocketcoder-update.json","browser_download_url":"${base}pocketcoder-update.json"},
        {"name":"evil.apk","browser_download_url":"https://evil.example/evil.apk"}]}"""
    private val sha = "a".repeat(64)
    private fun manifest(name: String = "PocketCoder-0.7.2.apk", hash: String = sha, size: Long = 1000) =
        """{"version":"0.7.2","androidVersionCode":22,"serverVersion":"0.7.2","apk":{"name":"$name","sha256":"$hash","size":$size}}"""

    @Test fun releaseAssetsKeepOnlyThisRepositoryDownloads() {
        val assets = UpdateChecker.parseReleaseAssets(release)!!
        assertEquals(setOf("PocketCoder-0.7.2.apk", "pocketcoder-update.json"), assets.keys)
    }

    @Test fun draftsAndPrereleasesAreIgnored() {
        assertNull(UpdateChecker.parseReleaseAssets(release.replace("\"draft\":false", "\"draft\":true")))
        assertNull(UpdateChecker.parseReleaseAssets(release.replace("\"prerelease\":false", "\"prerelease\":true")))
    }

    @Test fun manifestResolvesApkUrlFromReleaseAssets() {
        val update = UpdateChecker.parseManifest(manifest(), UpdateChecker.parseReleaseAssets(release)!!)
        assertEquals(22, update.versionCode)
        assertEquals("${base}PocketCoder-0.7.2.apk", update.apkUrl)
        assertEquals(sha, update.sha256)
    }

    @Test fun manifestRejectsUnexpectedNamesHashesAndSizes() {
        val assets = UpdateChecker.parseReleaseAssets(release)!!
        listOf(manifest(name = "evil.apk"), manifest(name = "../x.apk"), manifest(hash = "zz"), manifest(size = 0), manifest(name = "PocketCoder-9.9.9.apk"))
            .forEach { assertTrue(it, runCatching { UpdateChecker.parseManifest(it, assets) }.isFailure) }
    }

    @Test fun hexMatchesSha256Format() {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest("abc".toByteArray())
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", UpdateChecker.hex(digest))
    }
}
