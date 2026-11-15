package com.muzrc.keluargamy360.util

import com.muzrc.keluargamy360.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class AppUpdateInfo(
    val latestVersion: String,
    val versionCode: Int,
    val releaseNotes: String,
    val downloadUrl: String
)

object UpdateChecker {

    private const val VERSION_JSON_URL =
        "https://raw.githubusercontent.com/MuzRC/KeluargaMY-360/main/version.json"

    suspend fun checkForUpdate(): AppUpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val url = URL(VERSION_JSON_URL)
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 5000
                readTimeout = 5000
            }

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(body)
                val remoteCode = json.optInt("versionCode", 0)

                if (remoteCode > BuildConfig.VERSION_CODE) {
                    return@withContext AppUpdateInfo(
                        latestVersion = json.optString("latestVersion", ""),
                        versionCode = remoteCode,
                        releaseNotes = json.optString("releaseNotes", "Kemas kini baharu tersedia."),
                        downloadUrl = json.optString("downloadUrl", "")
                    )
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }
}
