package com.muzrc.keluargamy360.util

import android.content.ContentValues
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

enum class DiagnosticCategory(val tag: String, val label: String) {
    SYNC("SYNC", "Sync & VPN"),
    SERVIS("SERVIS", "Servis & OEM"),
    GPS("GPS", "GPS & Bangun"),
    TRIP("TRIP", "Perjalanan")
}

data class DiagnosticLogEntry(
    val timestampText: String,
    val category: DiagnosticCategory,
    val title: String,
    val detail: String,
    val rawLine: String
)

data class DailyTripSummary(
    val dateKey: String,
    val distanceKm: Float,
    val maxSpeedKmh: Float,
    val movingSeconds: Long,
    val tripCount: Int
)

object AppDiagnosticLogHelper {

    private const val DIAG_FILE_NAME = "log_diagnostik_app.txt"
    private const val TRIP_FILE_NAME = "log_perjalanan_harian.txt"
    private const val PREFS_NAME = "keluargamy360_diagnostic_prefs"
    private const val MAX_LINES = 350
    private val lock = Any()

    // Penampan kadar (rate-limit) supaya log tidak berulang terlalu kerap
    private val lastLogByKey = mutableMapOf<String, Long>()
    private var discardedDriftCountSinceLastFlush = 0
    private var lastDriftMaxAccuracy = 0f

    private fun dateTimeFmt(): SimpleDateFormat =
        SimpleDateFormat("dd MMM yyyy, hh:mm:ss a", Locale("ms", "MY"))

    private fun dayKeyFmt(): SimpleDateFormat =
        SimpleDateFormat("yyyy-MM-dd", Locale.US)

    private fun displayDayFmt(): SimpleDateFormat =
        SimpleDateFormat("dd MMM yyyy", Locale("ms", "MY"))

