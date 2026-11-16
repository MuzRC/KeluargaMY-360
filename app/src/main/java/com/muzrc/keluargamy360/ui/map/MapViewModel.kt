package com.muzrc.keluargamy360.ui.map

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.location.Geocoder
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.muzrc.keluargamy360.data.local.SecureStorage
import com.muzrc.keluargamy360.data.model.ActivityLogItem
import com.muzrc.keluargamy360.data.model.DecryptedMemberLocation
import com.muzrc.keluargamy360.data.model.FamilyGroup
import com.muzrc.keluargamy360.data.model.HistoryPoint
import com.muzrc.keluargamy360.data.model.SafePlace
import com.muzrc.keluargamy360.data.model.User
import com.muzrc.keluargamy360.data.model.UserLocation
import com.muzrc.keluargamy360.data.repository.FamilyRepository
import com.muzrc.keluargamy360.data.repository.LocationRepository
import com.muzrc.keluargamy360.service.GeofenceReceiver
import com.muzrc.keluargamy360.service.LocationTrackingService
import com.muzrc.keluargamy360.util.CryptoHelper
import com.muzrc.keluargamy360.util.DeviceRingerHelper
import com.muzrc.keluargamy360.util.PermissionHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.Locale

data class MapUiState(
    val familyGroup: FamilyGroup? = null,
    val members: List<DecryptedMemberLocation> = emptyList(),
    val selectedMember: DecryptedMemberLocation? = null,
    val safePlaces: List<SafePlace> = emptyList(),
    val activityLogs: List<ActivityLogItem> = emptyList(),
    val activeHistoryRoute: List<HistoryPoint> = emptyList(),
    val historyOwnerName: String? = null,
    val activeSosAlert: String? = null,
    val statusMessage: String? = null,
    val isSendingSos: Boolean = false,
    val isTrackingEnabled: Boolean = true,
    val lowBatteryAlertEnabled: Boolean = true,
    val speedAlertEnabled: Boolean = true,
    val speedLimitKmh: Float = 110f,
    val crashDetectionEnabled: Boolean = true
)

