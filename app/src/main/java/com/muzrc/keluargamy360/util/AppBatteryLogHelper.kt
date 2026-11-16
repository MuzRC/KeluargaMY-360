package com.muzrc.keluargamy360.util

import android.content.ContentValues
import android.content.Context
import android.net.TrafficStats
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class AppBatteryLogEntry(
    val timestampText: String,
    val summary: String,
    val detail: String,
    val rawLine: String
)

data class BatterySinceChargeSummary(
    val isCurrentlyCharging: Boolean,
    val startBatteryPercent: Int,
    val currentBatteryPercent: Int,
    val phoneBatteryUsedPercent: Int,
    val appUsedMah: Double,
    val appUsedPercentEstimate: Double?,
    val sinceTimestampMs: Long,
    val totalGpsFixes: Int,
    val totalUploads: Int
)

object AppBatteryLogHelper {

    private const val INTERNAL_FILE_NAME = "log_bateri_app.txt"
    private const val PREFS_NAME = "keluargamy360_battery_since_charge"
    private const val KEY_WAS_CHARGING = "was_charging"
    private const val KEY_SINCE_TIME_MS = "since_time_ms"
    private const val KEY_START_PCT = "start_battery_pct"
    private const val KEY_ACCUM_APP_MAH = "accum_app_mah"
    private const val KEY_ACCUM_GPS_FIXES = "accum_gps_fixes"
    private const val KEY_ACCUM_UPLOADS = "accum_uploads"

    private const val MAX_LINES = 300
    private val lock = Any()

    // Penjejak metrik khusus untuk UID & proses KeluargaMY360 sahaja
    private var lastSnapshotElapsedMs: Long = 0L
    private var lastCpuTimeMs: Long = 0L
    private var lastTxBytes: Long = 0L
    private var lastRxBytes: Long = 0L
    private var gpsFixCountSinceLastLog: Int = 0
    private var highAccuracyFixCountSinceLastLog: Int = 0
    private var firestoreWriteCountSinceLastLog: Int = 0

    private fun dateFormatter(): SimpleDateFormat =
        SimpleDateFormat("dd MMM yyyy, hh:mm:ss a", Locale("ms", "MY"))

    fun initBaseline() {
        synchronized(lock) {
            if (lastSnapshotElapsedMs == 0L) {
                val uid = Process.myUid()
                lastSnapshotElapsedMs = SystemClock.elapsedRealtime()
                lastCpuTimeMs = Process.getElapsedCpuTime()
                lastTxBytes = TrafficStats.getUidTxBytes(uid).coerceAtLeast(0L)
                lastRxBytes = TrafficStats.getUidRxBytes(uid).coerceAtLeast(0L)
            }
        }
    }

    fun recordGpsFix(isHighAccuracy: Boolean) {
        synchronized(lock) {
            if (lastSnapshotElapsedMs == 0L) initBaseline()
            gpsFixCountSinceLastLog++
            if (isHighAccuracy) {
                highAccuracyFixCountSinceLastLog++
            }
        }
    }

    fun recordFirestoreUpload() {
        synchronized(lock) {
            if (lastSnapshotElapsedMs == 0L) initBaseline()
            firestoreWriteCountSinceLastLog++
        }
    }

    private fun calculatePendingAppMah(): Double {
        if (lastSnapshotElapsedMs == 0L) return 0.0
        val nowCpuMs = Process.getElapsedCpuTime()
        val deltaCpuMs = (nowCpuMs - lastCpuTimeMs).coerceAtLeast(0L)
        val satFixes = highAccuracyFixCountSinceLastLog
        val netFixes = (gpsFixCountSinceLastLog - satFixes).coerceAtLeast(0)
        val uploads = firestoreWriteCountSinceLastLog

        val cpuMah = (deltaCpuMs / 1000.0) * 0.045
        val gpsMah = (satFixes * 0.055) + (netFixes * 0.012)
        val radioMah = uploads * 0.015
        return cpuMah + gpsMah + radioMah
    }

