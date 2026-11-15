package com.muzrc.keluargamy360.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import com.google.firebase.auth.FirebaseAuth
import com.muzrc.keluargamy360.KeluargaApp
import com.muzrc.keluargamy360.MainActivity
import com.muzrc.keluargamy360.R
import com.muzrc.keluargamy360.data.local.SecureStorage
import com.muzrc.keluargamy360.data.repository.LocationRepository
import com.muzrc.keluargamy360.util.BatteryHelper
import com.muzrc.keluargamy360.util.CrashAlertBus
import com.muzrc.keluargamy360.util.PermissionHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

class LocationTrackingService : Service(), SensorEventListener {

    private enum class PowerProfile {
        ACTIVE_MOVING,   // Sedang bergerak: GPS Satelit 15s
        STATIONARY_IDLE, // Pegun > 2 minit: Jimat bateri 90s (tiada satelit)
        LOW_BATTERY      // Bateri < 20%: Jimat kritikal 180s
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var secureStorage: SecureStorage
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private lateinit var locationRepository: LocationRepository

    private var sensorManager: SensorManager? = null
    private var accelerometer: Sensor? = null
    private var isSensorActive = false

    private var lastUploadedLocation: Location? = null
    private var lastHistoryLocation: Location? = null
    private var lastUploadTimeMs: Long = 0L
    private var lastMovementTimeMs: Long = System.currentTimeMillis()
    private var lastSpeedKmh: Float = 0f
    private var currentProfile: PowerProfile = PowerProfile.ACTIVE_MOVING

    private var sentLowBatteryAlert = false
    private var lastSpeedAlertTimeMs: Long = 0L
    private var lastCrashTriggerTimeMs: Long = 0L

