package com.muzrc.keluargamy360.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.location.Location
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import com.muzrc.keluargamy360.KeluargaApp
import com.muzrc.keluargamy360.MainActivity
import com.muzrc.keluargamy360.R
import com.muzrc.keluargamy360.data.local.SecureStorage
import com.muzrc.keluargamy360.data.repository.LocationRepository
import com.muzrc.keluargamy360.util.BatteryHelper
import com.muzrc.keluargamy360.util.PermissionHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class LocationTrackingService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private lateinit var locationRepository: LocationRepository

    private var lastUploadedLocation: Location? = null
    private var lastUploadTimeMs: Long = 0L
    private var isLowBatteryMode: Boolean = false

    override fun onCreate() {
        super.onCreate()
        val secureStorage = SecureStorage(this)
        locationRepository = LocationRepository(secureStorage)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                val now = System.currentTimeMillis()
                val battery = BatteryHelper.getBatteryStatus(this@LocationTrackingService)

                // 1. Tukar profil kuasa secara automatik jika bateri bawah 20% & tidak dicas
                val shouldUseLowBatteryMode = battery.level < 20 && !battery.isCharging
                if (shouldUseLowBatteryMode != isLowBatteryMode) {
                    isLowBatteryMode = shouldUseLowBatteryMode
                    restartLocationUpdates()
                }

                // 2. Tapis muat naik: hanya hantar jika bergerak >= 30m atau cukup 5 minit (heartbeat)
                val prevLoc = lastUploadedLocation
                val movedDistance = prevLoc?.distanceTo(loc) ?: Float.MAX_VALUE
                val elapsedSinceLastUpload = now - lastUploadTimeMs

                val shouldUpload = prevLoc == null ||
                        movedDistance >= MIN_DISPLACEMENT_METERS ||
                        elapsedSinceLastUpload >= HEARTBEAT_INTERVAL_MS

                if (!shouldUpload) return

                lastUploadedLocation = loc
                lastUploadTimeMs = now
                val speedKmh = (loc.speed * 3.6f).coerceAtLeast(0f)

                serviceScope.launch {
                    locationRepository.uploadEncryptedLocation(
                        latitude = loc.latitude,
                        longitude = loc.longitude,
                        speedKmh = speedKmh,
                        batteryLevel = battery.level,
                        isCharging = battery.isCharging
                    )
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        val battery = BatteryHelper.getBatteryStatus(this)
        isLowBatteryMode = battery.level < 20 && !battery.isCharging
        startLocationUpdates()
        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        if (!PermissionHelper.hasFineLocation(this)) return

        val intervalMs = if (isLowBatteryMode) LOW_BATTERY_INTERVAL_MS else NORMAL_INTERVAL_MS
        val minDistance = if (isLowBatteryMode) 50f else MIN_DISPLACEMENT_METERS

        val request = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, intervalMs)
            .setMinUpdateIntervalMillis(intervalMs / 2)
            .setMinUpdateDistanceMeters(minDistance)
            .build()

        fusedLocationClient.requestLocationUpdates(
            request,
            locationCallback,
            Looper.getMainLooper()
        )
    }

    private fun restartLocationUpdates() {
        fusedLocationClient.removeLocationUpdates(locationCallback)
        startLocationUpdates()
    }

    private fun buildNotification(): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, KeluargaApp.CHANNEL_TRACKING_ID)
            .setContentTitle(getString(R.string.notif_tracking_title))
            .setContentText(getString(R.string.notif_tracking_body))
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    override fun onDestroy() {
        fusedLocationClient.removeLocationUpdates(locationCallback)
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val NOTIFICATION_ID = 3601
        private const val NORMAL_INTERVAL_MS = 60_000L          // 1 minit semasa biasa
        private const val LOW_BATTERY_INTERVAL_MS = 180_000L    // 3 minit jika bateri < 20%
        private const val HEARTBEAT_INTERVAL_MS = 300_000L      // 5 minit jika duduk pegun
        private const val MIN_DISPLACEMENT_METERS = 30f

        fun start(context: Context) {
            val intent = Intent(context, LocationTrackingService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, LocationTrackingService::class.java)
            context.stopService(intent)
        }
    }
}