class MapViewModel(
    private val appContext: Context,
    private val secureStorage: SecureStorage,
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance()
) : ViewModel() {

    private val locationRepository = LocationRepository(secureStorage)
    private val familyRepository = FamilyRepository(secureStorage)

    private val _uiState = MutableStateFlow(
        MapUiState(
            isTrackingEnabled = secureStorage.isTrackingEnabled(),
            lowBatteryAlertEnabled = secureStorage.isLowBatteryAlertEnabled(),
            speedAlertEnabled = secureStorage.isSpeedAlertEnabled(),
            speedLimitKmh = secureStorage.getSpeedLimitKmh(),
            crashDetectionEnabled = secureStorage.isCrashDetectionEnabled()
        )
    )
    val uiState: StateFlow<MapUiState> = _uiState.asStateFlow()

    private var locationsListener: ListenerRegistration? = null
    private var placesListener: ListenerRegistration? = null
    private var alertsListener: ListenerRegistration? = null

    private val userProfilesCache = mutableMapOf<String, User>()
    private val addressCache = mutableMapOf<String, String>()
    private val sessionStartTime = System.currentTimeMillis()

    val currentUserId: String
        get() = auth.currentUser?.uid.orEmpty()

    init {
        startRealtimeListeners()
    }

    fun startRealtimeListeners() {
        val familyId = secureStorage.getFamilyId() ?: return
        val masterKey = secureStorage.getFamilyMasterKey() ?: return

        viewModelScope.launch {
            try {
                val famDoc = db.collection("families").document(familyId).get().await()
                val group = famDoc.toObject(FamilyGroup::class.java)
                _uiState.update { it.copy(familyGroup = group) }

                group?.memberIds?.forEach { memberUid ->
                    val uDoc = db.collection("users").document(memberUid).get().await()
                    uDoc.toObject(User::class.java)?.let { userProfilesCache[memberUid] = it }
                }
            } catch (_: Exception) {
            }
        }

        // 1. Lokasi ahli masa nyata
        locationsListener?.remove()
        locationsListener = db.collection("families").document(familyId)
            .collection("locations")
            .addSnapshotListener { snapshot, _ ->
                if (snapshot == null) return@addSnapshotListener
                viewModelScope.launch {
                    val decryptedList = mutableListOf<DecryptedMemberLocation>()

                    for (doc in snapshot.documents) {
                        val encLoc = doc.toObject(UserLocation::class.java) ?: continue
                        val plain = CryptoHelper.decrypt(
                            encLoc.encryptedPayload,
                            encLoc.iv,
                            masterKey
                        ) ?: continue

                        val parts = plain.split("|")
                        if (parts.size >= 5) {
                            val lat = parts[0].toDoubleOrNull() ?: continue
                            val lng = parts[1].toDoubleOrNull() ?: continue
                            val speed = parts[2].toFloatOrNull() ?: 0f
                            val battery = parts[3].toIntOrNull() ?: 100
                            val charging = parts[4].toBooleanStrictOrNull() ?: false

                            val profile = userProfilesCache[encLoc.uid] ?: fetchAndCacheUser(encLoc.uid)
                            val address = resolveAddress(lat, lng)

                            decryptedList.add(
                                DecryptedMemberLocation(
                                    uid = encLoc.uid,
                                    name = profile?.name ?: "Ahli Keluarga",
                                    photoUrl = profile?.photoUrl ?: "",
                                    latitude = lat,
                                    longitude = lng,
                                    speedKmh = speed,
                                    batteryLevel = battery,
                                    isCharging = charging,
                                    updatedAt = encLoc.updatedAt,
                                    addressText = address
                                )
                            )
                        }
                    }

                    _uiState.update { state ->
                        val updatedSelected = state.selectedMember?.let { sel ->
                            decryptedList.find { it.uid == sel.uid }
                        } ?: decryptedList.firstOrNull()

                        state.copy(
                            members = decryptedList,
                            selectedMember = updatedSelected
                        )
                    }
                }
            }

        // 2. Zon Selamat (Keluarga + Peribadi)
        placesListener?.remove()
        placesListener = db.collection("families").document(familyId)
            .collection("places")
            .addSnapshotListener { snapshot, _ ->
                if (snapshot == null) return@addSnapshotListener
                val familyPlaces = snapshot.documents.mapNotNull {
                    it.toObject(SafePlace::class.java)?.copy(isPrivate = false)
                }
                val combinedPlaces = familyPlaces + secureStorage.getPrivatePlaces()
                _uiState.update { it.copy(safePlaces = combinedPlaces) }
                registerSystemGeofences(combinedPlaces)
            }

        // 3. Log Aktiviti Keluarga & Amaran Masa Nyata (SOS, Ring Device, Bateri, Laju)
        alertsListener?.remove()
        alertsListener = db.collection("families").document(familyId)
            .collection("alerts")
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .limit(40)
            .addSnapshotListener { snapshot, _ ->
                if (snapshot == null) return@addSnapshotListener

                val logs = snapshot.documents.map { doc ->
                    ActivityLogItem(
                        id = doc.id,
                        type = doc.getString("type") ?: "INFO",
                        senderId = doc.getString("senderId").orEmpty(),
                        senderName = doc.getString("senderName") ?: "Ahli",
                        message = doc.getString("message").orEmpty(),
                        timestamp = doc.getLong("timestamp") ?: 0L
                    )
                }
                _uiState.update { it.copy(activityLogs = logs) }

                for (change in snapshot.documentChanges) {
                    val doc = change.document
                    val timestamp = doc.getLong("timestamp") ?: 0L
                    if (timestamp <= sessionStartTime) continue

                    val senderId = doc.getString("senderId").orEmpty()
                    val targetUid = doc.getString("targetUid").orEmpty()
                    val alertType = doc.getString("type").orEmpty()
                    val msg = doc.getString("message").orEmpty()

                    if (alertType == "RING" && targetUid == currentUserId) {
                        DeviceRingerHelper.ringLoudlyFor15Seconds(appContext)
                        _uiState.update { it.copy(statusMessage = msg) }
                        continue
                    }

                    if (senderId == currentUserId) continue

                    when (alertType) {
                        "SOS" -> _uiState.update { it.copy(activeSosAlert = msg) }
                        "CHECKIN", "BATTERY", "SPEED", "GEOFENCE" -> {
                            _uiState.update { it.copy(statusMessage = msg) }
                        }
                    }
                }
            }
    }

    private suspend fun fetchAndCacheUser(uid: String): User? {
        return try {
            val doc = db.collection("users").document(uid).get().await()
            doc.toObject(User::class.java)?.also { userProfilesCache[uid] = it }
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun resolveAddress(lat: Double, lng: Double): String {
        val key = String.format(Locale.US, "%.4f,%.4f", lat, lng)
        addressCache[key]?.let { return it }

        return withContext(Dispatchers.IO) {
            try {
                val geocoder = Geocoder(appContext, Locale("ms", "MY"))
                @Suppress("DEPRECATION")
                val results = geocoder.getFromLocation(lat, lng, 1)
                val formatted = if (!results.isNullOrEmpty()) {
                    val addr = results[0]
                    listOfNotNull(addr.thoroughfare, addr.locality ?: addr.subAdminArea, addr.adminArea)
                        .distinct()
                        .joinToString(", ")
                        .ifBlank { addr.getAddressLine(0) ?: key }
                } else {
                    key
                }
                addressCache[key] = formatted
                formatted
            } catch (e: Exception) {
                key
            }
        }
    }

    fun selectMember(member: DecryptedMemberLocation) {
        _uiState.update { it.copy(selectedMember = member) }
    }

    fun load24HourRoute(member: DecryptedMemberLocation) {
        viewModelScope.launch {
            val points = locationRepository.get24HourHistory(member.uid)
            if (points.size < 2) {
                _uiState.update {
                    it.copy(
                        activeHistoryRoute = emptyList(),
                        historyOwnerName = null,
                        statusMessage = "Belum cukup rekod pergerakan 24 jam untuk ${member.name}."
                    )
                }
            } else {
                _uiState.update {
                    it.copy(
                        selectedMember = member,
                        activeHistoryRoute = points,
                        historyOwnerName = member.name,
                        statusMessage = "Memaparkan ${points.size} titik laluan 24 jam ${member.name}."
                    )
                }
            }
        }
    }

    fun clear24HourRoute() {
        _uiState.update { it.copy(activeHistoryRoute = emptyList(), historyOwnerName = null) }
    }

    fun ringMemberDevice(member: DecryptedMemberLocation) {
        viewModelScope.launch {
            val res = locationRepository.sendRingRequest(member.uid, member.name)
            res.fold(
                onSuccess = {
                    _uiState.update {
                        it.copy(statusMessage = "Isyarat deringan penggera telah dihantar ke telefon ${member.name}!")
                    }
                },
                onFailure = { err ->
                    _uiState.update { it.copy(statusMessage = "Gagal menghantar deringan: ${err.message}") }
                }
            )
        }
    }

    fun updateMyDisplayName(newName: String) {
        viewModelScope.launch {
            familyRepository.updateDisplayName(newName).fold(
                onSuccess = {
                    val uid = currentUserId
                    userProfilesCache[uid]?.let { userProfilesCache[uid] = it.copy(name = newName.trim()) }
                    _uiState.update { it.copy(statusMessage = "Nama paparan berjaya ditukar ke '${newName.trim()}'.") }
                },
                onFailure = { err ->
                    _uiState.update { it.copy(statusMessage = "Gagal menukar nama: ${err.message}") }
                }
            )
        }
    }

    fun removeMember(member: DecryptedMemberLocation) {
        val familyId = secureStorage.getFamilyId() ?: return
        viewModelScope.launch {
            familyRepository.removeMemberFromFamily(familyId, member.uid).fold(
                onSuccess = {
                    _uiState.update {
                        it.copy(
                            members = it.members.filter { m -> m.uid != member.uid },
                            statusMessage = "${member.name} telah dikeluarkan daripada kumpulan."
                        )
                    }
                },
                onFailure = { err ->
                    _uiState.update { it.copy(statusMessage = "Gagal mengeluarkan ahli: ${err.message}") }
                }
            )
        }
    }

    fun leaveCurrentFamily(onLeftSuccess: () -> Unit) {
        val familyId = secureStorage.getFamilyId() ?: return
        viewModelScope.launch {
            familyRepository.leaveFamily(familyId).fold(
                onSuccess = {
                    LocationTrackingService.stop(appContext)
                    secureStorage.clearSession()
                    onLeftSuccess()
                },
                onFailure = { err ->
                    _uiState.update { it.copy(statusMessage = "Gagal keluar kumpulan: ${err.message}") }
                }
            )
        }
    }

    fun toggleTracking(enabled: Boolean) {
        secureStorage.setTrackingEnabled(enabled)
        _uiState.update {
            it.copy(
                isTrackingEnabled = enabled,
                statusMessage = if (enabled) "Perkongsian GPS disambung semula." else "Perkongsian GPS dijeda sementara."
            )
        }
        if (enabled) LocationTrackingService.start(appContext) else LocationTrackingService.stop(appContext)
    }

    fun toggleLowBatteryAlert(enabled: Boolean) {
        secureStorage.setLowBatteryAlertEnabled(enabled)
        _uiState.update { it.copy(lowBatteryAlertEnabled = enabled) }
    }

    fun toggleSpeedAlert(enabled: Boolean) {
        secureStorage.setSpeedAlertEnabled(enabled)
        _uiState.update { it.copy(speedAlertEnabled = enabled) }
    }

    fun setSpeedLimit(limitKmh: Float) {
        secureStorage.setSpeedLimitKmh(limitKmh)
        _uiState.update { it.copy(speedLimitKmh = limitKmh) }
    }

    fun toggleCrashDetection(enabled: Boolean) {
        secureStorage.setCrashDetectionEnabled(enabled)
        _uiState.update { it.copy(crashDetectionEnabled = enabled) }
    }

    fun sendSafeCheckIn() {
        val me = _uiState.value.members.find { it.uid == currentUserId }
        val senderName = me?.name ?: auth.currentUser?.displayName ?: "Ahli Keluarga"
        val address = me?.addressText ?: "lokasi terkini"

        viewModelScope.launch {
            locationRepository.sendSafeCheckIn(senderName, address).fold(
                onSuccess = {
                    _uiState.update { it.copy(statusMessage = "Status 'Saya Selamat' di $address telah dihantar!") }
                },
                onFailure = { err ->
                    _uiState.update { it.copy(statusMessage = "Gagal menghantar status: ${err.message}") }
                }
            )
        }
    }

    fun triggerSos(customPrefix: String = "") {
        val me = _uiState.value.members.find { it.uid == currentUserId }
        val senderName = if (customPrefix.isNotBlank()) {
            "$customPrefix (${me?.name ?: auth.currentUser?.displayName ?: "Ahli"})"
        } else {
            me?.name ?: auth.currentUser?.displayName ?: "Ahli Keluarga"
        }
        val lat = me?.latitude ?: 0.0
        val lng = me?.longitude ?: 0.0

        viewModelScope.launch {
            _uiState.update { it.copy(isSendingSos = true) }
            locationRepository.sendSosAlert(senderName, lat, lng).fold(
                onSuccess = {
                    _uiState.update {
                        it.copy(
                            isSendingSos = false,
                            statusMessage = "Amaran kecemasan SOS telah dihantar kepada semua ahli keluarga!"
                        )
                    }
                },
                onFailure = { err ->
                    _uiState.update {
                        it.copy(
                            isSendingSos = false,
                            statusMessage = "Gagal menghantar SOS: ${err.message}"
                        )
                    }
                }
            )
        }
    }

    fun addSafePlace(name: String, lat: Double, lng: Double, radiusMeters: Float, isPrivate: Boolean) {
        val uid = currentUserId
        if (uid.isBlank() || name.isBlank()) return

        if (isPrivate) {
            val currentPrivate = secureStorage.getPrivatePlaces().toMutableList()
            val newPlace = SafePlace(
                placeId = "priv_${System.currentTimeMillis()}",
                name = name.trim(),
                latitude = lat,
                longitude = lng,
                radiusMeters = radiusMeters,
                createdBy = uid,
                isPrivate = true
            )
            currentPrivate.add(newPlace)
            secureStorage.savePrivatePlaces(currentPrivate)

            val combined = _uiState.value.safePlaces.filter { !it.isPrivate } + currentPrivate
            _uiState.update {
                it.copy(
                    safePlaces = combined,
                    statusMessage = "Zon peribadi '${newPlace.name}' disimpan di telefon."
                )
            }
            registerSystemGeofences(combined)
            return
        }

        val familyId = secureStorage.getFamilyId() ?: return
        viewModelScope.launch {
            try {
                val docRef = db.collection("families").document(familyId).collection("places").document()
                val place = SafePlace(
                    placeId = docRef.id,
                    name = name.trim(),
                    latitude = lat,
                    longitude = lng,
                    radiusMeters = radiusMeters,
                    createdBy = uid,
                    isPrivate = false
                )
                docRef.set(place).await()
                _uiState.update { it.copy(statusMessage = "Zon keluarga '${place.name}' berjaya dikongsi.") }
            } catch (e: Exception) {
                _uiState.update { it.copy(statusMessage = "Gagal menambah zon: ${e.message}") }
            }
        }
    }

    fun deleteSafePlace(place: SafePlace) {
        if (place.isPrivate) {
            val updatedPrivate = secureStorage.getPrivatePlaces().filter { it.placeId != place.placeId }
            secureStorage.savePrivatePlaces(updatedPrivate)
            val combined = _uiState.value.safePlaces.filter { !it.isPrivate } + updatedPrivate
            _uiState.update { it.copy(safePlaces = combined) }
            registerSystemGeofences(combined)
            return
        }

        val familyId = secureStorage.getFamilyId() ?: return
        viewModelScope.launch {
            try {
                db.collection("families").document(familyId)
                    .collection("places").document(place.placeId)
                    .delete()
                    .await()
            } catch (_: Exception) {
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun registerSystemGeofences(places: List<SafePlace>) {
        if (!PermissionHelper.hasFineLocation(appContext) || !PermissionHelper.hasBackgroundLocation(appContext)) return
        val client = LocationServices.getGeofencingClient(appContext)

        val intent = Intent(appContext, GeofenceReceiver::class.java)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getBroadcast(appContext, 3602, intent, flags)

        client.removeGeofences(pendingIntent)
        if (places.isEmpty()) return

        val geofences = places.map { place ->
            Geofence.Builder()
                .setRequestId(place.name)
                .setCircularRegion(place.latitude, place.longitude, place.radiusMeters)
                .setExpirationDuration(Geofence.NEVER_EXPIRE)
                .setNotificationResponsiveness(15_000)
                .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT)
                .build()
        }

        // Set initialTrigger = 0 supaya tidak membunyikan notifikasi palsu setiap kali aplikasi dibuka
        val request = GeofencingRequest.Builder()
            .setInitialTrigger(0)
            .addGeofences(geofences)
            .build()

        client.addGeofences(request, pendingIntent)
    }

    fun clearStatusMessage() {
        _uiState.update { it.copy(statusMessage = null) }
    }

    fun dismissSosAlert() {
        _uiState.update { it.copy(activeSosAlert = null) }
    }

    override fun onCleared() {
        locationsListener?.remove()
        placesListener?.remove()
        alertsListener?.remove()
        super.onCleared()
    }
}
