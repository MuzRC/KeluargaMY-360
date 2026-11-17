package com.muzrc.keluargamy360.util

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

sealed class ApkDownloadState {
    data object Idle : ApkDownloadState()
    data class Downloading(val progressPercent: Int, val downloadedMb: Float, val totalMb: Float) : ApkDownloadState()
    data class ReadyToInstall(val apkFile: File) : ApkDownloadState()
    data class Failed(val reason: String) : ApkDownloadState()
}

object ApkUpdateInstaller {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _downloadState = MutableStateFlow<ApkDownloadState>(ApkDownloadState.Idle)
    val downloadState: StateFlow<ApkDownloadState> = _downloadState.asStateFlow()

    private var activeDownloadId: Long = -1L
    private var targetApkFile: File? = null
    private var progressJob: Job? = null
    private var completionReceiver: BroadcastReceiver? = null

    fun canInstallPackages(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    fun openInstallPermissionSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }

    fun resetState() {
        if (_downloadState.value !is ApkDownloadState.Downloading) {
            _downloadState.value = ApkDownloadState.Idle
        }
    }

    /**
     * Memuat turun 1 fail APK kemas kini menggunakan DownloadManager Android
     * dan terus membuka tetingkap pemasangan sebaik muat turun selesai.
     */
    fun startDownloadAndInstall(
        context: Context,
        downloadUrl: String,
        apkFileName: String
    ) {
        val appContext = context.applicationContext

        // Pastikan hanya 1 muat turun aktif pada satu masa
        if (_downloadState.value is ApkDownloadState.Downloading && activeDownloadId != -1L) {
            return
        }

        // Jika pautan bukan fail .apk terus (contohnya halaman web GitHub), buka pelayar
        if (!downloadUrl.contains(".apk", ignoreCase = true)) {
            try {
                val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                appContext.startActivity(webIntent)
            } catch (_: Exception) {
                Toast.makeText(appContext, "Gagal membuka pautan muat turun.", Toast.LENGTH_SHORT).show()
            }
            return
        }

        try {
            val safeFileName = apkFileName.ifBlank { "KeluargaMY360-update.apk" }
                .replace(Regex("[^a-zA-Z0-9._-]"), "_")

            val downloadDir = appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?: return
            if (!downloadDir.exists()) downloadDir.mkdirs()

            // Padam fail APK lama dalam folder kemas kini supaya tidak bertindan
            downloadDir.listFiles()?.forEach { oldFile ->
                if (oldFile.name.endsWith(".apk", ignoreCase = true)) {
                    oldFile.delete()
                }
            }

            val destinationFile = File(downloadDir, safeFileName)
            targetApkFile = destinationFile

            val dm = appContext.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val request = DownloadManager.Request(Uri.parse(downloadUrl)).apply {
                setTitle("Kemas Kini KeluargaMY360")
                setDescription("Memuat turun $safeFileName...")
                setMimeType("application/vnd.android.package-archive")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setAllowedOverMetered(true)
                setAllowedOverRoaming(true)
                setDestinationInExternalFilesDir(appContext, Environment.DIRECTORY_DOWNLOADS, safeFileName)
            }

            registerCompletionReceiver(appContext)
            activeDownloadId = dm.enqueue(request)
            _downloadState.value = ApkDownloadState.Downloading(0, 0f, 0f)
            startProgressPolling(appContext, dm, activeDownloadId, destinationFile)
        } catch (e: Exception) {
            activeDownloadId = -1L
            _downloadState.value = ApkDownloadState.Failed(
                "Gagal memulakan muat turun: ${e.message ?: "Ralat tidak diketahui"}"
            )
        }
    }

    private fun registerCompletionReceiver(appContext: Context) {
        if (completionReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
                val completedId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
                if (completedId != -1L && completedId == activeDownloadId) {
                    val file = targetApkFile
                    activeDownloadId = -1L
                    progressJob?.cancel()
                    if (file != null && file.exists() && file.length() > 0L) {
                        _downloadState.value = ApkDownloadState.ReadyToInstall(file)
                        launchInstaller(appContext, file)
                    } else {
                        _downloadState.value = ApkDownloadState.Failed("Fail APK yang dimuat turun tidak lengkap.")
                    }
                }
            }
        }
        completionReceiver = receiver
        ContextCompat.registerReceiver(
            appContext,
            receiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_EXPORTED
        )
    }

    private fun startProgressPolling(
        appContext: Context,
        dm: DownloadManager,
        downloadId: Long,
        destinationFile: File
    ) {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (isActive && activeDownloadId == downloadId) {
                var cursor: Cursor? = null
                try {
                    val query = DownloadManager.Query().setFilterById(downloadId)
                    cursor = dm.query(query)
                    if (cursor != null && cursor.moveToFirst()) {
                        val statusIdx = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                        val bytesDoneIdx = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                        val bytesTotalIdx = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)

                        val status = if (statusIdx >= 0) cursor.getInt(statusIdx) else -1
                        val done = if (bytesDoneIdx >= 0) cursor.getLong(bytesDoneIdx) else 0L
                        val total = if (bytesTotalIdx >= 0) cursor.getLong(bytesTotalIdx) else 0L

                        when (status) {
                            DownloadManager.STATUS_RUNNING,
                            DownloadManager.STATUS_PENDING,
                            DownloadManager.STATUS_PAUSED -> {
                                val pct = if (total > 0L) ((done * 100L) / total).toInt().coerceIn(0, 99) else 0
                                val doneMb = done / (1024f * 1024f)
                                val totalMb = if (total > 0L) total / (1024f * 1024f) else 0f
                                _downloadState.value = ApkDownloadState.Downloading(pct, doneMb, totalMb)
                            }
                            DownloadManager.STATUS_SUCCESSFUL -> {
                                activeDownloadId = -1L
                                if (destinationFile.exists() && destinationFile.length() > 0L) {
                                    _downloadState.value = ApkDownloadState.ReadyToInstall(destinationFile)
                                    launchInstaller(appContext, destinationFile)
                                } else {
                                    _downloadState.value = ApkDownloadState.Failed("Fail APK tidak dijumpai selepas muat turun.")
                                }
                                break
                            }
                            DownloadManager.STATUS_FAILED -> {
                                activeDownloadId = -1L
                                _downloadState.value = ApkDownloadState.Failed("Muat turun gagal. Sila semak sambungan internet anda.")
                                break
                            }
                        }
                    }
                } catch (_: Exception) {
                } finally {
                    cursor?.close()
                }
                delay(400L)
            }
        }
    }

    fun launchInstaller(context: Context, apkFile: File) {
        try {
            val apkUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(installIntent)
        } catch (e: Exception) {
            Toast.makeText(
                context,
                "Gagal membuka pemasang aplikasi: ${e.message ?: "Ralat"}",
                Toast.LENGTH_LONG
            ).show()
        }
    }
}
