package com.muzrc.keluargamy360.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.IBinder
import android.os.Looper
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.android.gms.location.*
import com.google.firebase.auth.FirebaseAuth
import com.muzrc.keluargamy360.KeluargaApp
import com.muzrc.keluargamy360.MainActivity
import com.muzrc.keluargamy360.R
import com.muzrc.keluargamy360.data.local.SecureStorage
import com.muzrc.keluargamy360.data.repository.LocationRepository
import com.muzrc.keluargamy360.util.AppBatteryLogHelper
import com.muzrc.keluargamy360.util.BatteryHelper
import com.muzrc.keluargamy360.util.CrashAlertBus
import com.muzrc.keluargamy360.util.OfflineRouteQueueHelper
import com.muzrc.keluargamy360.util.PermissionHelper
import com.muzrc.keluargamy360.util.PrivateZoneLogHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

class LocationTrackingService : Service(), SensorEventListener {

    private enum class PowerProfile {
        MOVING_CHARGING,        // Bergerak + Dicas: Ultra-agresif GPS 5s
        MOVING_HIGH_BATTERY,    // Bergerak + Bateri >= 50%: Agresif GPS 8s
        MOVING_MEDIUM_BATTERY,  // Bergerak + Bateri 20%-49%: Seimbang GPS 15s
        MOVING_LOW_BATTERY,     // Bergerak + Bateri < 20%: Jimat bergerak 40s
        STATIONARY_NORMAL,      // Berhenti > 2 minit + Bateri >= 20%/Dicas: Tidur 2 minit
        STATIONARY_LOW_BATTERY  // Berhenti > 2 minit + Bateri < 20%: Tidur dalam 4 minit
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val uploadMutex = Mutex()
    private val profileLock = Any()
    private var monitorJob: Job? = null

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
    private var currentProfile: PowerProfile = PowerProfile.MOVING_HIGH_BATTERY

    private var lastBatteryLogTimeMs: Long = 0L
    private var lastLoggedBatteryLevel: Int = -1
    private var lastLoggedChargingState: Boolean? = null

    private var motionShakeCount = 0
    private var firstMotionShakeMs = 0L

    private var sentLowBatteryAlert = false
    private var lastSpeedAlertTimeMs: Long = 0L
    private var lastCrashTriggerTimeMs: Long = 0L