    override fun onCreate() {
        super.onCreate()
        secureStorage = SecureStorage(this)
        locationRepository = LocationRepository(secureStorage)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                val now = System.currentTimeMillis()
                val battery = BatteryHelper.getBatteryStatus(this@LocationTrackingService)

                val prevLoc = lastUploadedLocation
                val movedDistance = prevLoc?.distanceTo(loc) ?: Float.MAX_VALUE
                val elapsedSec = ((now - lastUploadTimeMs) / 1000f).coerceAtLeast(1f)

                // 1. Tapis GPS Drift: isyarat mesti tepat (accuracy <= 35m) & jarak lebih besar daripada ralat GPS
                val isAccurateFix = !loc.hasAccuracy() || loc.accuracy <= 35f
                val driftThreshold = if (loc.hasAccuracy()) {
                    max(MIN_DISPLACEMENT_METERS, loc.accuracy * 1.3f)
                } else {
                    MIN_DISPLACEMENT_METERS
                }

                val gpsSpeedKmh = if (loc.hasSpeed()) (loc.speed * 3.6f).coerceAtLeast(0f) else 0f
                val hasRealGpsSpeed = isAccurateFix && gpsSpeedKmh >= 5f
                val hasRealDisplacement = prevLoc != null && isAccurateFix && movedDistance >= driftThreshold

                val isMovingNow = prevLoc == null || hasRealGpsSpeed || hasRealDisplacement
                if (isMovingNow) {
                    lastMovementTimeMs = now
                }

                // 2. Kira kelajuan hanya jika pergerakan sahih (elak 9 km/j palsu dalam rumah)
                val calculatedSpeedKmh = if (prevLoc != null && isAccurateFix && movedDistance >= max(35f, driftThreshold)) {
                    (movedDistance / elapsedSec) * 3.6f
                } else {
                    0f
                }
                val speedKmh = when {
                    !isMovingNow -> 0f
                    hasRealGpsSpeed -> gpsSpeedKmh
                    else -> calculatedSpeedKmh
                }
                lastSpeedKmh = speedKmh

                val userName = FirebaseAuth.getInstance().currentUser?.displayName ?: "Ahli Keluarga"

                // 3. Sensor kemalangan hanya aktif bila memandu >= 25 km/j
                val shouldMonitorCrash = secureStorage.isCrashDetectionEnabled() && speedKmh >= 25f
                updateCrashSensorState(shouldMonitorCrash)

                // 4. Amaran Bateri Lemah (< 15%)
                if (secureStorage.isLowBatteryAlertEnabled() && battery.level < 15 && !battery.isCharging) {
                    if (!sentLowBatteryAlert) {
                        sentLowBatteryAlert = true
                        serviceScope.launch {
                            locationRepository.postFamilyAlert(
                                type = "BATTERY",
                                message = "Amaran Bateri: Bateri telefon $userName tinggal ${battery.level}%!"
                            )
                        }
                    }
                } else if (battery.level >= 20 || battery.isCharging) {
                    sentLowBatteryAlert = false
                }

                // 5. Amaran Had Laju
                val speedLimit = secureStorage.getSpeedLimitKmh()
                if (secureStorage.isSpeedAlertEnabled() && speedKmh > speedLimit) {
                    if (now - lastSpeedAlertTimeMs > 600_000L) {
                        lastSpeedAlertTimeMs = now
                        serviceScope.launch {
                            locationRepository.postFamilyAlert(
                                type = "SPEED",
                                message = "Amaran Had Laju: $userName memandu pada kelajuan ${speedKmh.roundToInt()} km/j (Had: ${speedLimit.roundToInt()} km/j)."
                            )
                        }
                    }
                }

                // 6. Tukar profil kuasa secara automatik (Tidur bila pegun > 2 minit, bangun bila bergerak)
                val stationaryDuration = now - lastMovementTimeMs
                val targetProfile = when {
                    battery.level < 20 && !battery.isCharging -> PowerProfile.LOW_BATTERY
                    stationaryDuration >= IDLE_THRESHOLD_MS -> PowerProfile.STATIONARY_IDLE
                    else -> PowerProfile.ACTIVE_MOVING
                }
                if (targetProfile != currentProfile) {
                    currentProfile = targetProfile
                    restartLocationUpdates()
                }

                // 7. Muat naik ke Firestore: segera bila pergerakan sahih, atau heartbeat 5 minit bila pegun
                val elapsedSinceLastUpload = now - lastUploadTimeMs
                val shouldUpload = prevLoc == null ||
                        (hasRealDisplacement || hasRealGpsSpeed) ||
                        elapsedSinceLastUpload >= HEARTBEAT_INTERVAL_MS

                if (!shouldUpload) return

                // Jika pegun (heartbeat), kekalkan koordinat asal supaya pin tidak bergetar di peta
                val uploadLat = if (isMovingNow) loc.latitude else (prevLoc?.latitude ?: loc.latitude)
                val uploadLng = if (isMovingNow) loc.longitude else (prevLoc?.longitude ?: loc.longitude)

                if (isMovingNow) {
                    lastUploadedLocation = loc
                }
                lastUploadTimeMs = now

                serviceScope.launch {
                    locationRepository.uploadEncryptedLocation(
                        latitude = uploadLat,
                        longitude = uploadLng,
                        speedKmh = speedKmh,
                        batteryLevel = battery.level,
                        isCharging = battery.isCharging
                    )

                    if (isMovingNow) {
                        val prevHist = lastHistoryLocation
                        if (prevHist == null || prevHist.distanceTo(loc) >= 80f) {
                            lastHistoryLocation = loc
                            locationRepository.appendHistoryPoint(uploadLat, uploadLng)
                        }
                    }
                }
            }
        }
    }

    private fun updateCrashSensorState(enable: Boolean) {
        if (enable && !isSensorActive) {
            accelerometer?.let {
                sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
                isSensorActive = true
            }
        } else if (!enable && isSensorActive) {
            sensorManager?.unregisterListener(this)
            isSensorActive = false
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type != Sensor.TYPE_ACCELEROMETER) return
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        val gForce = sqrt((x * x + y * y + z * z).toDouble()) / SensorManager.GRAVITY_EARTH

        if (gForce >= 4.5 && lastSpeedKmh >= 25f) {
            val now = System.currentTimeMillis()
            if (now - lastCrashTriggerTimeMs > 60_000L) {
                lastCrashTriggerTimeMs = now
                CrashAlertBus.triggerCrashCountdown()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        val battery = BatteryHelper.getBatteryStatus(this)
        currentProfile = if (battery.level < 20 && !battery.isCharging) {
            PowerProfile.LOW_BATTERY
        } else {
            PowerProfile.ACTIVE_MOVING
        }
        startLocationUpdates()
        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        if (!PermissionHelper.hasFineLocation(this)) return

        val (priority, intervalMs, minUpdateMs, minDistance) = when (currentProfile) {
            PowerProfile.ACTIVE_MOVING -> Quad(Priority.PRIORITY_HIGH_ACCURACY, 15_000L, 8_000L, MIN_DISPLACEMENT_METERS)
            PowerProfile.STATIONARY_IDLE -> Quad(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 90_000L, 45_000L, MIN_DISPLACEMENT_METERS)
            PowerProfile.LOW_BATTERY -> Quad(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 180_000L, 90_000L, 35f)
        }

        val request = LocationRequest.Builder(priority, intervalMs)
            .setMinUpdateIntervalMillis(minUpdateMs)
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
        updateCrashSensorState(false)
        fusedLocationClient.removeLocationUpdates(locationCallback)
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private data class Quad(
        val priority: Int,
        val intervalMs: Long,
        val minUpdateMs: Long,
        val minDistance: Float
    )

    companion object {
        private const val NOTIFICATION_ID = 3601
        private const val IDLE_THRESHOLD_MS = 120_000L          // Masuk mod tidur selepas 2 minit pegun
        private const val HEARTBEAT_INTERVAL_MS = 300_000L      // Hantar status pegun setiap 5 minit
        private const val MIN_DISPLACEMENT_METERS = 25f         // Abaikan getaran GPS bawah 25 meter

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