    private fun syncChargeSessionLocked(
        context: Context,
        phoneBatteryLevel: Int,
        isCharging: Boolean,
        deltaAppMah: Double,
        deltaGpsFixes: Int,
        deltaUploads: Int
    ) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val hasInit = prefs.contains(KEY_SINCE_TIME_MS)
        val wasCharging = prefs.getBoolean(KEY_WAS_CHARGING, isCharging)

        if (!hasInit) {
            prefs.edit()
                .putBoolean(KEY_WAS_CHARGING, isCharging)
                .putLong(KEY_SINCE_TIME_MS, now)
                .putInt(KEY_START_PCT, phoneBatteryLevel)
                .putFloat(KEY_ACCUM_APP_MAH, deltaAppMah.toFloat())
                .putInt(KEY_ACCUM_GPS_FIXES, deltaGpsFixes)
                .putInt(KEY_ACCUM_UPLOADS, deltaUploads)
                .apply()
            return
        }

        if (isCharging) {
            // Semasa sedang dicas: kemas kini paras tertinggi & set semula pembilang supaya tepat bila cabut cas
            val prevStart = prefs.getInt(KEY_START_PCT, phoneBatteryLevel)
            val newStart = if (!wasCharging) phoneBatteryLevel else maxOf(prevStart, phoneBatteryLevel)
            prefs.edit()
                .putBoolean(KEY_WAS_CHARGING, true)
                .putLong(KEY_SINCE_TIME_MS, now)
                .putInt(KEY_START_PCT, newStart)
                .putFloat(KEY_ACCUM_APP_MAH, 0f)
                .putInt(KEY_ACCUM_GPS_FIXES, 0)
                .putInt(KEY_ACCUM_UPLOADS, 0)
                .apply()
        } else if (wasCharging) {
            // Baru sahaja cabut pengecas: mula kiraan baharu dari paras bateri semasa
            prefs.edit()
                .putBoolean(KEY_WAS_CHARGING, false)
                .putLong(KEY_SINCE_TIME_MS, now)
                .putInt(KEY_START_PCT, phoneBatteryLevel)
                .putFloat(KEY_ACCUM_APP_MAH, deltaAppMah.toFloat())
                .putInt(KEY_ACCUM_GPS_FIXES, deltaGpsFixes)
                .putInt(KEY_ACCUM_UPLOADS, deltaUploads)
                .apply()
        } else {
            // Sedang menggunakan bateri: tambah penggunaan terkumpul app ini
            val prevStart = prefs.getInt(KEY_START_PCT, phoneBatteryLevel)
            val adjustedStart = maxOf(prevStart, phoneBatteryLevel)
            val prevMah = prefs.getFloat(KEY_ACCUM_APP_MAH, 0f).toDouble()
            val prevGps = prefs.getInt(KEY_ACCUM_GPS_FIXES, 0)
            val prevUploads = prefs.getInt(KEY_ACCUM_UPLOADS, 0)

            prefs.edit()
                .putBoolean(KEY_WAS_CHARGING, false)
                .putInt(KEY_START_PCT, adjustedStart)
                .putFloat(KEY_ACCUM_APP_MAH, (prevMah + deltaAppMah).toFloat())
                .putInt(KEY_ACCUM_GPS_FIXES, prevGps + deltaGpsFixes)
                .putInt(KEY_ACCUM_UPLOADS, prevUploads + deltaUploads)
                .apply()
        }
    }

    fun getUsageSinceLastCharge(context: Context): BatterySinceChargeSummary {
        synchronized(lock) {
            val status = BatteryHelper.getBatteryStatus(context)
            val pendingMah = if (status.isCharging) 0.0 else calculatePendingAppMah()
            val pendingGps = if (status.isCharging) 0 else gpsFixCountSinceLastLog
            val pendingUploads = if (status.isCharging) 0 else firestoreWriteCountSinceLastLog

            syncChargeSessionLocked(
                context = context,
                phoneBatteryLevel = status.level,
                isCharging = status.isCharging,
                deltaAppMah = 0.0,
                deltaGpsFixes = 0,
                deltaUploads = 0
            )

            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val startPct = prefs.getInt(KEY_START_PCT, status.level)
            val sinceMs = prefs.getLong(KEY_SINCE_TIME_MS, System.currentTimeMillis())
            val storedMah = prefs.getFloat(KEY_ACCUM_APP_MAH, 0f).toDouble()
            val storedGps = prefs.getInt(KEY_ACCUM_GPS_FIXES, 0)
            val storedUploads = prefs.getInt(KEY_ACCUM_UPLOADS, 0)

            val totalAppMah = if (status.isCharging) 0.0 else (storedMah + pendingMah)
            val phoneUsedPct = if (status.isCharging) 0 else (startPct - status.level).coerceAtLeast(0)

            // Anggar % kapasiti bateri telefon jika sistem mendedahkan charge counter
            val fullCapacityMah = estimateBatteryCapacityMah(context)
            val appUsedPct = if (fullCapacityMah != null && fullCapacityMah > 500.0) {
                (totalAppMah / fullCapacityMah) * 100.0
            } else {
                null
            }

            return BatterySinceChargeSummary(
                isCurrentlyCharging = status.isCharging,
                startBatteryPercent = startPct,
                currentBatteryPercent = status.level,
                phoneBatteryUsedPercent = phoneUsedPct,
                appUsedMah = totalAppMah,
                appUsedPercentEstimate = appUsedPct,
                sinceTimestampMs = sinceMs,
                totalGpsFixes = if (status.isCharging) 0 else (storedGps + pendingGps),
                totalUploads = if (status.isCharging) 0 else (storedUploads + pendingUploads)
            )
        }
    }

    private fun estimateBatteryCapacityMah(context: Context): Double? {
        return try {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager ?: return null
            val chargeCounterUah = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
            val capacityPct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            if (chargeCounterUah > 0 && capacityPct in 1..100) {
                val currentMah = chargeCounterUah / 1000.0
                (currentMah / (capacityPct / 100.0))
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Merekodkan statistik penggunaan tenaga KHUSUS proses KeluargaMY360 sahaja
     * (CPU proses app, data rangkaian UID app, bilangan GPS fix, & anggaran mAh app).
     */
    fun logAppUsageCheckpoint(
        context: Context,
        reason: String,
        profileName: String,
        phoneBatteryLevel: Int,
        isCharging: Boolean
    ) {
        synchronized(lock) {
            try {
                if (lastSnapshotElapsedMs == 0L) {
                    initBaseline()
                }

                val nowElapsed = SystemClock.elapsedRealtime()
                val durationMs = (nowElapsed - lastSnapshotElapsedMs).coerceAtLeast(0L)
                val durationMin = durationMs / 60_000.0

                val uid = Process.myUid()
                val nowCpuMs = Process.getElapsedCpuTime()
                val nowTx = TrafficStats.getUidTxBytes(uid).coerceAtLeast(0L)
                val nowRx = TrafficStats.getUidRxBytes(uid).coerceAtLeast(0L)

                val deltaCpuMs = (nowCpuMs - lastCpuTimeMs).coerceAtLeast(0L)
                val deltaTxBytes = (nowTx - lastTxBytes).coerceAtLeast(0L)
                val deltaRxBytes = (nowRx - lastRxBytes).coerceAtLeast(0L)
                val totalDataKb = (deltaTxBytes + deltaRxBytes) / 1024.0

                val gpsFixes = gpsFixCountSinceLastLog
                val satFixes = highAccuracyFixCountSinceLastLog
                val netFixes = (gpsFixes - satFixes).coerceAtLeast(0)
                val uploads = firestoreWriteCountSinceLastLog

                val cpuMah = (deltaCpuMs / 1000.0) * 0.045
                val gpsMah = (satFixes * 0.055) + (netFixes * 0.012)
                val radioMah = uploads * 0.015
                val estimatedAppMah = cpuMah + gpsMah + radioMah

                // Kemas kini jumlah terkumpul sejak kali terakhir bateri dicas
                syncChargeSessionLocked(
                    context = context,
                    phoneBatteryLevel = phoneBatteryLevel,
                    isCharging = isCharging,
                    deltaAppMah = estimatedAppMah,
                    deltaGpsFixes = gpsFixes,
                    deltaUploads = uploads
                )

                val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                val screenState = if (pm?.isInteractive == true) "Skrin Nyala" else "Latar Belakang"
                val chargeTag = if (isCharging) "Dicas $phoneBatteryLevel%" else "Bateri $phoneBatteryLevel%"

                val ts = dateFormatter().format(Date())
                val durationStr = when {
                    durationMs < 1000L -> "Mula"
                    durationMin < 1.0 -> "${(durationMs / 1000L)}s"
                    else -> String.format(Locale.US, "%.1f min", durationMin)
                }

                val summaryPart = String.format(
                    Locale.US,
                    "[%s] App guna ~%.2f mAh (%s | %s)",
                    reason,
                    estimatedAppMah,
                    durationStr,
                    screenState
                )
                val detailPart = String.format(
                    Locale.US,
                    "Profil: %s | CPU App: %.1fs | GPS: %d (Satelit:%d) | Data App: %.1f KB | Hantar: %dx | Tel: %s",
                    profileName,
                    deltaCpuMs / 1000.0,
                    gpsFixes,
                    satFixes,
                    totalDataKb,
                    uploads,
                    chargeTag
                )

                val fullLine = "[$ts] $summaryPart || $detailPart"

                val file = File(context.filesDir, INTERNAL_FILE_NAME)
                val existingLines = if (file.exists()) {
                    file.readLines().filter { it.isNotBlank() }.toMutableList()
                } else {
                    mutableListOf()
                }
                existingLines.add(fullLine)

                val trimmed = if (existingLines.size > MAX_LINES) {
                    existingLines.takeLast(MAX_LINES)
                } else {
                    existingLines
                }
                file.writeText(trimmed.joinToString("\n") + "\n")

                // Tetapkan semula penanda aras untuk selang seterusnya
                lastSnapshotElapsedMs = nowElapsed
                lastCpuTimeMs = nowCpuMs
                lastTxBytes = nowTx
                lastRxBytes = nowRx
                gpsFixCountSinceLastLog = 0
                highAccuracyFixCountSinceLastLog = 0
                firestoreWriteCountSinceLastLog = 0
            } catch (_: Exception) {
            }
        }
    }

    fun readLogs(context: Context): List<AppBatteryLogEntry> {
        synchronized(lock) {
            return try {
                val file = File(context.filesDir, INTERNAL_FILE_NAME)
                if (!file.exists()) return emptyList()

                file.readLines()
                    .filter { it.isNotBlank() }
                    .asReversed()
                    .map { line ->
                        val closeBracket = line.indexOf(']')
                        val ts = if (line.startsWith("[") && closeBracket > 1) {
                            line.substring(1, closeBracket)
                        } else {
                            ""
                        }
                        val rest = if (closeBracket > 1) {
                            line.substring(closeBracket + 1).trim()
                        } else {
                            line
                        }
                        val parts = rest.split("||", limit = 2)
                        AppBatteryLogEntry(
                            timestampText = ts,
                            summary = parts.getOrElse(0) { rest }.trim(),
                            detail = parts.getOrElse(1) { "" }.trim(),
                            rawLine = line
                        )
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
                    return Result.failure(Exception("Tiada rekod log bateri app untuk dieksport."))
                }

                val summary = getUsageSinceLastCharge(context)
                val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
                val exportName = "KeluargaMY360_Log_Bateri_App_$stamp.txt"
                val content = buildString {
                    appendLine("=== LOG PENGGUNAAN TENAGA KHUSUS APP KELUARGAMY360 ===")
                    appendLine("Dieksport pada: ${dateFormatter().format(Date())}")
                    appendLine(
                        String.format(
                            Locale.US,
                            "Jumlah App Guna Sejak Dicas: ~%.2f mAh (Bateri Telefon Turun: %d%% [%d%% -> %d%%])",
                            summary.appUsedMah,
                            summary.phoneBatteryUsedPercent,
                            summary.startBatteryPercent,
                            summary.currentBatteryPercent
                        )
                    )
                    appendLine("Nota: Metrik CPU, GPS & Data ditapis khas untuk proses aplikasi ini sahaja.")
                    appendLine("=======================================================")
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
