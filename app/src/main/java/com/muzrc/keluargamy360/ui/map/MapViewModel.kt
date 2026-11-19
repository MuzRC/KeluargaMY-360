package com.muzrc.keluargamy360.ui.map

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.location.Geocoder
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

data class MapUiState(
    val familyGroup: FamilyGroup? = null,
    val members: List<DecryptedMemberLocation> = emptyList(),
    val selectedMember: DecryptedMemberLocation? = null,
    val memberNicknames: Map<String, String> = emptyMap(),
    val safePlaces: List<SafePlace> = emptyList(),
    val activityLogs: List<ActivityLogItem> = emptyList(),
    val activeHistoryRoute: List<HistoryPoint> = emptyList(),
    val historyOwnerName: String? = null,
    val historyTargetMember: DecryptedMemberLocation? = null,
    val historyDayOffset: Int = 0,
    val activeSosAlert: String? = null,
    val statusMessage: String? = null,
    val isSendingSos: Boolean = false,
    val isTrackingEnabled: Boolean = true,
    val lowBatteryAlertEnabled: Boolean = true,
    val speedAlertEnabled: Boolean = true,
    val speedLimitKmh: Float = 110f,
    val extendedStopAlertEnabled: Boolean = true,
    val extendedStopMinutes: Int = 20
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
            memberNicknames = secureStorage.getMemberNicknames(),
            isTrackingEnabled = secureStorage.isTrackingEnabled(),
            lowBatteryAlertEnabled = secureStorage.isLowBatteryAlertEnabled(),
            speedAlertEnabled = secureStorage.isSpeedAlertEnabled(),
            speedLimitKmh = secureStorage.getSpeedLimitKmh(),
            extendedStopAlertEnabled = secureStorage.isExtendedStopAlertEnabled(),
            extendedStopMinutes = secureStorage.getExtendedStopMinutes()
        )
    )
    val uiState: StateFlow<MapUiState> = _uiState.asStateFlow()

    private var locationsListener: ListenerRegistration? = null
    private var placesListener: ListenerRegistration? = null
    private var alertsListener: ListenerRegistration? = null

    private val userProfilesCache = ConcurrentHashMap<String, User>()
    private val addressCache = ConcurrentHashMap<String, String>()
    private val sessionStartTime = System.currentTimeMillis()
    private var lastActiveViewSentMs: Long = 0L

    val currentUserId: String
        get() = auth.currentUser?.uid.orEmpty()

    init {
        startRealtimeListeners()
    }

    private fun resolveEffectiveDisplayName(uid: String, accountName: String): String {
        val customNickname = _uiState.value.memberNicknames[uid]?.trim()
        return if (!customNickname.isNullOrEmpty()) customNickname else accountName
    }

    private fun isVpnOrSlowNetwork(): Boolean {
        return try {
            val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork ?: return true
            val caps = cm.getNetworkCapabilities(network) ?: return true
            val hasVpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            val isSlow = caps.linkDownstreamBandwidthKbps in 1..1500
            hasVpn || isSlow
        } catch (_: Exception) {
            false
        }
    }

    fun startRealtimeListeners() {
        val familyId = secureStorage.getFamilyId() ?: return
        val masterKey = secureStorage.getFamilyMasterKey() ?: return

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val famDoc = db.collection("families").document(familyId).get().await()
                val group = famDoc.toObject(FamilyGroup::class.java)
                _uiState.update { it.copy(familyGroup = group) }

                group?.memberIds?.map { memberUid ->
                    async {
                        val uDoc = db.collection("users").document(memberUid).get().await()
                        uDoc.toObject(User::class.java)?.let { u ->
                            userProfilesCache[memberUid] = u
                            if (memberUid == currentUserId && u.name.isNotBlank()) {
                                secureStorage.saveCachedUserName(u.name)
                            }
                        }
                    }
                }?.awaitAll()

                // Segarkan nama ahli di UI sebaik profil dari Firestore selesai dimuatkan
                _uiState.update { state ->
                    if (state.members.isEmpty()) state
                    else {
                        val refreshed = state.members.map { m ->
                            val baseName = userProfilesCache[m.uid]?.name ?: m.name
                            val effectiveName = resolveEffectiveDisplayName(m.uid, baseName)
                            val photo = userProfilesCache[m.uid]?.photoUrl ?: m.photoUrl
                            m.copy(name = effectiveName, photoUrl = photo)
                        }
                        val updatedSelected = state.selectedMember?.let { sel ->
                            refreshed.find { it.uid == sel.uid }
                        } ?: refreshed.firstOrNull()
                        state.copy(members = refreshed, selectedMember = updatedSelected)
                    }
                }
            } catch (_: Exception) {
            }
        }

        // 1. Lokasi ahli masa nyata (dekripsi selari + kemas kini serta-merta tanpa tunggu Geocoder)
        locationsListener?.remove()
        locationsListener = db.collection("families").document(familyId)
            .collection("locations")
            .addSnapshotListener { snapshot, _ ->
                if (snapshot == null) return@addSnapshotListener
                viewModelScope.launch {
                    val immediateList = withContext(Dispatchers.Default) {
                        coroutineScope {
                            snapshot.documents.map { doc ->
                                async {
                                    val encLoc = doc.toObject(UserLocation::class.java) ?: return@async null
                                    val plain = CryptoHelper.decrypt(
                                        encLoc.encryptedPayload,
                                        encLoc.iv,
                                        masterKey
                                    ) ?: return@async null

                                    val parts = plain.split("|")
                                    if (parts.size < 5) return@async null

                                    val lat = parts[0].toDoubleOrNull() ?: return@async null
                                    val lng = parts[1].toDoubleOrNull() ?: return@async null
                                    val speed = parts[2].toFloatOrNull() ?: 0f
                                    val battery = parts[3].toIntOrNull() ?: 100
                                    val charging = parts[4].toBooleanStrictOrNull() ?: false

                                    val profile = userProfilesCache[encLoc.uid] ?: fetchAndCacheUser(encLoc.uid)
                                    val accountName = profile?.name ?: "Ahli Keluarga"
                                    val displayName = resolveEffectiveDisplayName(encLoc.uid, accountName)
                                    val cacheKey = String.format(Locale.US, "%.3f,%.3f", lat, lng)
                                    val cachedAddress = addressCache[cacheKey]
                                        ?: String.format(Locale.US, "%.4f, %.4f", lat, lng)

                                    DecryptedMemberLocation(
                                        uid = encLoc.uid,
                                        name = displayName,
                                        photoUrl = profile?.photoUrl ?: "",
                                        latitude = lat,
                                        longitude = lng,
                                        speedKmh = speed,
                                        batteryLevel = battery,
                                        isCharging = charging,
                                        updatedAt = encLoc.updatedAt,
                                        addressText = cachedAddress
                                    )
                                }
                            }.awaitAll().filterNotNull()
                        }
                    }

                    // Paparkan pin & kelajuan di peta serta-merta (0ms lag)
                    _uiState.update { state ->
                        val updatedSelected = state.selectedMember?.let { sel ->
                            immediateList.find { it.uid == sel.uid }
                        } ?: immediateList.firstOrNull()

                        state.copy(
                            members = immediateList,
                            selectedMember = updatedSelected
                        )
                    }

                    // Cari nama jalan secara berasingan di background hanya jika rangkaian laju & tiada VPN
                    if (!isVpnOrSlowNetwork() && immediateList.isNotEmpty()) {
                        launch(Dispatchers.IO) {
                            var anyResolved = false
                            val enrichedList = immediateList.map { member ->
                                val resolved = resolveAddressFast(member.latitude, member.longitude)
                                if (resolved != member.addressText) {
                                    anyResolved = true
                                    member.copy(addressText = resolved)
                                } else {
                                    member
                                }
                            }
                            if (anyResolved) {
                                _uiState.update { state ->
                                    val updatedSelected = state.selectedMember?.let { sel ->
                                        enrichedList.find { it.uid == sel.uid }
                                    } ?: enrichedList.firstOrNull()
                                    state.copy(members = enrichedList, selectedMember = updatedSelected)
                                }
                            }
                        }
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
                secureStorage.saveFamilyPlaces(familyPlaces)
                val combinedPlaces = familyPlaces + secureStorage.getPrivatePlaces()
                _uiState.update { it.copy(safePlaces = combinedPlaces) }
                registerSystemGeofences(combinedPlaces)
            }

        // 3. Log Aktiviti Keluarga & Amaran Masa Nyata
        alertsListener?.remove()
        alertsListener = db.collection("families").document(familyId)
            .collection("alerts")
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .limit(100)
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
                        "CHECKIN", "BATTERY", "SPEED", "GEOFENCE", "STOP_ALERT" -> {
                            _uiState.update { it.copy(statusMessage = msg) }
                        }
                    }
                }
            }
    }

    private suspend fun fetchAndCacheUser(uid: String): User? {
        return withTimeoutOrNull(2500L) {
            try {
                val doc = db.collection("users").document(uid).get().await()
                doc.toObject(User::class.java)?.also { userProfilesCache[uid] = it }
            } catch (_: Exception) {
                null
            }
        }
    }

    private suspend fun resolveAddressFast(lat: Double, lng: Double): String {
        val key = String.format(Locale.US, "%.3f,%.3f", lat, lng)
        addressCache[key]?.let { return it }

        val fallback = String.format(Locale.US, "%.4f, %.4f", lat, lng)
        return withTimeoutOrNull(2000L) {
            withContext(Dispatchers.IO) {
                try {
                    val geocoder = Geocoder(appContext, Locale("ms", "MY"))
                    @Suppress("DEPRECATION")
                    val results = geocoder.getFromLocation(lat, lng, 1)
                    val formatted = if (!results.isNullOrEmpty()) {
                        val addr = results[0]
                        listOfNotNull(addr.thoroughfare, addr.locality ?: addr.subAdminArea, addr.adminArea)
                            .distinct()
                            .joinToString(", ")
                            .ifBlank { addr.getAddressLine(0) ?: fallback }
                    } else {
                        fallback
                    }
                    addressCache[key] = formatted
                    formatted
                } catch (_: Exception) {
                    fallback
                }
            }
        } ?: fallback
    }

    fun selectMember(member: DecryptedMemberLocation) {
        _uiState.update { it.copy(selectedMember = member) }
    }

    /**
     * Menetapkan nama panggilan peribadi pada telefon sendiri untuk mana-mana ahli (termasuk diri sendiri)
     * dan terus menyegarkan senarai, peta, serta lencana secara masa nyata tanpa perlu tutup aplikasi.
     */
    fun setMemberNickname(targetUid: String, nickname: String) {
        val cleanNickname = nickname.trim()
        secureStorage.saveMemberNickname(targetUid, cleanNickname)
        val updatedMap = secureStorage.getMemberNicknames()

        val baseAccountName = userProfilesCache[targetUid]?.name
            ?: (if (targetUid == currentUserId) auth.currentUser?.displayName else null)
            ?: "Ahli Keluarga"
        val effectiveName = if (cleanNickname.isNotEmpty()) cleanNickname else baseAccountName

        _uiState.update { state ->
            val updatedMembers = state.members.map { m ->
                if (m.uid == targetUid) m.copy(name = effectiveName) else m
            }
            val updatedSelected = state.selectedMember?.let { sel ->
                if (sel.uid == targetUid) sel.copy(name = effectiveName) else sel
            }
            val updatedHistoryMember = state.historyTargetMember?.let { hm ->
                if (hm.uid == targetUid) hm.copy(name = effectiveName) else hm
            }
            val updatedHistoryOwner = if (state.historyTargetMember?.uid == targetUid) {
                effectiveName
            } else {
                state.historyOwnerName
            }
            state.copy(
                memberNicknames = updatedMap,
                members = updatedMembers,
                selectedMember = updatedSelected,
                historyTargetMember = updatedHistoryMember,
                historyOwnerName = updatedHistoryOwner,
                statusMessage = if (cleanNickname.isNotEmpty()) {
                    "Nama panggilan ditukar ke '$cleanNickname'."
                } else {
                    "Nama panggilan dipulihkan ke '$baseAccountName'."
                }
            )
        }
    }

    fun load24HourRoute(member: DecryptedMemberLocation, dayOffset: Int = 0) {
        val safeOffset = dayOffset.coerceIn(0, 6)
        viewModelScope.launch {
            locationRepository.flushOfflineRouteQueue(appContext)

            val cal = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                add(Calendar.DAY_OF_YEAR, -safeOffset)
            }
            val startOfDayMs = cal.timeInMillis
            val endOfDayMs = if (safeOffset == 0) {
                System.currentTimeMillis()
            } else {
                startOfDayMs + 86_400_000L - 1L
            }

            val dayLabel = when (safeOffset) {
                0 -> "Hari Ini"
                1 -> "Semalam"
                else -> SimpleDateFormat("dd MMM", Locale("ms", "MY")).format(Date(startOfDayMs))
            }

            val points = locationRepository.getHistoryForRange(
                targetUid = member.uid,
                startTimeMs = startOfDayMs,
                endTimeMs = endOfDayMs,
                context = appContext
            )

            _uiState.update {
                it.copy(
                    selectedMember = member,
                    historyTargetMember = member,
                    historyOwnerName = member.name,
                    historyDayOffset = safeOffset,
                    activeHistoryRoute = if (points.size >= 2) points else emptyList(),
                    statusMessage = if (points.size >= 2) {
                        "Memaparkan ${points.size} titik laluan ${member.name} ($dayLabel)."
                    } else {
                        "Tiada rekod perjalanan mencukupi untuk ${member.name} pada $dayLabel."
                    }
                )
            }
        }
    }

    fun selectHistoryDay(dayOffset: Int) {
        val target = _uiState.value.historyTargetMember ?: _uiState.value.selectedMember ?: return
        load24HourRoute(target, dayOffset)
    }

    fun clear24HourRoute() {
        _uiState.update {
            it.copy(
                activeHistoryRoute = emptyList(),
                historyOwnerName = null,
                historyTargetMember = null,
                historyDayOffset = 0
            )
        }
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
        val cleanName = newName.trim()
        if (cleanName.isBlank()) return
        val uid = currentUserId

        // Kemas kini UI tempatan & cache nama latar belakang secara masa nyata serta-merta
        secureStorage.saveCachedUserName(cleanName)
        userProfilesCache[uid] = (userProfilesCache[uid] ?: User(uid = uid)).copy(name = cleanName)
        val effectiveName = resolveEffectiveDisplayName(uid, cleanName)
        _uiState.update { state ->
            val updatedMembers = state.members.map { m ->
                if (m.uid == uid) m.copy(name = effectiveName) else m
            }
            val updatedSelected = state.selectedMember?.let { sel ->
                if (sel.uid == uid) sel.copy(name = effectiveName) else sel
            }
            state.copy(members = updatedMembers, selectedMember = updatedSelected)
        }

        viewModelScope.launch {
            familyRepository.updateDisplayName(cleanName).fold(
                onSuccess = {
                    _uiState.update { it.copy(statusMessage = "Nama akaun berjaya ditukar ke '$cleanName'.") }
                },
                onFailure = { err ->
                    _uiState.update { it.copy(statusMessage = "Gagal menyimpan ke pelayan: ${err.message}") }
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

    fun toggleExtendedStopAlert(enabled: Boolean) {
        secureStorage.setExtendedStopAlertEnabled(enabled)
        _uiState.update { it.copy(extendedStopAlertEnabled = enabled) }
    }

    fun setExtendedStopMinutes(minutes: Int) {
        secureStorage.setExtendedStopMinutes(minutes)
        _uiState.update { it.copy(extendedStopMinutes = minutes) }
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

        val request = GeofencingRequest.Builder()
            .setInitialTrigger(0)
            .addGeofences(geofences)
            .build()

        client.addGeofences(request, pendingIntent)
    }

    /**
     * Memaklumkan pelayan bahawa pengguna sedang membuka atau menutup skrin Peta.
     * Mencetuskan mod GPS 2.5s (Active Viewing) pada telefon ahli keluarga dengan had masa 10 minit
     * dan penampan tulis (throttle 3 minit) supaya kuota Firebase kekal rendah.
     */
    fun notifyActiveViewing(isViewing: Boolean) {
        val familyId = secureStorage.getFamilyId() ?: return
        val uid = currentUserId
        if (uid.isBlank()) return

        val now = System.currentTimeMillis()
        if (isViewing) {
            // Jangan tulis berulang jika sesi Active Viewing baru sahaja dihantar < 3 minit lalu
            if (now - lastActiveViewSentMs < 180_000L) return
            lastActiveViewSentMs = now
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    db.collection("families").document(familyId)
                        .update(
                            mapOf(
                                "activeViewingUntil" to (now + 600_000L), // Had automatik 10 minit
                                "activeViewerUid" to uid
                            )
                        )
                        .await()
                } catch (_: Exception) {
                }
            }
        } else {
            lastActiveViewSentMs = 0L
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    db.collection("families").document(familyId)
                        .update(
                            mapOf(
                                "activeViewingUntil" to 0L,
                                "activeViewerUid" to ""
                            )
                        )
                        .await()
                } catch (_: Exception) {
                }
            }
        }
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
