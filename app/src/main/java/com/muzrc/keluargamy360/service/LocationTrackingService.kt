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
import kotlin.math.roundToInt
import kotlin.math.sqrt

class LocationTrackingService : Service(), SensorEventListener {

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
    private var lastSpeedKmh: Float = 0f
    private var isLowBatteryMode: Boolean = false

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

                val gpsSpeedKmh = (loc.speed * 3.6f).coerceAtLeast(0f)
                val calculatedSpeedKmh = if (prevLoc != null && movedDistance >= 10f) {
                    (movedDistance / elapsedSec) * 3.6f
                } else {
                    0f
                }
                val speedKmh = if (gpsSpeedKmh > 1f) gpsSpeedKmh else calculatedSpeedKmh
                lastSpeedKmh = speedKmh

                val userName = FirebaseAuth.getInstance().currentUser?.displayName ?: "Ahli Keluarga"

                val shouldMonitorCrash = secureStorage.isCrashDetectionEnabled() && speedKmh >= 25f
                updateCrashSensorState(shouldMonitorCrash)

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

                val shouldUseLowBatteryMode = battery.level < 20 && !battery.isCharging
                if (shouldUseLowBatteryMode != isLowBatteryMode) {
                    isLowBatteryMode = shouldUseLowBatteryMode
                    restartLocationUpdates()
                }

                val elapsedSinceLastUpload = now - lastUploadTimeMs
                val minDisplacement = if (isLowBatteryMode) 30f else MIN_DISPLACEMENT_METERS

                val shouldUpload = prevLoc == null ||
                        movedDistance >= minDisplacement ||
                        elapsedSinceLastUpload >= HEARTBEAT_INTERVAL_MS

                if (!shouldUpload) return

                lastUploadedLocation = loc
                lastUploadTimeMs = now

                serviceScope.launch {
                    locationRepository.uploadEncryptedLocation(
                        latitude = loc.latitude,
                        longitude = loc.longitude,
                        speedKmh = speedKmh,
                        batteryLevel = battery.level,
                        isCharging = battery.isCharging
                    )

                    val prevHist = lastHistoryLocation
                    if (prevHist == null || prevHist.distanceTo(loc) >= 80f) {
                        lastHistoryLocation = loc
                        locationRepository.appendHistoryPoint(loc.latitude, loc.longitude)
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
        isLowBatteryMode = battery.level < 20 && !battery.isCharging
        startLocationUpdates()
        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        if (!PermissionHelper.hasFineLocation(this)) return

        val priority = if (isLowBatteryMode) {
            Priority.PRIORITY_BALANCED_POWER_ACCURACY
        } else {
            Priority.PRIORITY_HIGH_ACCURACY
        }
        val intervalMs = if (isLowBatteryMode) LOW_BATTERY_INTERVAL_MS else ACTIVE_INTERVAL_MS
        val minDistance = if (isLowBatteryMode) 30f else MIN_DISPLACEMENT_METERS

        val request = LocationRequest.Builder(priority, intervalMs)
            .setMinUpdateIntervalMillis(8_000L)
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

    companion object {
        private const val NOTIFICATION_ID = 3601
        private const val ACTIVE_INTERVAL_MS = 15_000L
        private const val LOW_BATTERY_INTERVAL_MS = 120_000L
        private const val HEARTBEAT_INTERVAL_MS = 300_000L
        private const val MIN_DISPLACEMENT_METERS = 10f

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