    override fun onCreate() {
        super.onCreate()
        secureStorage = SecureStorage(this)
        locationRepository = LocationRepository(secureStorage)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        AppBatteryLogHelper.initBaseline()

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                val now = System.currentTimeMillis()
                val battery = BatteryHelper.getBatteryStatus(this@LocationTrackingService)

                // Rekod aktiviti GPS khusus app kita mengikut ketepatan semasa
                val isHighAccProfile = currentProfile == PowerProfile.MOVING_CHARGING ||
                        currentProfile == PowerProfile.MOVING_HIGH_BATTERY ||
                        currentProfile == PowerProfile.MOVING_MEDIUM_BATTERY
                AppBatteryLogHelper.recordGpsFix(isHighAccuracy = isHighAccProfile)

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

                // 1b. Sandaran pengesan Zon Peribadi menggunakan koordinat GPS (tulis ke .txt tempatan)
                if (isAccurateFix) {
                    evaluatePrivateZonesByGps(loc, now)
                }

                val gpsSpeedKmh = if (loc.hasSpeed()) (loc.speed * 3.6f).coerceAtLeast(0f) else 0f
                val hasRealGpsSpeed = isAccurateFix && gpsSpeedKmh >= 5f
                val hasRealDisplacement = prevLoc != null && isAccurateFix && movedDistance >= driftThreshold

                val isMovingNow = prevLoc == null || hasRealGpsSpeed || hasRealDisplacement
                if (isMovingNow) {
                    lastMovementTimeMs = now
                }

                // 2. Kira kelajuan hanya jika pergerakan sahih
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

                // 3. Amaran Bateri Lemah (< 15%)
                if (secureStorage.isLowBatteryAlertEnabled() && battery.level < 15 && !battery.isCharging) {
                    if (!sentLowBatteryAlert) {
                        sentLowBatteryAlert = true
                        serviceScope.launch {
                            withTimeoutOrNull(4000L) {
                                locationRepository.postFamilyAlert(
                                    type = "BATTERY",
                                    message = "Amaran Bateri: Bateri telefon $userName tinggal ${battery.level}%!"
                                )
                            }
                        }
                    }
                } else if (battery.level >= 20 || battery.isCharging) {
                    sentLowBatteryAlert = false
                }

                // 4. Amaran Had Laju
                val speedLimit = secureStorage.getSpeedLimitKmh()
                if (secureStorage.isSpeedAlertEnabled() && speedKmh > speedLimit) {
                    if (now - lastSpeedAlertTimeMs > 600_000L) {
                        lastSpeedAlertTimeMs = now
                        serviceScope.launch {
                            withTimeoutOrNull(4000L) {
                                locationRepository.postFamilyAlert(
                                    type = "SPEED",
                                    message = "Amaran Had Laju: $userName memandu pada kelajuan ${speedKmh.roundToInt()} km/j (Had: ${speedLimit.roundToInt()} km/j)."
                                )
                            }
                        }
                    }
                }

                // 5. Kemas kini profil kuasa adaptif & log bateri app
                evaluateProfileAndBattery(now, battery.level, battery.isCharging)

                // 6. Muat naik ke Firestore atau simpan ke barisan luar talian (Offline Route Queue)
                val heartbeatMs = if (currentProfile == PowerProfile.STATIONARY_LOW_BATTERY) {
                    HEARTBEAT_LOW_BATTERY_MS
                } else {
                    HEARTBEAT_NORMAL_MS
                }
                val elapsedSinceLastUpload = now - lastUploadTimeMs
                val shouldUpload = prevLoc == null ||
                        (hasRealDisplacement || hasRealGpsSpeed) ||
                        elapsedSinceLastUpload >= heartbeatMs

                if (!shouldUpload) return
                if (!uploadMutex.tryLock()) return

                val uploadLat = if (isMovingNow) loc.latitude else (prevLoc?.latitude ?: loc.latitude)
                val uploadLng = if (isMovingNow) loc.longitude else (prevLoc?.longitude ?: loc.longitude)

                if (isMovingNow) {
                    lastUploadedLocation = loc
                }
                lastUploadTimeMs = now

                serviceScope.launch {
                    try {
                        val isOnline = OfflineRouteQueueHelper.isNetworkAvailable(this@LocationTrackingService)
                        if (isOnline) {
                            val liveSent = locationRepository.uploadEncryptedLocation(
                                latitude = uploadLat,
                                longitude = uploadLng,
                                speedKmh = speedKmh,
                                batteryLevel = battery.level,
                                isCharging = battery.isCharging
                            )
                            if (liveSent) {
                                AppBatteryLogHelper.recordFirestoreUpload()
                                // Hantar semua titik laluan luar talian yang tertunggak sebaik internet pulih
                                val flushedCount = locationRepository.flushOfflineRouteQueue(this@LocationTrackingService)
                                if (flushedCount > 0) {
                                    AppBatteryLogHelper.recordFirestoreUpload()
                                }
                            }
                        }

                        if (isMovingNow) {
                            val prevHist = lastHistoryLocation
                            val histStepMeters = when (currentProfile) {
                                PowerProfile.MOVING_CHARGING -> 50f
                                PowerProfile.MOVING_HIGH_BATTERY -> 65f
                                else -> 80f
                            }
                            if (prevHist == null || prevHist.distanceTo(loc) >= histStepMeters) {
                                lastHistoryLocation = loc
                                val historySent = locationRepository.appendHistoryPoint(
                                    latitude = uploadLat,
                                    longitude = uploadLng,
                                    context = this@LocationTrackingService,
                                    recordedAtMs = now
                                )
                                if (historySent) {
                                    AppBatteryLogHelper.recordFirestoreUpload()
                                }
                            }
                        }
                    } finally {
                        uploadMutex.unlock()
                    }
                }
            }
        }

