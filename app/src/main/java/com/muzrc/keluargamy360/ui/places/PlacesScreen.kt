package com.muzrc.keluargamy360.ui.places

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.firebase.auth.FirebaseAuth
import com.muzrc.keluargamy360.ui.map.MapViewModel
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition
import org.json.JSONObject
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlacesScreen(viewModel: MapViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    var showAddDialog by remember { mutableStateOf(false) }
    var placeName by remember { mutableStateOf("") }
    var radiusMeters by remember { mutableFloatStateOf(150f) }
    var isPrivateZone by remember { mutableStateOf(false) }
    var offlineStatus by remember { mutableStateOf("Sedia untuk muat turun peta sekitar lokasi anda") }
    var isDownloadingOffline by remember { mutableStateOf(false) }

    val currentUserId = FirebaseAuth.getInstance().currentUser?.uid
    val myLocation = uiState.members.find { it.uid == currentUserId } ?: uiState.selectedMember

    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("Tambah Zon Selamat") },
            text = {
                Column {
                    Text(
                        text = "Lokasi: ${myLocation?.addressText ?: "Koordinat Semasa"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = placeName,
                        onValueChange = { placeName = it },
                        label = { Text("Nama Tempat (cth: Rumah, Pejabat)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(12.dp))

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
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 4.dp)
                    )

                    Spacer(modifier = Modifier.height(14.dp))
                    Text("Jejari Zon: ${radiusMeters.roundToInt()} meter", style = MaterialTheme.typography.labelMedium)
                    Slider(
                        value = radiusMeters,
                        onValueChange = { radiusMeters = it },
                        valueRange = 100f..500f,
                        steps = 7
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (myLocation != null && placeName.isNotBlank()) {
                            viewModel.addSafePlace(
                                name = placeName,
                                lat = myLocation.latitude,
                                lng = myLocation.longitude,
                                radiusMeters = radiusMeters,
                                isPrivate = isPrivateZone
                            )
                            placeName = ""
                            showAddDialog = false
                        }
                    },
                    enabled = myLocation != null && placeName.isNotBlank()
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
                onClick = { showAddDialog = true },
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
            // Kad Peta Luar Talian (Offline Map Cache)
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f)),
                modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp)
            ) {
                Row(
                    modifier = Modifier.padding(14.dp).fillMaxWidth(),
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
                                    override fun onCreate(region: org.maplibre.android.offline.OfflineRegion) {
                                        region.setDownloadState(org.maplibre.android.offline.OfflineRegion.STATE_ACTIVE)
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
                        text = "Tiada zon selamat didaftarkan lagi.
Tekan '+ Tambah Zon' untuk mula.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(uiState.safePlaces, key = { "${it.isPrivate}_${it.placeId}" }) { place ->
                        Card(shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(14.dp),
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
