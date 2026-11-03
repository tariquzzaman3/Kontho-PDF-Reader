package org.kontho.pdfreader

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

data class UpdateCheckResult(
    val installedVersion: String,
    val latestVersion: String?,
    val releaseUrl: String?,
    val apkDownloadUrl: String?,
    val errorMessage: String? = null,
    val releaseTitle: String? = null,
    val releaseNotes: String? = null
) {
    val updateAvailable: Boolean
        get() = latestVersion?.let { GithubReleaseChecker.isNewer(it, installedVersion) } == true
}

/**
 * Checks GitHub's public latest-stable-release endpoint only. It never uploads
 * PDFs, OCR text, device identifiers, or other library contents.
 */
object GithubReleaseChecker {
    const val RELEASES_URL = "https://github.com/tariquzzaman3/Kontho-PDF-Reader/releases"
    private const val RELEASES_API =
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
                val connection = (URL(RELEASES_API).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 15_000
                    requestMethod = "GET"
                    instanceFollowRedirects = true
                    useCaches = false
                    setRequestProperty("Accept", "application/vnd.github+json")
                    setRequestProperty("User-Agent", "Kontho-PDF-Reader-Android")
                    setRequestProperty("Cache-Control", "no-cache")
                    setRequestProperty("Pragma", "no-cache")
                }

                try {
                    val code = connection.responseCode
                    if (code != HttpURLConnection.HTTP_OK) {
                        val details = connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use {
                            it.readText().take(240)
                        }.orEmpty()
                        throw IOException(
                            "GitHub returned HTTP $code" +
                                (connection.responseMessage?.let { " ($it)" } ?: "") + "." +
                                (if (details.isNotBlank()) " $details" else "")
                        )
                    }

                    val release = connection.inputStream.bufferedReader(Charsets.UTF_8).use {
                        JSONObject(it.readText())
                    }

                    if (release.optBoolean("draft", false) || release.optBoolean("prerelease", false)) {
                        throw IOException("GitHub did not return a published stable release.")
                    }

                    val assets = release.optJSONArray("assets")
                        ?: throw IOException("The latest GitHub release has no APK asset.")
                    val apkUrl = (0 until assets.length())
                        .mapNotNull { index -> assets.optJSONObject(index) }
                        .firstOrNull { asset ->
                            asset.optString("name").endsWith(".apk", ignoreCase = true) &&
                                isTrustedApkDownloadUrl(asset.optString("browser_download_url"))
                        }
                        ?.optString("browser_download_url")
                        ?.takeIf { it.isNotBlank() }
                        ?: throw IOException("The latest GitHub release does not contain a trusted APK.")

                    val latest = release.optString("tag_name")
                        .ifBlank { release.optString("name") }
                        .trim()
                    if (latest.isBlank() || Regex("\\d+").find(latest) == null) {
                        throw IOException("The latest GitHub release did not include a readable version tag.")
                    }

                    val releaseUrl = release.optString("html_url")
                        .takeIf { it.startsWith("https://github.com/tariquzzaman3/Kontho-PDF-Reader/releases/") }
                        ?: RELEASES_URL

                    UpdateCheckResult(
                        installed,
                        latest,
                        releaseUrl,
                        apkUrl,
                        releaseTitle = release.optString("name").trim().takeIf { it.isNotBlank() } ?: latest,
                        releaseNotes = release.optString("body").trim().takeIf { it.isNotBlank() }
                    )
                } finally {
                    connection.disconnect()
                }
            } catch (error: Exception) {
                UpdateCheckResult(
                    installed,
                    null,
                    null,
                    null,
                    error.localizedMessage ?: "Could not contact GitHub."
                )
            }

            Handler(Looper.getMainLooper()).post { callback(result) }
        }, "kontho-update-check").apply {
            isDaemon = true
            start()
        }
    }

    fun isTrustedApkDownloadUrl(value: String): Boolean = try {
        val url = URL(value)
        url.protocol == "https" &&
            url.host.equals("github.com", ignoreCase = true) &&
            (url.port == -1 || url.port == 443) &&
            url.userInfo == null &&
            url.query == null &&
            url.ref == null &&
            url.path.startsWith("/tariquzzaman3/Kontho-PDF-Reader/releases/download/") &&
            url.path.endsWith(".apk", ignoreCase = true)
    } catch (_: Exception) {
        false
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
