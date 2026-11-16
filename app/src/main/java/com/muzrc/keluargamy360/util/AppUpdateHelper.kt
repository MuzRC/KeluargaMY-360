package com.muzrc.keluargamy360.util

import com.muzrc.keluargamy360.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

sealed class AppUpdateResult {
    data class UpToDate(val currentVersion: String) : AppUpdateResult()
    data class UpdateAvailable(
        val currentVersion: String,
        val latestVersion: String,
        val releaseNotes: String,
        val apkFileName: String,
        val downloadUrl: String
    ) : AppUpdateResult()
    data class Error(val message: String) : AppUpdateResult()
}

object AppUpdateHelper {

    private const val LATEST_RELEASE_API =
        "https://api.github.com/repos/MuzRC/KeluargaMY-360/releases/latest"

    private data class ApkAsset(
        val name: String,
        val url: String,
        val updatedAt: String
    )

    suspend fun checkForUpdates(): AppUpdateResult = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            val url = URL(LATEST_RELEASE_API)
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "KeluargaMY360-Android")
            }

            val code = connection.responseCode
            if (code == 404) {
                return@withContext AppUpdateResult.Error(
                    "Tiada Release dijumpai. Pastikan repositori GitHub bertetapan Public dan mempunyai Release."
                )
            }
            if (code != 200) {
                return@withContext AppUpdateResult.Error(
                    "Pelayan GitHub memulangkan ralat HTTP $code."
                )
            }

            val responseText = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(responseText)

            val rawTag = json.optString("tag_name", "").trim()
            val cleanRemoteVersion = rawTag.removePrefix("v").removePrefix("V").trim()
            val currentVersion = BuildConfig.VERSION_NAME.removePrefix("v").removePrefix("V").trim()

            if (cleanRemoteVersion.isBlank()) {
                return@withContext AppUpdateResult.Error("Format versi pada GitHub Release tidak sah.")
            }

            val htmlUrl = json.optString("html_url", "https://github.com/MuzRC/KeluargaMY-360/releases/latest")
            val bodyNotes = json.optString("body", "").ifBlank {
                "Penambahbaikan prestasi, kestabilan GPS, dan keselamatan keluarga."
            }

            // Kumpul semua aset .apk dan pilih HANYA 1 fail APK rasmi yang paling baharu
            val apkCandidates = mutableListOf<ApkAsset>()
            val assets = json.optJSONArray("assets")
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    val assetName = asset.optString("name", "").trim()
                    val downloadUrl = asset.optString("browser_download_url", "").trim()
                    val updatedAt = asset.optString("updated_at", asset.optString("created_at", ""))
                    if (assetName.endsWith(".apk", ignoreCase = true) && downloadUrl.isNotBlank()) {
                        apkCandidates.add(ApkAsset(name = assetName, url = downloadUrl, updatedAt = updatedAt))
                    }
                }
            }

            val selectedApk = selectSingleLatestApk(apkCandidates)

            if (isNewerVersion(cleanRemoteVersion, currentVersion)) {
                AppUpdateResult.UpdateAvailable(
                    currentVersion = currentVersion,
                    latestVersion = cleanRemoteVersion,
                    releaseNotes = bodyNotes,
                    apkFileName = selectedApk?.name ?: "KeluargaMY360-v$cleanRemoteVersion.apk",
                    downloadUrl = selectedApk?.url ?: htmlUrl
                )
            } else {
                AppUpdateResult.UpToDate(currentVersion = currentVersion)
            }
        } catch (e: Exception) {
            AppUpdateResult.Error("Gagal menyemak kemas kini. Pastikan sambungan internet aktif.")
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Memilih HANYA SATU fail .apk terkini:
     * 1. Utamakan APK release rasmi (bukan debug / unsigned).
     * 2. Susun mengikut masa kemas kini paling baharu (updated_at descending) dan ambil 1 sahaja.
     */
    private fun selectSingleLatestApk(candidates: List<ApkAsset>): ApkAsset? {
        if (candidates.isEmpty()) return null
        val signedReleaseApks = candidates.filter {
            !it.name.contains("debug", ignoreCase = true) &&
                !it.name.contains("unsigned", ignoreCase = true)
        }
        val pool = signedReleaseApks.ifEmpty { candidates }
        return pool.sortedByDescending { it.updatedAt }.firstOrNull()
    }

    private fun isNewerVersion(remote: String, local: String): Boolean {
        val remoteParts = remote.substringBefore("-").split(".").map { it.toIntOrNull() ?: 0 }
        val localParts = local.substringBefore("-").split(".").map { it.toIntOrNull() ?: 0 }
        val maxLen = maxOf(remoteParts.size, localParts.size)

        for (i in 0 until maxLen) {
            val r = remoteParts.getOrElse(i) { 0 }
            val l = localParts.getOrElse(i) { 0 }
            if (r > l) return true
            if (r < l) return false
        }
        return false
    }
}