    fun detectNetworkMode(context: Context): String {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return "Tiada Rangkaian"
            val network = cm.activeNetwork ?: return "Luar Talian (Tiada Internet)"
            val caps = cm.getNetworkCapabilities(network) ?: return "Luar Talian"
            val hasNet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            if (!hasNet) return "Tiada Akses Internet"
            val isVpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            val transport = when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Data Mudah Alih"
                else -> "Rangkaian"
            }
            if (isVpn) "$transport + VPN Aktif" else transport
        } catch (_: Exception) {
            "Tidak Diketahui"
        }
    }

    fun logEvent(
        context: Context,
        category: DiagnosticCategory,
        title: String,
        detail: String = "",
        throttleKey: String? = null,
        minIntervalMs: Long = 0L
    ) {
        synchronized(lock) {
            try {
                val now = System.currentTimeMillis()
                if (throttleKey != null && minIntervalMs > 0L) {
                    val last = lastLogByKey[throttleKey] ?: 0L
                    if (now - last < minIntervalMs) return
                    lastLogByKey[throttleKey] = now
                }

                val ts = dateTimeFmt().format(Date(now))
                val cleanTitle = title.replace("\n", " ").trim()
                val cleanDetail = detail.replace("\n", " ").trim()
                val line = if (cleanDetail.isNotEmpty()) {
                    "[$ts] [${category.tag}] $cleanTitle | $cleanDetail"
                } else {
                    "[$ts] [${category.tag}] $cleanTitle"
                }

                appendLineToFileLocked(context, DIAG_FILE_NAME, line)
            } catch (_: Exception) {
            }
        }
    }

    // 1. Log Penyelarasan & Barisan Luar Talian
    fun logSyncIssue(context: Context, operation: String, reason: String) {
        val netMode = detectNetworkMode(context)
        val queueCount = OfflineRouteQueueHelper.peekAll(context).size
        logEvent(
            context = context,
            category = DiagnosticCategory.SYNC,
            title = "Muat Naik Ditangguh ($operation)",
            detail = "Punca: $reason | Rangkaian: $netMode | Barisan luar talian: $queueCount titik",
            throttleKey = "sync_issue_$operation",
            minIntervalMs = 300_000L // Maksimum 1 kali setiap 5 minit untuk isu sama
        )
    }

    fun logBatchSyncSuccess(context: Context, uploadedCount: Int, remainingCount: Int) {
        if (uploadedCount <= 0) return
        val netMode = detectNetworkMode(context)
        logEvent(
            context = context,
            category = DiagnosticCategory.SYNC,
            title = "Penyelarasan Berkelompok Berjaya (+$uploadedCount titik)",
            detail = "Baki dalam barisan: $remainingCount titik | Rangkaian: $netMode"
        )
    }

    // 2. Log Kesihatan Servis & Sekatan Bateri OEM
    fun logServiceLifecycle(context: Context, eventTitle: String, extraDetail: String = "") {
        val batteryExempt = PermissionHelper.isIgnoringBatteryOptimizations(context)
        val bgLocGranted = PermissionHelper.hasBackgroundLocation(context)
        val oemStatus = if (batteryExempt) "Pengecualian Bateri: AKTIF" else "Pengecualian Bateri: DISEKAT SISTEM"
        val locStatus = if (bgLocGranted) "Lokasi Latar Belakang: SENTIASA" else "Lokasi Latar Belakang: TERHAD"
        val combinedDetail = listOfNotNull(
            extraDetail.takeIf { it.isNotBlank() },
            oemStatus,
            locStatus
        ).joinToString(" | ")

        logEvent(
            context = context,
            category = DiagnosticCategory.SERVIS,
            title = eventTitle,
            detail = combinedDetail
        )
    }

    // 3. Log Punca Bangun & Tapisan GPS Drift
    fun logWakeupTrigger(context: Context, source: String, profileBefore: String) {
        logEvent(
            context = context,
            category = DiagnosticCategory.GPS,
            title = "Bangun dari Mod Tidur ($source)",
            detail = "Profil sebelum: $profileBefore",
            throttleKey = "wakeup_$source",
            minIntervalMs = 60_000L
        )
    }

    fun recordDiscardedGpsDrift(accuracyMeters: Float) {
        synchronized(lock) {
            discardedDriftCountSinceLastFlush++
            lastDriftMaxAccuracy = max(lastDriftMaxAccuracy, accuracyMeters)
        }
    }

    fun flushGpsDriftSummary(context: Context) {
        synchronized(lock) {
            if (discardedDriftCountSinceLastFlush <= 0) return
            val count = discardedDriftCountSinceLastFlush
            val worstAcc = lastDriftMaxAccuracy.roundToInt()
            discardedDriftCountSinceLastFlush = 0
            lastDriftMaxAccuracy = 0f

            val ts = dateTimeFmt().format(Date())
            val line = "[$ts] [GPS] Tapisan GPS Drift: $count bacaan tidak tepat diabaikan | Ralat maksimum: ±${worstAcc}m (> 35m)"
            appendLineToFileLocked(context, DIAG_FILE_NAME, line)
        }
    }

    // 4. Log Ringkasan Perjalanan & Jarak Harian (Daily Trip & Mileage Log)
    fun recordMovingSegment(
        context: Context,
        distanceMeters: Float,
        speedKmh: Float,
        elapsedSeconds: Float,
        isNewTripStart: Boolean
    ) {
        if (distanceMeters <= 0f || distanceMeters > 5000f) return
        synchronized(lock) {
            try {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val todayKey = dayKeyFmt().format(Date())
                val storedDay = prefs.getString("trip_day_key", todayKey) ?: todayKey

                // Jika bertukar hari baharu, simpan ringkasan akhir hari semalam ke fail .txt
                if (storedDay != todayKey) {
                    val prevDistKm = prefs.getFloat("trip_dist_m", 0f) / 1000f
                    val prevMaxSpd = prefs.getFloat("trip_max_spd", 0f)
                    val prevSec = prefs.getLong("trip_moving_sec", 0L)
                    val prevTrips = prefs.getInt("trip_count", 0)
                    if (prevDistKm > 0.05f) {
                        writeTripArchiveLineLocked(context, storedDay, prevDistKm, prevMaxSpd, prevSec, prevTrips)
                    }
                    prefs.edit()
                        .putString("trip_day_key", todayKey)
                        .putFloat("trip_dist_m", 0f)
                        .putFloat("trip_max_spd", 0f)
                        .putLong("trip_moving_sec", 0L)
                        .putInt("trip_count", 0)
                        .apply()
                }

                val curDistM = prefs.getFloat("trip_dist_m", 0f) + distanceMeters
                val curMaxSpd = max(prefs.getFloat("trip_max_spd", 0f), speedKmh)
                val curSec = prefs.getLong("trip_moving_sec", 0L) + elapsedSeconds.toLong().coerceIn(1L, 120L)
                val curTrips = prefs.getInt("trip_count", 0) + if (isNewTripStart) 1 else 0

                prefs.edit()
                    .putString("trip_day_key", todayKey)
                    .putFloat("trip_dist_m", curDistM)
                    .putFloat("trip_max_spd", curMaxSpd)
                    .putLong("trip_moving_sec", curSec)
                    .putInt("trip_count", max(1, curTrips))
                    .apply()
            } catch (_: Exception) {
            }
        }
    }

    fun getTodayTripSummary(context: Context): DailyTripSummary {
        synchronized(lock) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val todayKey = dayKeyFmt().format(Date())
            val storedDay = prefs.getString("trip_day_key", todayKey) ?: todayKey
            return if (storedDay != todayKey) {
                DailyTripSummary(todayKey, 0f, 0f, 0L, 0)
            } else {
                DailyTripSummary(
                    dateKey = todayKey,
                    distanceKm = prefs.getFloat("trip_dist_m", 0f) / 1000f,
                    maxSpeedKmh = prefs.getFloat("trip_max_spd", 0f),
                    movingSeconds = prefs.getLong("trip_moving_sec", 0L),
                    tripCount = prefs.getInt("trip_count", 0)
                )
            }
        }
    }

    fun logTripCheckpointNow(context: Context) {
        val summary = getTodayTripSummary(context)
        val mins = (summary.movingSeconds / 60L)
        val distText = String.format(Locale.US, "%.2f km", summary.distanceKm)
        val maxSpdText = "${summary.maxSpeedKmh.roundToInt()} km/j"
        logEvent(
            context = context,
            category = DiagnosticCategory.TRIP,
            title = "Ringkasan Perjalanan Hari Ini: $distText",
            detail = "Perjalanan: ${summary.tripCount}x | Tempoh bergerak: ${mins} minit | Laju maks: $maxSpdText"
        )
    }

    private fun writeTripArchiveLineLocked(
        context: Context,
        dateKey: String,
        distKm: Float,
        maxSpd: Float,
        movingSec: Long,
        trips: Int
    ) {
        val mins = movingSec / 60L
        val summaryText = String.format(
            Locale.US,
            "[%s] [TRIP] Ringkasan Harian: %.2f km | Perjalanan: %dx | Tempoh bergerak: %d min | Laju maks: %d km/j",
            dateKey,
            distKm,
            trips,
            mins,
            maxSpd.roundToInt()
        )
        appendLineToFileLocked(context, TRIP_FILE_NAME, summaryText)
        appendLineToFileLocked(context, DIAG_FILE_NAME, summaryText)
    }

    private fun appendLineToFileLocked(context: Context, fileName: String, line: String) {
        val file = File(context.filesDir, fileName)
        val existing = if (file.exists()) {
            file.readLines().filter { it.isNotBlank() }.toMutableList()
        } else {
            mutableListOf()
        }
        existing.add(line)
        val trimmed = if (existing.size > MAX_LINES) existing.takeLast(MAX_LINES) else existing
        file.writeText(trimmed.joinToString("\n") + "\n")
    }

    fun readLogs(context: Context, categoryFilter: DiagnosticCategory? = null): List<DiagnosticLogEntry> {
        synchronized(lock) {
            return try {
                val file = File(context.filesDir, DIAG_FILE_NAME)
                if (!file.exists()) return emptyList()

                file.readLines()
                    .filter { it.isNotBlank() }
                    .asReversed()
                    .mapNotNull { line ->
                        val parsed = parseLine(line)
                        if (categoryFilter == null || parsed.category == categoryFilter) parsed else null
                    }
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    private fun parseLine(line: String): DiagnosticLogEntry {
        // Format: [dd MMM yyyy, hh:mm:ss a] [TAG] Title | Detail
        val firstClose = line.indexOf(']')
        val ts = if (line.startsWith("[") && firstClose > 1) {
            line.substring(1, firstClose)
        } else {
            ""
        }
        val afterTs = if (firstClose > 0 && firstClose + 1 < line.length) {
            line.substring(firstClose + 1).trim()
        } else {
            line
        }

        val secondClose = afterTs.indexOf(']')
        val tagStr = if (afterTs.startsWith("[") && secondClose > 1) {
            afterTs.substring(1, secondClose)
        } else {
            "SERVIS"
        }
        val category = DiagnosticCategory.entries.find { it.tag == tagStr } ?: DiagnosticCategory.SERVIS
        val body = if (secondClose > 0 && secondClose + 1 < afterTs.length) {
            afterTs.substring(secondClose + 1).trim()
        } else {
            afterTs
        }

        val parts = body.split("|", limit = 2)
        val title = parts[0].trim()
        val detail = parts.getOrNull(1)?.trim().orEmpty()

        return DiagnosticLogEntry(
            timestampText = ts,
            category = category,
            title = title,
            detail = detail,
            rawLine = line
        )
    }

    fun clearLogs(context: Context) {
        synchronized(lock) {
            try {
                File(context.filesDir, DIAG_FILE_NAME).delete()
                lastLogByKey.clear()
            } catch (_: Exception) {
            }
        }
    }

    fun exportToDownloads(context: Context): Result<String> {
        synchronized(lock) {
            return try {
                val file = File(context.filesDir, DIAG_FILE_NAME)
                if (!file.exists() || file.readText().isBlank()) {
                    return Result.failure(Exception("Tiada rekod log diagnostik untuk dieksport."))
                }

                val todaySummary = getTodayTripSummary(context)
                val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
                val exportName = "KeluargaMY360_Diagnostik_$stamp.txt"
                val content = buildString {
                    appendLine("=== LOG DIAGNOSTIK & PERJALANAN KELUARGAMY360 ===")
                    appendLine("Dieksport pada : ${dateTimeFmt().format(Date())}")
                    appendLine("Peranti        : ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE})")
                    appendLine(
                        String.format(
                            Locale.US,
                            "Perjalanan Hari Ini: %.2f km | %dx perjalanan | %d min bergerak | Maks %d km/j",
                            todaySummary.distanceKm,
                            todaySummary.tripCount,
                            todaySummary.movingSeconds / 60L,
                            todaySummary.maxSpeedKmh.roundToInt()
                        )
                    )
                    appendLine("====================================================")
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
