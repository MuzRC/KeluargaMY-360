package com.muzrc.keluargamy360.util

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class PrivateZoneLogEntry(
    val timestampText: String,
    val message: String,
    val rawLine: String
)

object PrivateZoneLogHelper {

    private const val INTERNAL_FILE_NAME = "log_zon_peribadi.txt"
    private const val MAX_LINES = 250
    private val lock = Any()

    private fun dateFormatter(): SimpleDateFormat =
        SimpleDateFormat("dd MMM yyyy, hh:mm:ss a", Locale("ms", "MY"))

    fun appendEvent(context: Context, placeName: String, isEnter: Boolean) {
        synchronized(lock) {
            try {
                val file = File(context.filesDir, INTERNAL_FILE_NAME)
                val ts = dateFormatter().format(Date())
                val status = if (isEnter) "MASUK" else "KELUAR"
                val detail = if (isEnter) {
                    "Tiba di zon peribadi: $placeName"
                } else {
                    "Keluar dari zon peribadi: $placeName"
                }
                val newLine = "[$ts] [$status] $detail"

                val existingLines = if (file.exists()) {
                    file.readLines().filter { it.isNotBlank() }.toMutableList()
                } else {
                    mutableListOf()
                }
                existingLines.add(newLine)

                val trimmed = if (existingLines.size > MAX_LINES) {
                    existingLines.takeLast(MAX_LINES)
                } else {
                    existingLines
                }
                file.writeText(trimmed.joinToString("\n") + "\n")
            } catch (_: Exception) {
            }
        }
    }

    fun readLogs(context: Context): List<PrivateZoneLogEntry> {
        synchronized(lock) {
            return try {
                val file = File(context.filesDir, INTERNAL_FILE_NAME)
                if (!file.exists()) return emptyList()

                file.readLines()
                    .filter { it.isNotBlank() }
                    .asReversed()
                    .map { line ->
                        val closeBracket = line.indexOf(']')
                        if (line.startsWith("[") && closeBracket > 1) {
                            val ts = line.substring(1, closeBracket)
                            val msg = line.substring(closeBracket + 1).trim()
                            PrivateZoneLogEntry(timestampText = ts, message = msg, rawLine = line)
                        } else {
                            PrivateZoneLogEntry(timestampText = "", message = line, rawLine = line)
                        }
                    }
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    fun clearLogs(context: Context) {
        synchronized(lock) {
            try {
                val file = File(context.filesDir, INTERNAL_FILE_NAME)
                if (file.exists()) {
                    file.delete()
                }
            } catch (_: Exception) {
            }
        }
    }

    fun exportToDownloads(context: Context): Result<String> {
        synchronized(lock) {
            return try {
                val file = File(context.filesDir, INTERNAL_FILE_NAME)
                if (!file.exists() || file.readText().isBlank()) {
                    return Result.failure(Exception("Tiada rekod log peribadi untuk dieksport."))
                }

                val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
                val exportName = "KeluargaMY360_Log_Zon_Peribadi_$stamp.txt"
                val content = buildString {
                    appendLine("=== REKOD ZON PERIBADI KELUARGAMY360 ===")
                    appendLine("Dieksport pada: ${dateFormatter().format(Date())}")
                    appendLine("Simpanan tempatan (tidak dikongsi ke pelayan)")
                    appendLine("=========================================")
                    appendLine()
                    append(file.readText())
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, exportName)
                        put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    }
                    val resolver = context.contentResolver
                    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                        ?: return Result.failure(Exception("Gagal mencipta fail dalam folder Downloads."))

                    resolver.openOutputStream(uri)?.use { out ->
                        out.write(content.toByteArray(Charsets.UTF_8))
                    }
                } else {
                    @Suppress("DEPRECATION")
                    val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    if (!downloadsDir.exists()) downloadsDir.mkdirs()
                    File(downloadsDir, exportName).writeText(content)
                }

                Result.success(exportName)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }
}
