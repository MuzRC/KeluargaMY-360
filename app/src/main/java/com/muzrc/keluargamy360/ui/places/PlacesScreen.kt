package com.muzrc.keluargamy360.ui.places

import android.location.Geocoder
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.PinDrop
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.firebase.auth.FirebaseAuth
import com.muzrc.keluargamy360.ui.map.MapViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition
import java.util.Locale
import kotlin.math.roundToInt

private fun parseCoordinatesText(raw: String): Pair<Double, Double>? {
    val cleaned = raw.trim()
    if (cleaned.isBlank()) return null
    val regex = Regex("""(-?\d+(?:\.\d+)?)\s*[,;\s]+\s*(-?\d+(?:\.\d+)?)""")
    val match = regex.find(cleaned) ?: return null
    val lat = match.groupValues[1].toDoubleOrNull() ?: return null
    val lng = match.groupValues[2].toDoubleOrNull() ?: return null
    if (lat !in -90.0..90.0 || lng !in -180.0..180.0) return null
    return lat to lng
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlacesScreen(viewModel: MapViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()

    var showAddDialog by remember { mutableStateOf(false) }
    var placeName by remember { mutableStateOf("") }
    var radiusMeters by remember { mutableFloatStateOf(150f) }
    var isPrivateZone by remember { mutableStateOf(false) }
    var offlineStatus by remember { mutableStateOf("Sedia untuk muat turun peta sekitar lokasi anda") }
    var isDownloadingOffline by remember { mutableStateOf(false) }

    // 0 = Lokasi Semasa, 1 = Pilih di Peta, 2 = Koordinat GPS, 3 = Cari Alamat
    var locationSourceMode by remember { mutableIntStateOf(0) }
    var selectedLat by remember { mutableDoubleStateOf(5.3473) }
    var selectedLng by remember { mutableDoubleStateOf(115.7455) }
    var selectedLocationSummary by remember { mutableStateOf("") }
    var coordInputText by remember { mutableStateOf("") }
    var searchAddressQuery by remember { mutableStateOf("") }
    var isSearchingAddress by remember { mutableStateOf(false) }
    var pickerMapLibre by remember { mutableStateOf<MapLibreMap?>(null) }

    val currentUserId = FirebaseAuth.getInstance().currentUser?.uid
    val myLocation = uiState.members.find { it.uid == currentUserId } ?: uiState.selectedMember

    fun openAddDialogWithDefaults() {
        locationSourceMode = 0
        placeName = ""
        radiusMeters = 150f
        isPrivateZone = false
        val baseLat = myLocation?.latitude ?: 5.3473
        val baseLng = myLocation?.longitude ?: 115.7455
        selectedLat = baseLat
        selectedLng = baseLng
        selectedLocationSummary = myLocation?.addressText
            ?: String.format(Locale.US, "%.5f, %.5f", baseLat, baseLng)
        coordInputText = String.format(Locale.US, "%.5f, %.5f", baseLat, baseLng)
        searchAddressQuery = ""
        showAddDialog = true
    }

    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("Tambah Zon Selamat", fontWeight = FontWeight.Bold) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // 4 Pilihan Cara Tetapkan Lokasi Zon
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        SegmentedButton(
                            selected = locationSourceMode == 0,
                            onClick = {
                                locationSourceMode = 0
                                myLocation?.let {
                                    selectedLat = it.latitude
                                    selectedLng = it.longitude
                                    selectedLocationSummary = it.addressText
                                }
                            },
                            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 4),
                            icon = {}
                        ) {
                            Text("Semasa", style = MaterialTheme.typography.labelSmall, maxLines = 1)
                        }
                        SegmentedButton(
                            selected = locationSourceMode == 1,
                            onClick = { locationSourceMode = 1 },
                            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 4),
                            icon = {}
                        ) {
                            Text("Peta", style = MaterialTheme.typography.labelSmall, maxLines = 1)
                        }
                        SegmentedButton(
                            selected = locationSourceMode == 2,
                            onClick = {
                                locationSourceMode = 2
                                coordInputText = String.format(Locale.US, "%.5f, %.5f", selectedLat, selectedLng)
                            },
                            shape = SegmentedButtonDefaults.itemShape(index = 2, count = 4),
                            icon = {}
                        ) {
                            Text("Koordinat", style = MaterialTheme.typography.labelSmall, maxLines = 1)
                        }
                        SegmentedButton(
                            selected = locationSourceMode == 3,
                            onClick = { locationSourceMode = 3 },
                            shape = SegmentedButtonDefaults.itemShape(index = 3, count = 4),
                            icon = {}
                        ) {
                            Text("Cari", style = MaterialTheme.typography.labelSmall, maxLines = 1)
                        }
                    }

                    when (locationSourceMode) {
                        0 -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.MyLocation,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = myLocation?.addressText ?: "Menunggu isyarat GPS semasa...",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        1 -> {
                            Text(
                                text = "Gerakkan peta sehingga pin sasaran berada tepat di tengah lokasi:",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(210.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                            ) {
                                val miniMapView = remember {
                                    MapView(context).apply {
                                        getMapAsync { m ->
                                            m.setStyle(
                                                Style.Builder().fromUri("https://tiles.openfreemap.org/styles/liberty")
                                            ) {
                                                pickerMapLibre = m
                                                m.moveCamera(
                                                    CameraUpdateFactory.newLatLngZoom(
                                                        LatLng(selectedLat, selectedLng),
                                                        15.5
                                                    )
                                                )
                                                m.addOnCameraIdleListener {
                                                    val center = m.cameraPosition.target ?: return@addOnCameraIdleListener
                                                    selectedLat = center.latitude
                                                    selectedLng = center.longitude
                                                    selectedLocationSummary = String.format(
                                                        Locale.US,
                                                        "%.5f, %.5f",
                                                        center.latitude,
                                                        center.longitude
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }

                                DisposableEffect(lifecycleOwner, miniMapView) {
                                    val obs = LifecycleEventObserver { _, event ->
                                        when (event) {
                                            Lifecycle.Event.ON_START -> miniMapView.onStart()
                                            Lifecycle.Event.ON_RESUME -> miniMapView.onResume()
                                            Lifecycle.Event.ON_PAUSE -> miniMapView.onPause()
                                            Lifecycle.Event.ON_STOP -> miniMapView.onStop()
                                            Lifecycle.Event.ON_DESTROY -> miniMapView.onDestroy()
                                            else -> Unit
                                        }
                                    }
                                    lifecycleOwner.lifecycle.addObserver(obs)
                                    onDispose {
                                        pickerMapLibre = null
                                        lifecycleOwner.lifecycle.removeObserver(obs)
                                    }
                                }

                                AndroidView(factory = { miniMapView }, modifier = Modifier.fillMaxSize())

                                // Penanda sasaran tetap di tengah peta mini
                                Icon(
                                    imageVector = Icons.Default.Place,
                                    contentDescription = "Pin Sasaran",
                                    tint = Color(0xFFD32F2F),
                                    modifier = Modifier
                                        .size(36.dp)
                                        .align(Alignment.Center)
                                        .offset(y = (-16).dp)
                                )
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFFD32F2F))
                                        .align(Alignment.Center)
                                )

                                // Butang fokus semula ke lokasi semasa pengguna
                                myLocation?.let { me ->
                                    SmallFloatingActionButton(
                                        onClick = {
                                            pickerMapLibre?.animateCamera(
                                                CameraUpdateFactory.newLatLngZoom(
                                                    LatLng(me.latitude, me.longitude),
                                                    16.0
                                                ),
                                                500
                                            )
                                        },
                                        modifier = Modifier
                                            .align(Alignment.BottomEnd)
                                            .padding(8.dp)
                                    ) {
                                        Icon(Icons.Default.MyLocation, contentDescription = "Lokasi Saya", modifier = Modifier.size(18.dp))
                                    }
                                }
                            }
                            Text(
                                text = String.format(Locale.US, "Koordinat Pin: %.5f, %.5f", selectedLat, selectedLng),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        2 -> {
                            val parsed = remember(coordInputText) { parseCoordinatesText(coordInputText) }
                            OutlinedTextField(
                                value = coordInputText,
                                onValueChange = { txt ->
                                    coordInputText = txt
                                    parseCoordinatesText(txt)?.let { (lat, lng) ->
                                        selectedLat = lat
                                        selectedLng = lng
                                        selectedLocationSummary = String.format(Locale.US, "%.5f, %.5f", lat, lng)
                                    }
                                },
                                label = { Text("Tampal Koordinat (Lat, Lng)") },
                                placeholder = { Text("cth: 5.34730, 115.74550") },
                                singleLine = true,
                                isError = coordInputText.isNotBlank() && parsed == null,
                                leadingIcon = { Icon(Icons.Default.PinDrop, null) },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(
                                text = if (parsed != null) {
                                    String.format(Locale.US, "Sah: Lat %.5f, Lng %.5f", parsed.first, parsed.second)
                                } else {
                                    "Format diterima: 5.3473, 115.7455 (boleh terus salin dari Google Maps)"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = if (parsed != null) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        3 -> {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                OutlinedTextField(
                                    value = searchAddressQuery,
                                    onValueChange = { searchAddressQuery = it },
                                    label = { Text("Nama Tempat / Alamat") },
                                    placeholder = { Text("cth: SMK Beaufort") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                                FilledTonalIconButton(
                                    enabled = searchAddressQuery.isNotBlank() && !isSearchingAddress,
                                    onClick = {
                                        isSearchingAddress = true
                                        coroutineScope.launch {
                                            val found = withContext(Dispatchers.IO) {
                                                try {
                                                    val geocoder = Geocoder(context, Locale("ms", "MY"))
                                                    @Suppress("DEPRECATION")
                                                    geocoder.getFromLocationName(searchAddressQuery.trim(), 1)?.firstOrNull()
                                                } catch (_: Exception) {
                                                    null
                                                }
                                            }
                                            isSearchingAddress = false
                                            if (found != null) {
                                                selectedLat = found.latitude
                                                selectedLng = found.longitude
                                                val addrLine = found.getAddressLine(0)
                                                    ?: String.format(Locale.US, "%.5f, %.5f", found.latitude, found.longitude)
                                                selectedLocationSummary = addrLine
                                                if (placeName.isBlank()) {
                                                    placeName = searchAddressQuery.trim()
                                                }
                                            } else {
                                                Toast.makeText(
                                                    context,
                                                    "Lokasi tidak dijumpai. Cuba nama bandar atau guna tab Peta/Koordinat.",
                                                    Toast.LENGTH_LONG
                                                ).show()
                                            }
                                        }
                                    }
                                ) {
                                    if (isSearchingAddress) {
                                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                    } else {
                                        Icon(Icons.Default.Search, contentDescription = "Cari")
                                    }
                                }
                            }
                            if (selectedLocationSummary.isNotBlank()) {
                                Text(
                                    text = "Hasil: $selectedLocationSummary",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }

                    OutlinedTextField(
                        value = placeName,
                        onValueChange = { placeName = it },
                        label = { Text("Nama Zon (cth: Rumah, Sekolah, Pejabat)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = !isPrivateZone,
                            onClick = { isPrivateZone = false },
                            label = { Text("Keluarga") },
                            leadingIcon = { Icon(Icons.Default.Group, null, Modifier.size(16.dp)) },
                            modifier = Modifier.weight(1f)
                        )
                        FilterChip(
                            selected = isPrivateZone,
                            onClick = { isPrivateZone = true },
                            label = { Text("Peribadi") },
                            leadingIcon = { Icon(Icons.Default.Lock, null, Modifier.size(16.dp)) },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Text(
                        text = if (isPrivateZone) {
                            "Disimpan di telefon ini sahaja. Ahli lain tidak dapat melihat zon ini."
                        } else {
                            "Dikongsi dengan semua ahli dalam kumpulan keluarga."
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )

                    Column {
                        Text("Jejari Zon: ${radiusMeters.roundToInt()} meter", style = MaterialTheme.typography.labelMedium)
                        Slider(
                            value = radiusMeters,
                            onValueChange = { radiusMeters = it },
                            valueRange = 100f..500f,
                            steps = 7
                        )
                    }
                }
            },
            confirmButton = {
                val isCoordValid = if (locationSourceMode == 2) {
                    parseCoordinatesText(coordInputText) != null
                } else {
                    true
                }
                val canSave = placeName.isNotBlank() && isCoordValid &&
                    (locationSourceMode != 0 || myLocation != null)

                Button(
                    onClick = {
                        val finalCoords = when (locationSourceMode) {
                            0 -> myLocation?.let { it.latitude to it.longitude }
                            2 -> parseCoordinatesText(coordInputText)
                            else -> selectedLat to selectedLng
                        }
                        if (finalCoords != null && placeName.isNotBlank()) {
                            viewModel.addSafePlace(
                                name = placeName,
                                lat = finalCoords.first,
                                lng = finalCoords.second,
                                radiusMeters = radiusMeters,
                                isPrivate = isPrivateZone
                            )
                            placeName = ""
                            showAddDialog = false
                        }
                    },
                    enabled = canSave
                ) { Text("Simpan") }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) { Text("Batal") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Zon Selamat & Peta Offline", fontWeight = FontWeight.Bold) })
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { openAddDialogWithDefaults() },
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text("Tambah Zon") }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
        ) {
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 14.dp)
            ) {
                Row(
                    modifier = Modifier
                        .padding(14.dp)
                        .fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.CloudDownload, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Peta Luar Talian (Offline Cache)", fontWeight = FontWeight.Bold)
                        Text(offlineStatus, style = MaterialTheme.typography.bodySmall)
                    }
                    FilledTonalButton(
                        enabled = myLocation != null && !isDownloadingOffline,
                        onClick = {
                            val loc = myLocation ?: return@FilledTonalButton
                            isDownloadingOffline = true
                            offlineStatus = "Memuat turun jubin peta 10km..."
                            val bounds = LatLngBounds.Builder()
                                .include(LatLng(loc.latitude + 0.08, loc.longitude + 0.08))
                                .include(LatLng(loc.latitude - 0.08, loc.longitude - 0.08))
                                .build()
                            val def = OfflineTilePyramidRegionDefinition(
                                "https://tiles.openfreemap.org/styles/liberty",
                                bounds,
                                10.0,
                                15.0,
                                context.resources.displayMetrics.density
                            )
                            val meta = JSONObject().put("name", "Kawasan Semasa").toString().toByteArray()
                            OfflineManager.getInstance(context).createOfflineRegion(
                                def,
                                meta,
                                object : OfflineManager.CreateOfflineRegionCallback {
                                    override fun onCreate(region: OfflineRegion) {
                                        region.setDownloadState(OfflineRegion.STATE_ACTIVE)
                                        isDownloadingOffline = false
                                        offlineStatus = "Peta luar talian berjaya disimpan di telefon!"
                                        Toast.makeText(context, "Peta luar talian sedia digunakan!", Toast.LENGTH_SHORT).show()
                                    }

                                    override fun onError(error: String) {
                                        isDownloadingOffline = false
                                        offlineStatus = "Gagal muat turun: $error"
                                    }
                                }
                            )
                        }
                    ) {
                        Text(if (isDownloadingOffline) "..." else "Simpan")
                    }
                }
            }

            if (uiState.safePlaces.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "Tiada zon selamat didaftarkan lagi.\nTekan '+ Tambah Zon' atau tekan lama mana-mana lokasi di atas Peta.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(uiState.safePlaces, key = { "${it.isPrivate}_${it.placeId}" }) { place ->
                        Card(shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = if (place.isPrivate) Icons.Default.Lock else Icons.Default.Group,
                                    contentDescription = null,
                                    tint = if (place.isPrivate) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(place.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                    Text(
                                        text = "${if (place.isPrivate) "Peribadi (Telefon)" else "Kongsi Keluarga"} • ${place.radiusMeters.roundToInt()}m",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                IconButton(onClick = { viewModel.deleteSafePlace(place) }) {
                                    Icon(Icons.Default.Delete, "Padam", tint = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
