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
import com.muzrc.keluargamy360.data.local.SecureStorage
import com.muzrc.keluargamy360.data.model.DecryptedMemberLocation
import com.muzrc.keluargamy360.data.model.FamilyGroup
import com.muzrc.keluargamy360.data.model.SafePlace
import com.muzrc.keluargamy360.data.model.User
import com.muzrc.keluargamy360.data.model.UserLocation
import com.muzrc.keluargamy360.data.repository.LocationRepository
import com.muzrc.keluargamy360.service.GeofenceReceiver
import com.muzrc.keluargamy360.service.LocationTrackingService
import com.muzrc.keluargamy360.util.CryptoHelper
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
    val activeSosAlert: String? = null,
    val statusMessage: String? = null,
    val isSendingSos: Boolean = false,
    val isTrackingEnabled: Boolean = true
)

class MapViewModel(
    private val appContext: Context,
    private val secureStorage: SecureStorage,
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance()
) : ViewModel() {

    private val locationRepository = LocationRepository(secureStorage)

    private val _uiState = MutableStateFlow(
        MapUiState(isTrackingEnabled = secureStorage.isTrackingEnabled())
    )
    val uiState: StateFlow<MapUiState> = _uiState.asStateFlow()

    private var locationsListener: ListenerRegistration? = null
    private var placesListener: ListenerRegistration? = null
    private var alertsListener: ListenerRegistration? = null

    private val userProfilesCache = mutableMapOf<String, User>()
    private val addressCache = mutableMapOf<String, String>()
    private val sessionStartTime = System.currentTimeMillis()

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

        // 1. Dengar lokasi tersulit semua ahli secara masa nyata
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

        // 2. Dengar senarai Zon Selamat (Geofence)
        placesListener?.remove()
        placesListener = db.collection("families").document(familyId)
            .collection("places")
            .addSnapshotListener { snapshot, _ ->
                if (snapshot == null) return@addSnapshotListener
                val places = snapshot.documents.mapNotNull { it.toObject(SafePlace::class.java) }
                _uiState.update { it.copy(safePlaces = places) }
                registerSystemGeofences(places)
            }

        // 3. Dengar amaran kecemasan SOS & daftar masuk selamat daripada ahli lain
        alertsListener?.remove()
        alertsListener = db.collection("families").document(familyId)
            .collection("alerts")
            .whereGreaterThan("timestamp", sessionStartTime)
            .addSnapshotListener { snapshot, _ ->
                if (snapshot == null) return@addSnapshotListener
                for (change in snapshot.documentChanges) {
                    val senderId = change.document.getString("senderId") ?: continue
                    if (senderId == auth.currentUser?.uid) continue

                    val alertType = change.document.getString("type") ?: "SOS"
                    val senderName = change.document.getString("senderName") ?: "Ahli Keluarga"
                    val msg = change.document.getString("message")
                        ?: "AMARAN SOS daripada $senderName! Sila semak lokasi beliau segera."

                    if (alertType == "CHECKIN") {
                        _uiState.update { it.copy(statusMessage = msg) }
                    } else {
                        _uiState.update { it.copy(activeSosAlert = msg) }
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

    fun toggleTracking(enabled: Boolean) {
        secureStorage.setTrackingEnabled(enabled)
        _uiState.update {
            it.copy(
                isTrackingEnabled = enabled,
                statusMessage = if (enabled) {
                    "Perkongsian lokasi masa nyata disambung semula."
                } else {
                    "Perkongsian lokasi dijeda sementara."
                }
            )
        }
        if (enabled) {
            LocationTrackingService.start(appContext)
        } else {
            LocationTrackingService.stop(appContext)
        }
    }

    fun sendSafeCheckIn() {
        val myUid = auth.currentUser?.uid ?: return
        val me = _uiState.value.members.find { it.uid == myUid }
        val senderName = me?.name ?: auth.currentUser?.displayName ?: "Ahli Keluarga"
        val address = me?.addressText ?: "lokasi terkini"

        viewModelScope.launch {
            val res = locationRepository.sendSafeCheckIn(senderName, address)
            res.fold(
                onSuccess = {
                    _uiState.update {
                        it.copy(statusMessage = "Status 'Saya Selamat' di $address telah dihantar!")
                    }
                },
                onFailure = { err ->
                    _uiState.update {
                        it.copy(statusMessage = "Gagal menghantar status: ${err.message}")
                    }
                }
            )
        }
    }

    fun triggerSos() {
        val myUid = auth.currentUser?.uid ?: return
        val me = _uiState.value.members.find { it.uid == myUid }
        val senderName = me?.name ?: auth.currentUser?.displayName ?: "Ahli Keluarga"
        val lat = me?.latitude ?: 0.0
        val lng = me?.longitude ?: 0.0

        viewModelScope.launch {
            _uiState.update { it.copy(isSendingSos = true) }
            val res = locationRepository.sendSosAlert(senderName, lat, lng)
            res.fold(
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

    fun addSafePlace(name: String, lat: Double, lng: Double, radiusMeters: Float) {
        val familyId = secureStorage.getFamilyId() ?: return
        val uid = auth.currentUser?.uid ?: return
        if (name.isBlank()) return

        viewModelScope.launch {
            try {
                val docRef = db.collection("families").document(familyId).collection("places").document()
                val place = SafePlace(
                    placeId = docRef.id,
                    name = name.trim(),
                    latitude = lat,
                    longitude = lng,
                    radiusMeters = radiusMeters,
                    createdBy = uid
                )
                docRef.set(place).await()
                _uiState.update { it.copy(statusMessage = "Zon selamat '${place.name}' berjaya ditambah.") }
            } catch (e: Exception) {
                _uiState.update { it.copy(statusMessage = "Gagal menambah zon: ${e.message}") }
            }
        }
    }

    fun deleteSafePlace(placeId: String) {
        val familyId = secureStorage.getFamilyId() ?: return
        viewModelScope.launch {
            try {
                db.collection("families").document(familyId)
                    .collection("places").document(placeId)
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
                .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT)
                .build()
        }

        val request = GeofencingRequest.Builder()
            .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
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
