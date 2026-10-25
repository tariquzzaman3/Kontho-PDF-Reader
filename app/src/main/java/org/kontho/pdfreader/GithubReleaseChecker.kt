package org.kontho.pdfreader

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class UpdateCheckResult(
    val installedVersion: String,
    val latestVersion: String?,
    val releaseUrl: String?,
    val errorMessage: String? = null
) {
    val updateAvailable: Boolean
        get() = latestVersion?.let { GithubReleaseChecker.isNewer(it, installedVersion) } == true
}

/**
 * Checks GitHub's public latest-release endpoint only. It never uploads PDFs,
 * OCR text, device identifiers, or other library contents.
 */
object GithubReleaseChecker {
    const val RELEASES_URL = "https://github.com/tariquzzaman3/Kontho-PDF-Reader/releases"
    private const val LATEST_RELEASE_API =
        "https://api.github.com/repos/tariquzzaman3/Kontho-PDF-Reader/releases/latest"

    fun check(context: Context, callback: (UpdateCheckResult) -> Unit) {
        val appContext = context.applicationContext
        Thread({
            val installed = try {
                appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName ?: "0.0.0"
            } catch (_: Exception) {
                "0.0.0"
            }
            val result = try {
                val connection = (URL(LATEST_RELEASE_API).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8_000
                    readTimeout = 8_000
                    requestMethod = "GET"
                    setRequestProperty("Accept", "application/vnd.github+json")
                    setRequestProperty("User-Agent", "Kontho-PDF-Reader-Android")
                }
                try {
                    val code = connection.responseCode
                    if (code != HttpURLConnection.HTTP_OK) {
                        throw IOException("GitHub returned HTTP $code.")
                    }
                    val json = connection.inputStream.bufferedReader(Charsets.UTF_8).use {
                        JSONObject(it.readText())
                    }
                    val latest = json.optString("tag_name").ifBlank { json.optString("name") }.trim()
                    if (latest.isBlank()) throw IOException("The latest GitHub release did not include a version.")
                    val url = json.optString("html_url").takeIf { it.startsWith("https://") }
                        ?: RELEASES_URL
                    UpdateCheckResult(installed, latest, url)
                } finally {
                    connection.disconnect()
                }
            } catch (error: Exception) {
                UpdateCheckResult(installed, null, null, error.localizedMessage ?: "Could not contact GitHub.")
            }
            Handler(Looper.getMainLooper()).post { callback(result) }
        }, "kontho-update-check").apply {
            isDaemon = true
            start()
        }
    }

    fun isNewer(latest: String, installed: String): Boolean {
        fun components(version: String): List<Int> =
            Regex("\\d+").findAll(version).mapNotNull { it.value.toIntOrNull() }.toList()
        val latestParts = components(latest)
        val installedParts = components(installed)
        if (latestParts.isEmpty() || installedParts.isEmpty()) return false
        for (index in 0 until maxOf(latestParts.size, installedParts.size)) {
            val newer = latestParts.getOrElse(index) { 0 }
            val current = installedParts.getOrElse(index) { 0 }
            if (newer != current) return newer > current
        }
        return false
    }
}