        startAppBatteryMonitorLoop()
    }

    /**
     * Pemasa latar belakang bebas (setiap 60 saat) supaya log bateri app, pertukaran mod tidur,
     * dan penghantaran semula laluan luar talian tetap berjalan walaupun telefon pegun.
     */
    private fun startAppBatteryMonitorLoop() {
        monitorJob?.cancel()
        monitorJob = serviceScope.launch {
            while (isActive) {
                delay(60_000L)
                val now = System.currentTimeMillis()
                val battery = BatteryHelper.getBatteryStatus(this@LocationTrackingService)
                withContext(Dispatchers.Main) {
                    evaluateProfileAndBattery(now, battery.level, battery.isCharging)
                }

                // Jika ada rekod laluan luar talian tertunggak dan sambungan internet sudah pulih
                if (OfflineRouteQueueHelper.isNetworkAvailable(this@LocationTrackingService)) {
                    if (uploadMutex.tryLock()) {
                        try {
                            val flushed = locationRepository.flushOfflineRouteQueue(this@LocationTrackingService)
                            if (flushed > 0) {
                                AppBatteryLogHelper.recordFirestoreUpload()
                            }
                        } finally {
                            uploadMutex.unlock()
                        }
                    }
                }
            }
        }
    }

    private fun evaluateProfileAndBattery(now: Long, batteryLevel: Int, isCharging: Boolean) {
        synchronized(profileLock) {
            val stationaryDuration = now - lastMovementTimeMs
            val isStationary = stationaryDuration >= IDLE_THRESHOLD_MS
            val targetProfile = resolveTargetProfile(
                isStationary = isStationary,
                batteryLevel = batteryLevel,
                isCharging = isCharging
            )

            if (targetProfile != currentProfile) {
                val oldProfile = currentProfile
                currentProfile = targetProfile
                AppBatteryLogHelper.logAppUsageCheckpoint(
                    context = this,
                    reason = "Tukar Mod -> ${targetProfile.name}",
                    profileName = oldProfile.name,
                    phoneBatteryLevel = batteryLevel,
                    isCharging = isCharging
                )
                lastBatteryLogTimeMs = now
                lastLoggedBatteryLevel = batteryLevel
                lastLoggedChargingState = isCharging
                restartLocationUpdates()
            } else {
                evaluatePeriodicAppBatteryLog(now, batteryLevel, isCharging)
            }

            val shouldMonitorCrash = secureStorage.isCrashDetectionEnabled() && lastSpeedKmh >= 25f
            updateSensorState(shouldMonitorCrash || isStationary)
        }
    }

    private fun evaluatePeriodicAppBatteryLog(now: Long, batteryLevel: Int, isCharging: Boolean) {
        if (lastLoggedBatteryLevel == -1) {
            lastLoggedBatteryLevel = batteryLevel
            lastLoggedChargingState = isCharging
            return
        }

        val chargingChanged = lastLoggedChargingState != null && lastLoggedChargingState != isCharging
        val batteryStepReached = abs(batteryLevel - lastLoggedBatteryLevel) >= 5
        val periodicIntervalReached = (now - lastBatteryLogTimeMs) >= APP_BATTERY_LOG_INTERVAL_MS

        if (chargingChanged || batteryStepReached || periodicIntervalReached) {
            val reason = when {
                chargingChanged -> if (isCharging) "Mula Mengecas" else "Cabut Pengecas"
                batteryStepReached -> "Perubahan 5% Bateri"
                else -> "Laporan 30 Minit"
            }
            AppBatteryLogHelper.logAppUsageCheckpoint(
                context = this,
                reason = reason,
                profileName = currentProfile.name,
                phoneBatteryLevel = batteryLevel,
                isCharging = isCharging
            )
            lastBatteryLogTimeMs = now
            lastLoggedBatteryLevel = batteryLevel
            lastLoggedChargingState = isCharging
        }
    }

    private fun resolveTargetProfile(
        isStationary: Boolean,
        batteryLevel: Int,
        isCharging: Boolean
    ): PowerProfile {
        return if (isStationary) {
            if (batteryLevel < 20 && !isCharging) {
                PowerProfile.STATIONARY_LOW_BATTERY
            } else {
                PowerProfile.STATIONARY_NORMAL
            }
        } else {
            when {
                isCharging -> PowerProfile.MOVING_CHARGING
                batteryLevel >= 50 -> PowerProfile.MOVING_HIGH_BATTERY
                batteryLevel >= 20 -> PowerProfile.MOVING_MEDIUM_BATTERY
                else -> PowerProfile.MOVING_LOW_BATTERY
            }
        }
    }

    private fun evaluatePrivateZonesByGps(loc: Location, now: Long) {
        val privatePlaces = secureStorage.getPrivatePlaces()
        if (privatePlaces.isEmpty()) return

        val statePrefs = getSharedPreferences("keluargamy360_private_zone_state", Context.MODE_PRIVATE)
        val cooldownPrefs = getSharedPreferences("keluargamy360_geofence_cooldown", Context.MODE_PRIVATE)
        val distanceResult = FloatArray(1)

        for (place in privatePlaces) {
            Location.distanceBetween(
                loc.latitude,
                loc.longitude,
                place.latitude,
                place.longitude,
                distanceResult
            )
            val dist = distanceResult[0]
            val stateKey = "inside_${place.placeId}"
            val hasInitialState = statePrefs.contains(stateKey)

            val isInsideNow = if (hasInitialState && statePrefs.getBoolean(stateKey, false)) {
                dist <= (place.radiusMeters + 25f)
            } else {
                dist <= place.radiusMeters
            }

            if (!hasInitialState) {
                statePrefs.edit().putBoolean(stateKey, isInsideNow).apply()
                continue
            }

            val wasInside = statePrefs.getBoolean(stateKey, false)
            if (isInsideNow != wasInside) {
                val transitionCode = if (isInsideNow) {
                    Geofence.GEOFENCE_TRANSITION_ENTER
                } else {
                    Geofence.GEOFENCE_TRANSITION_EXIT
                }
                val cooldownKey = "${place.name}_$transitionCode"
                val lastTriggered = cooldownPrefs.getLong(cooldownKey, 0L)

                statePrefs.edit().putBoolean(stateKey, isInsideNow).apply()

                if (now - lastTriggered >= 600_000L) {
                    cooldownPrefs.edit().putLong(cooldownKey, now).apply()
                    PrivateZoneLogHelper.appendEvent(this, place.name, isInsideNow)
                    val msg = if (isInsideNow) {
                        "Anda telah tiba di zon peribadi: ${place.name}."
                    } else {
                        "Anda telah keluar dari zon peribadi: ${place.name}."
                    }
                    showPrivateZoneNotification(place.name, msg)
                }
            }
        }
    }

    private fun showPrivateZoneNotification(title: String, body: String) {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) return

        val openIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            title.hashCode(),
            openIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, KeluargaApp.CHANNEL_GEOFENCE_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_map)
            .setContentTitle("Zon Peribadi: $title")
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        NotificationManagerCompat.from(this).notify(title.hashCode(), notification)
    }

    private fun updateSensorState(enable: Boolean) {
        if (enable && !isSensorActive) {
            accelerometer?.let {
                sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
                isSensorActive = true
            }
        } else if (!enable && isSensorActive) {
            sensorManager?.unregisterListener(this)
            isSensorActive = false
            motionShakeCount = 0
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type != Sensor.TYPE_ACCELEROMETER) return
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        val gForce = sqrt((x * x + y * y + z * z).toDouble()) / SensorManager.GRAVITY_EARTH
        val now = System.currentTimeMillis()

        // 1. Pengesan impak kemalangan (>= 4.5G semasa memandu >= 25 km/j)
        if (secureStorage.isCrashDetectionEnabled() && gForce >= 4.5 && lastSpeedKmh >= 25f) {
            if (now - lastCrashTriggerTimeMs > 60_000L) {
                lastCrashTriggerTimeMs = now
                CrashAlertBus.triggerCrashCountdown()
            }
        }

        // 2. Bangun serta-merta dari mod tidur apabila pergerakan fizikal dikesan
        val isSleeping = currentProfile == PowerProfile.STATIONARY_NORMAL ||
                currentProfile == PowerProfile.STATIONARY_LOW_BATTERY
        if (isSleeping && abs(gForce - 1.0) >= 0.20) {
            if (now - firstMotionShakeMs > 2500L) {
                firstMotionShakeMs = now
                motionShakeCount = 1
            } else {
                motionShakeCount++
                if (motionShakeCount >= 3) {
                    motionShakeCount = 0
                    lastMovementTimeMs = now
                    val battery = BatteryHelper.getBatteryStatus(this)
                    evaluateProfileAndBattery(now, battery.level, battery.isCharging)
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        val battery = BatteryHelper.getBatteryStatus(this)
        val now = System.currentTimeMillis()
        currentProfile = resolveTargetProfile(
            isStationary = false,
            batteryLevel = battery.level,
            isCharging = battery.isCharging
        )
        if (lastLoggedBatteryLevel == -1) {
            lastBatteryLogTimeMs = now
            lastLoggedBatteryLevel = battery.level
            lastLoggedChargingState = battery.isCharging
            AppBatteryLogHelper.logAppUsageCheckpoint(
                context = this,
                reason = "Servis Mula",
                profileName = currentProfile.name,
                phoneBatteryLevel = battery.level,
                isCharging = battery.isCharging
            )
        }
        startLocationUpdates()
        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        if (!PermissionHelper.hasFineLocation(this)) return

        val (priority, intervalMs, minUpdateMs) = when (currentProfile) {
            PowerProfile.MOVING_CHARGING ->
                Triple(Priority.PRIORITY_HIGH_ACCURACY, 5_000L, 3_000L)
            PowerProfile.MOVING_HIGH_BATTERY ->
                Triple(Priority.PRIORITY_HIGH_ACCURACY, 8_000L, 5_000L)
            PowerProfile.MOVING_MEDIUM_BATTERY ->
                Triple(Priority.PRIORITY_HIGH_ACCURACY, 15_000L, 8_000L)
            PowerProfile.MOVING_LOW_BATTERY ->
                Triple(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 40_000L, 20_000L)
            PowerProfile.STATIONARY_NORMAL ->
                Triple(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 120_000L, 60_000L)
            PowerProfile.STATIONARY_LOW_BATTERY ->
                Triple(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 240_000L, 120_000L)
        }

        val request = LocationRequest.Builder(priority, intervalMs)
            .setMinUpdateIntervalMillis(minUpdateMs)
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
        monitorJob?.cancel()
        val battery = BatteryHelper.getBatteryStatus(this)
        AppBatteryLogHelper.logAppUsageCheckpoint(
            context = this,
            reason = "Servis Dihentikan",
            profileName = currentProfile.name,
            phoneBatteryLevel = battery.level,
            isCharging = battery.isCharging
        )
        updateSensorState(false)
        fusedLocationClient.removeLocationUpdates(locationCallback)
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val NOTIFICATION_ID = 3601
        private const val IDLE_THRESHOLD_MS = 120_000L              // Masuk mod tidur selepas 2 minit pegun
        private const val HEARTBEAT_NORMAL_MS = 300_000L            // Hantar status pegun setiap 5 minit
        private const val HEARTBEAT_LOW_BATTERY_MS = 600_000L       // Hantar status pegun setiap 10 minit (<20%)
        private const val APP_BATTERY_LOG_INTERVAL_MS = 1_800_000L  // Rekod statistik tenaga app setiap 30 minit
        private const val MIN_DISPLACEMENT_METERS = 25f             // Abaikan getaran GPS bawah 25 meter

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
