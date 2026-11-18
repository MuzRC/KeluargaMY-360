package com.muzrc.keluargamy360.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.muzrc.keluargamy360.KeluargaApp
import com.muzrc.keluargamy360.MainActivity
import com.muzrc.keluargamy360.R
import com.muzrc.keluargamy360.data.local.SecureStorage
import com.muzrc.keluargamy360.data.repository.LocationRepository
import com.muzrc.keluargamy360.util.AppBatteryLogHelper
import com.muzrc.keluargamy360.util.AppDiagnosticLogHelper
import com.muzrc.keluargamy360.util.BatteryHelper
import com.muzrc.keluargamy360.util.CrashAlertBus
import com.muzrc.keluargamy360.util.NotificationSoundHelper
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
        ACTIVE_VIEWING,         // Ahli sedang melihat peta secara langsung: GPS 2.5s (had 10 minit)
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

    // Android Activity Recognition API (utama) + penapis sensor accelerometer (sandaran)
    private var activityTransitionClient: ActivityRecognitionClient? = null
    private var activityTransitionPendingIntent: PendingIntent? = null
    private var activityTransitionReceiver: BroadcastReceiver? = null
    private var isActivityRecognitionActive = false

    private var lastUploadedLocation: Location? = null
    private var lastHistoryLocation: Location? = null
    private var lastUploadTimeMs: Long = 0L
    private var lastMovementTimeMs: Long = System.currentTimeMillis()
    private var lastEnteredStationaryMs: Long = 0L
    private var lastSpeedKmh: Float = 0f
    private var currentProfile: PowerProfile = PowerProfile.MOVING_HIGH_BATTERY

    private var lastBatteryLogTimeMs: Long = 0L
    private var lastLoggedBatteryLevel: Int = -1
    private var lastLoggedChargingState: Boolean? = null

    // Penapis sandaran accelerometer: memerlukan gerakan berterusan >= 8 saat
    private var sustainedMotionStartMs = 0L
    private var lastMotionImpulseMs = 0L

    private var sentLowBatteryAlert = false
    private var lastSpeedAlertTimeMs: Long = 0L
    private var lastCrashTriggerTimeMs: Long = 0L

    // Mod Active Viewing (GPS 2.5s automatik luput selepas 10 minit)
    private var familyWatchListener: ListenerRegistration? = null
    @Volatile private var activeViewingUntilMs: Long = 0L

    override fun onCreate() {
        super.onCreate()
        secureStorage = SecureStorage(this)
        locationRepository = LocationRepository(secureStorage)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        activityTransitionClient = ActivityRecognition.getClient(this)
        AppBatteryLogHelper.initBaseline()

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        setupActivityRecognition()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                val now = System.currentTimeMillis()
                val battery = BatteryHelper.getBatteryStatus(this@LocationTrackingService)

                // Rekod aktiviti GPS khusus app kita mengikut ketepatan semasa
                val isHighAccProfile = currentProfile == PowerProfile.ACTIVE_VIEWING ||
                        currentProfile == PowerProfile.MOVING_CHARGING ||
                        currentProfile == PowerProfile.MOVING_HIGH_BATTERY ||
                        currentProfile == PowerProfile.MOVING_MEDIUM_BATTERY
                AppBatteryLogHelper.recordGpsFix(isHighAccuracy = isHighAccProfile)

                val prevLoc = lastUploadedLocation
                val movedDistance = prevLoc?.distanceTo(loc) ?: Float.MAX_VALUE
                val elapsedSec = ((now - lastUploadTimeMs) / 1000f).coerceAtLeast(1f)

                // 1. Tapis GPS Drift: isyarat mesti tepat (accuracy <= 35m) & jarak lebih besar daripada ralat GPS
                val isAccurateFix = !loc.hasAccuracy() || loc.accuracy <= 35f
                if (!isAccurateFix && loc.hasAccuracy()) {
                    AppDiagnosticLogHelper.recordDiscardedGpsDrift(loc.accuracy)
                }
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

                val wasStationaryBeforeMove = (now - lastMovementTimeMs) >= IDLE_THRESHOLD_MS
                val isMovingNow = prevLoc == null || hasRealGpsSpeed || hasRealDisplacement
                if (isMovingNow) {
                    lastMovementTimeMs = now
                    if (prevLoc != null && isAccurateFix && movedDistance in 10f..5000f) {
                        AppDiagnosticLogHelper.recordMovingSegment(
                            context = this@LocationTrackingService,
                            distanceMeters = movedDistance,
                            speedKmh = if (hasRealGpsSpeed) gpsSpeedKmh else ((movedDistance / elapsedSec) * 3.6f),
                            elapsedSeconds = elapsedSec,
                            isNewTripStart = wasStationaryBeforeMove
                        )
                    }
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
                                isCharging = battery.isCharging,
                                context = this@LocationTrackingService
                            )
                            if (liveSent) {
                                AppBatteryLogHelper.recordFirestoreUpload()
                                // Hantar semua titik laluan luar talian yang tertunggak sebaik internet pulih
                                val flushedCount = locationRepository.flushOfflineRouteQueue(this@LocationTrackingService)
                                if (flushedCount > 0) {
                                    AppBatteryLogHelper.recordFirestoreUpload()
                                }
                            }
                        } else {
                            AppDiagnosticLogHelper.logSyncIssue(
                                context = this@LocationTrackingService,
                                operation = "Lokasi Langsung",
                                reason = "Tiada sambungan internet"
                            )
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

        setupActiveViewingListener()
        startAppBatteryMonitorLoop()
    }

    /**
     * Mendengar isyarat Active Viewing pada dokumen keluarga.
     * Apabila ahli lain membuka tab Peta, GPS dinaikkan kepada 2.5s sehingga maksimum 10 minit,
     * dan automatik kembali ke profil jimat bateri sebaik tamat tempoh atau peta ditutup.
     */
    private fun setupActiveViewingListener() {
        val familyId = secureStorage.getFamilyId() ?: return
        val myUid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        familyWatchListener?.remove()
        familyWatchListener = FirebaseFirestore.getInstance()
            .collection("families")
            .document(familyId)
            .addSnapshotListener { snap, _ ->
                if (snap == null || !snap.exists()) return@addSnapshotListener
                val untilMs = snap.getLong("activeViewingUntil") ?: 0L
                val viewerUid = snap.getString("activeViewerUid").orEmpty()
                val now = System.currentTimeMillis()

                // Hanya aktifkan jika ahli LAIN yang sedang melihat peta & belum melebihi had 10 minit
                val cappedUntil = if (viewerUid.isNotBlank() && viewerUid != myUid && untilMs > now) {
                    minOf(untilMs, now + ACTIVE_VIEWING_MAX_MS)
                } else {
                    0L
                }

                val wasActive = activeViewingUntilMs > now
                val isNowActive = cappedUntil > now
                activeViewingUntilMs = cappedUntil

                if (wasActive != isNowActive) {
                    AppDiagnosticLogHelper.logServiceLifecycle(
                        context = this,
                        eventTitle = if (isNowActive) {
                            "Mod Active Viewing Aktif (GPS 2.5s)"
                        } else {
                            "Mod Active Viewing Tamat -> Kembali Jimat Bateri"
                        },
                        extraDetail = if (isNowActive) "Had masa automatik: 10 minit" else "Tamat tempoh / Peta ditutup"
                    )
                    val battery = BatteryHelper.getBatteryStatus(this)
                    evaluateProfileAndBattery(now, battery.level, battery.isCharging)
                }
            }
    }

    private fun hasActivityRecognitionPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACTIVITY_RECOGNITION
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    /**
     * Mendaftarkan Android Activity Recognition API (perkakasan rendah tenaga Google)
     * supaya mod tidur hanya bangun apabila sistem mengesahkan pengguna berjalan, berlari,
     * berbasikal, atau memandu—mengabaikan pergerakan memegang telefon di tangan.
     */
    @SuppressLint("MissingPermission")
    private fun setupActivityRecognition() {
        if (isActivityRecognitionActive || !hasActivityRecognitionPermission()) return

        try {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent == null || !ActivityTransitionResult.hasResult(intent)) return
                    val result = ActivityTransitionResult.extractResult(intent) ?: return
                    for (event in result.transitionEvents) {
                        if (event.transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER &&
                            event.activityType in WAKEUP_ACTIVITIES
                        ) {
                            val now = System.currentTimeMillis()
                            val actName = when (event.activityType) {
                                DetectedActivity.IN_VEHICLE -> "Dalam Kenderaan"
                                DetectedActivity.ON_BICYCLE -> "Berbasikal"
                                DetectedActivity.WALKING -> "Berjalan Kaki"
                                DetectedActivity.RUNNING -> "Berlari"
                                else -> "Aktiviti Fizikal"
                            }
                            if (currentProfile == PowerProfile.STATIONARY_NORMAL ||
                                currentProfile == PowerProfile.STATIONARY_LOW_BATTERY
                            ) {
                                AppDiagnosticLogHelper.logWakeupTrigger(
                                    context = this@LocationTrackingService,
                                    source = "ActivityRecognition: $actName",
                                    profileBefore = currentProfile.name
                                )
                            }
                            lastMovementTimeMs = now
                            val battery = BatteryHelper.getBatteryStatus(this@LocationTrackingService)
                            evaluateProfileAndBattery(now, battery.level, battery.isCharging)
                            break
                        }
                    }
                }
            }
            activityTransitionReceiver = receiver
            ContextCompat.registerReceiver(
                this,
                receiver,
                IntentFilter(ACTION_ACTIVITY_TRANSITION),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )

            val transitions = WAKEUP_ACTIVITIES.map { activityType ->
                ActivityTransition.Builder()
                    .setActivityType(activityType)
                    .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER)
                    .build()
            }
            val request = ActivityTransitionRequest(transitions)

            val piIntent = Intent(ACTION_ACTIVITY_TRANSITION).setPackage(packageName)
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pendingIntent = PendingIntent.getBroadcast(this, 3602, piIntent, flags)
            activityTransitionPendingIntent = pendingIntent

            activityTransitionClient?.requestActivityTransitionUpdates(request, pendingIntent)
                ?.addOnSuccessListener {
                    isActivityRecognitionActive = true
                    val battery = BatteryHelper.getBatteryStatus(this)
                    evaluateProfileAndBattery(System.currentTimeMillis(), battery.level, battery.isCharging)
                }
                ?.addOnFailureListener {
                    isActivityRecognitionActive = false
                }
        } catch (_: Exception) {
            isActivityRecognitionActive = false
        }
    }

    @SuppressLint("MissingPermission")
    private fun teardownActivityRecognition() {
        try {
            activityTransitionPendingIntent?.let { pi ->
                if (hasActivityRecognitionPermission()) {
                    activityTransitionClient?.removeActivityTransitionUpdates(pi)
                }
                pi.cancel()
            }
            activityTransitionReceiver?.let { unregisterReceiver(it) }
        } catch (_: Exception) {
        } finally {
            activityTransitionPendingIntent = null
            activityTransitionReceiver = null
            isActivityRecognitionActive = false
        }
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
                    if (!isActivityRecognitionActive && hasActivityRecognitionPermission()) {
                        setupActivityRecognition()
                    }
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
                if (targetProfile == PowerProfile.STATIONARY_NORMAL ||
                    targetProfile == PowerProfile.STATIONARY_LOW_BATTERY
                ) {
                    lastEnteredStationaryMs = now
                }
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

            // Jika Activity Recognition aktif, sensor accelerometer tidak perlu dihidupkan semasa tidur
            val shouldMonitorCrash = secureStorage.isCrashDetectionEnabled() && lastSpeedKmh >= 25f
            val needFallbackWakeupSensor = isStationary && !isActivityRecognitionActive
            updateSensorState(shouldMonitorCrash || needFallbackWakeupSensor)
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
            AppDiagnosticLogHelper.flushGpsDriftSummary(this)
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
        val now = System.currentTimeMillis()
        val isActiveViewing = now < activeViewingUntilMs && (batteryLevel >= 15 || isCharging)
        if (isActiveViewing) {
            return PowerProfile.ACTIVE_VIEWING
        }

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
        val allPlaces = secureStorage.getPrivatePlaces() + secureStorage.getFamilyPlaces()
        if (allPlaces.isEmpty()) return

        val statePrefs = getSharedPreferences("keluargamy360_private_zone_state", Context.MODE_PRIVATE)
        val cooldownPrefs = getSharedPreferences("keluargamy360_geofence_cooldown", Context.MODE_PRIVATE)
        val distanceResult = FloatArray(1)
        val userName = FirebaseAuth.getInstance().currentUser?.displayName?.takeIf { it.isNotBlank() }
            ?: secureStorage.getCachedUserName()
            ?: "Ahli Keluarga"

        for (place in allPlaces) {
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
                    if (place.isPrivate) {
                        PrivateZoneLogHelper.appendEvent(this, place.name, isInsideNow)
                        val msg = if (isInsideNow) {
                            "Anda telah tiba di zon peribadi: ${place.name}."
                        } else {
                            "Anda telah keluar dari zon peribadi: ${place.name}."
                        }
                        showPrivateZoneNotification("Zon Peribadi: ${place.name}", msg)
                    } else {
                        val localMsg = if (isInsideNow) {
                            "Anda telah tiba di zon selamat: ${place.name}."
                        } else {
                            "Anda telah keluar dari zon selamat: ${place.name}."
                        }
                        val familyMsg = if (isInsideNow) {
                            "[MASUK] $userName telah tiba di zon selamat: ${place.name}."
                        } else {
                            "[KELUAR] $userName telah keluar dari zon selamat: ${place.name}."
                        }
                        showPrivateZoneNotification("Kemas Kini Zon: ${place.name}", localMsg)
                        serviceScope.launch {
                            withTimeoutOrNull(4500L) {
                                locationRepository.postFamilyAlert(
                                    type = "GEOFENCE",
                                    message = familyMsg
                                )
                            }
                        }
                    }
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

        val soundUri = NotificationSoundHelper.getGeofenceSoundUri(this)
        val builder = NotificationCompat.Builder(this, NotificationSoundHelper.getGeofenceChannelId(this))
            .setSmallIcon(android.R.drawable.ic_dialog_map)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(if (soundUri != null) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)

        if (soundUri != null) {
            builder.setSound(soundUri)
        } else {
            builder.setSilent(true)
        }
        val notification = builder.build()

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
            sustainedMotionStartMs = 0L
            lastMotionImpulseMs = 0L
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

        // 2. Sandaran bangun (hanya digunakan jika kebenaran Activity Recognition tidak diberikan):
        // Memerlukan tempoh bertenang 30s & gerakan berterusan sekurang-kurangnya 8 saat
        val isSleeping = currentProfile == PowerProfile.STATIONARY_NORMAL ||
                currentProfile == PowerProfile.STATIONARY_LOW_BATTERY
        if (!isActivityRecognitionActive &&
            isSleeping &&
            (now - lastEnteredStationaryMs) >= STATIONARY_COOLDOWN_MS &&
            abs(gForce - 1.0) >= 0.25
        ) {
            if (sustainedMotionStartMs == 0L || (now - lastMotionImpulseMs) > 2000L) {
                sustainedMotionStartMs = now
            }
            lastMotionImpulseMs = now

            if ((now - sustainedMotionStartMs) >= SUSTAINED_WAKEUP_MS) {
                sustainedMotionStartMs = 0L
                lastMotionImpulseMs = 0L
                AppDiagnosticLogHelper.logWakeupTrigger(
                    context = this,
                    source = "Accelerometer Fallback (8s)",
                    profileBefore = currentProfile.name
                )
                lastMovementTimeMs = now
                val battery = BatteryHelper.getBatteryStatus(this)
                evaluateProfileAndBattery(now, battery.level, battery.isCharging)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        if (!isActivityRecognitionActive && hasActivityRecognitionPermission()) {
            setupActivityRecognition()
        }
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
            AppDiagnosticLogHelper.logServiceLifecycle(
                context = this,
                eventTitle = "Servis Penjejakan GPS Dimulakan",
                extraDetail = "Profil awal: ${currentProfile.name} | Rangkaian: ${AppDiagnosticLogHelper.detectNetworkMode(this)}"
            )
        }
        startLocationUpdates()
        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        if (!PermissionHelper.hasFineLocation(this)) return

        val (priority, intervalMs, minUpdateMs) = when (currentProfile) {
            PowerProfile.ACTIVE_VIEWING ->
                Triple(Priority.PRIORITY_HIGH_ACCURACY, 2_500L, 1_500L)
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

    override fun onTaskRemoved(rootIntent: Intent?) {
        AppDiagnosticLogHelper.logServiceLifecycle(
            context = this,
            eventTitle = "Aplikasi Dileret Tutup (onTaskRemoved)",
            extraDetail = "Servis latar belakang kekal bertahan (START_STICKY)"
        )
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        familyWatchListener?.remove()
        monitorJob?.cancel()
        teardownActivityRecognition()
        AppDiagnosticLogHelper.flushGpsDriftSummary(this)
        val battery = BatteryHelper.getBatteryStatus(this)
        AppBatteryLogHelper.logAppUsageCheckpoint(
            context = this,
            reason = "Servis Dihentikan",
            profileName = currentProfile.name,
            phoneBatteryLevel = battery.level,
            isCharging = battery.isCharging
        )
        AppDiagnosticLogHelper.logServiceLifecycle(
            context = this,
            eventTitle = "Servis Penjejakan GPS Dihentikan (onDestroy)",
            extraDetail = "Profil terakhir: ${currentProfile.name}"
        )
        updateSensorState(false)
        fusedLocationClient.removeLocationUpdates(locationCallback)
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val NOTIFICATION_ID = 3601
        private const val ACTION_ACTIVITY_TRANSITION = "com.muzrc.keluargamy360.ACTION_ACTIVITY_TRANSITION"
        private const val IDLE_THRESHOLD_MS = 120_000L              // Masuk mod tidur selepas 2 minit pegun
        private const val STATIONARY_COOLDOWN_MS = 30_000L          // Tempoh bertenang 30s selepas masuk mod tidur
        private const val SUSTAINED_WAKEUP_MS = 8_000L              // Sandaran accelerometer: gerakan berterusan 8s
        private const val HEARTBEAT_NORMAL_MS = 300_000L            // Hantar status pegun setiap 5 minit
        private const val HEARTBEAT_LOW_BATTERY_MS = 600_000L       // Hantar status pegun setiap 10 minit (<20%)
        private const val ACTIVE_VIEWING_MAX_MS = 600_000L          // Had maksimum Active Viewing: 10 minit
        private const val APP_BATTERY_LOG_INTERVAL_MS = 1_800_000L  // Rekod statistik tenaga app setiap 30 minit
        private const val MIN_DISPLACEMENT_METERS = 25f             // Abaikan getaran GPS bawah 25 meter

        private val WAKEUP_ACTIVITIES = listOf(
            DetectedActivity.IN_VEHICLE,
            DetectedActivity.ON_BICYCLE,
            DetectedActivity.WALKING,
            DetectedActivity.RUNNING
        )

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
