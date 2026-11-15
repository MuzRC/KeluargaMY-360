package com.muzrc.keluargamy360.ui.map

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.muzrc.keluargamy360.data.model.DecryptedMemberLocation
import com.muzrc.keluargamy360.service.LocationTrackingService
import com.muzrc.keluargamy360.util.PermissionHelper
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    viewModel: MapViewModel,
    onNavigateToPlaces: () -> Unit,
    onSignOut: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var mapLibreMap by remember { mutableStateOf<MapLibreMap?>(null) }
    var showSosConfirmDialog by remember { mutableStateOf(false) }
    var showInviteDialog by remember { mutableStateOf(false) }
    var hasBatteryExemption by remember {
        mutableStateOf(PermissionHelper.isIgnoringBatteryOptimizations(context))
    }

    // Minta kebenaran lokasi & notifikasi apabila skrin dibuka
    val backgroundPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        LocationTrackingService.start(context)
    }

    val foregroundPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
        if (fineGranted) {
            LocationTrackingService.start(context)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                !PermissionHelper.hasBackgroundLocation(context)
            ) {
                backgroundPermissionLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }
        }
    }

    LaunchedEffect(Unit) {
        val permsToRequest = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (PermissionHelper.hasFineLocation(context)) {
            LocationTrackingService.start(context)
        } else {
            foregroundPermissionLauncher.launch(permsToRequest.toTypedArray())
        }
    }

    LaunchedEffect(uiState.statusMessage) {
        uiState.statusMessage?.let { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            viewModel.clearStatusMessage()
        }
    }

    // Kemas kini pin ahli keluarga pada peta setiap kali koordinat berubah
    LaunchedEffect(uiState.members, mapLibreMap) {
        val map = mapLibreMap ?: return@LaunchedEffect
        updateMembersOnMap(map, uiState.members)
    }

    // Gerakkan kamera peta kepada ahli yang dipilih
    LaunchedEffect(uiState.selectedMember, mapLibreMap) {
        val map = mapLibreMap ?: return@LaunchedEffect
        val target = uiState.selectedMember ?: return@LaunchedEffect
        map.animateCamera(
            CameraUpdateFactory.newLatLngZoom(
                LatLng(target.latitude, target.longitude),
                15.0
            )
        )
    }

    // Dialog Amaran SOS dari ahli lain
    uiState.activeSosAlert?.let { alertMsg ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissSosAlert() },
            icon = { Icon(Icons.Default.Warning, contentDescription = null, tint = Color.Red) },
            title = { Text("AMARAN KECEMASAN SOS!", fontWeight = FontWeight.Bold, color = Color.Red) },
            text = { Text(alertMsg) },
            confirmButton = {
                Button(
                    onClick = { viewModel.dismissSosAlert() },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Red)
                ) {
                    Text("Difahami")
                }
            }
        )
    }

    // Dialog Pengesahan Hantar SOS
    if (showSosConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showSosConfirmDialog = false },
            icon = { Icon(Icons.Default.Warning, contentDescription = null, tint = Color.Red) },
            title = { Text("Hantar Isyarat Kecemasan SOS?") },
            text = {
                Text("Amaran kecemasan berserta lokasi masa nyata anda akan dihantar serta-merta kepada semua ahli keluarga.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        showSosConfirmDialog = false
                        viewModel.triggerSos()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Red)
                ) {
                    Text("HANTAR SOS SEKARANG")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSosConfirmDialog = false }) {
                    Text("Batal")
                }
            }
        )
    }

    // Dialog Kod Jemputan Keluarga
    if (showInviteDialog) {
        val code = uiState.familyGroup?.inviteCode ?: "-"
        AlertDialog(
            onDismissRequest = { showInviteDialog = false },
            title = { Text(uiState.familyGroup?.familyName ?: "Kod Jemputan Keluarga") },
            text = {
                Column {
                    Text("Kongsi kod ini bersama PIN Keselamatan 6-digit anda kepada ahli keluarga untuk menyertai kumpulan:")
                    Spacer(modifier = Modifier.height(12.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = code,
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            IconButton(onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("Kod KeluargaMY360", code))
                                Toast.makeText(context, "Kod jemputan disalin!", Toast.LENGTH_SHORT).show()
                            }) {
                                Icon(Icons.Default.ContentCopy, contentDescription = "Salin Kod")
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showInviteDialog = false }) {
                    Text("Tutup")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = uiState.familyGroup?.familyName ?: "KeluargaMY360",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${uiState.members.size} ahli aktif • Tersulit E2EE",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                },
                actions = {
                    TextButton(onClick = { showInviteDialog = true }) {
                        Text("Kod Jemputan", fontWeight = FontWeight.SemiBold)
                    }
                    IconButton(onClick = onNavigateToPlaces) {
                        Icon(Icons.Default.Place, contentDescription = "Zon Selamat")
                    }
                    IconButton(onClick = onSignOut) {
                        Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = "Log Keluar")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Peringatan jika jimat bateri menyekat GPS latar belakang
            if (!hasBatteryExemption) {
                Surface(
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.BatteryAlert,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Matikan sekatan bateri supaya GPS tidak terhenti.",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = {
                            context.startActivity(PermissionHelper.createBatteryOptimizationIntent(context))
                            hasBatteryExemption = PermissionHelper.isIgnoringBatteryOptimizations(context)
                        }) {
                            Text("Benarkan", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // Bahagian Atas: Peta Interaktif MapLibre (OpenFreeMap - 100% Percuma)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.55f)
            ) {
                val mapView = remember {
                    MapView(context).apply {
                        getMapAsync { map ->
                            map.setStyle(
                                Style.Builder().fromUri("https://tiles.openfreemap.org/styles/liberty")
                            ) { style ->
                                setupMemberMapLayers(style)
                                mapLibreMap = map
                                updateMembersOnMap(map, uiState.members)
                            }
                        }
                    }
                }

                DisposableEffect(lifecycleOwner, mapView) {
                    val observer = LifecycleEventObserver { _, event ->
                        when (event) {
                            Lifecycle.Event.ON_START -> mapView.onStart()
                            Lifecycle.Event.ON_RESUME -> mapView.onResume()
                            Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                            Lifecycle.Event.ON_STOP -> mapView.onStop()
                            Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
                            else -> Unit
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose {
                        lifecycleOwner.lifecycle.removeObserver(observer)
                    }
                }

                AndroidView(
                    factory = { mapView },
                    modifier = Modifier.fillMaxSize()
                )

                // Butang Kecemasan SOS (Bawah Kiri Peta)
                ExtendedFloatingActionButton(
                    onClick = { showSosConfirmDialog = true },
                    containerColor = Color(0xFFD32F2F),
                    contentColor = Color.White,
                    icon = { Icon(Icons.Default.Warning, contentDescription = "SOS") },
                    text = { Text("SOS", fontWeight = FontWeight.Bold) },
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(16.dp)
                )

                // Butang Fokus Semula Kamera (Bawah Kanan Peta)
                SmallFloatingActionButton(
                    onClick = {
                        uiState.selectedMember?.let { target ->
                            mapLibreMap?.animateCamera(
                                CameraUpdateFactory.newLatLngZoom(
                                    LatLng(target.latitude, target.longitude),
                                    15.5
                                )
                            )
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(16.dp)
                ) {
                    Icon(Icons.Default.MyLocation, contentDescription = "Fokus Lokasi")
                }
            }

            // Bahagian Bawah: Senarai Kad Ahli Keluarga
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.45f),
                shadowElevation = 8.dp,
                shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Ahli Keluarga",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 10.dp)
                    )

                    if (uiState.members.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Menunggu isyarat GPS pertama daripada ahli keluarga...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(uiState.members, key = { it.uid }) { member ->
                                MemberCard(
                                    member = member,
                                    isSelected = member.uid == uiState.selectedMember?.uid,
                                    onClick = { viewModel.selectMember(member) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private const val MEMBERS_SOURCE_ID = "keluargamy360-members-source"
private const val MEMBERS_CIRCLE_LAYER_ID = "keluargamy360-members-circle"
private const val MEMBERS_LABEL_LAYER_ID = "keluargamy360-members-label"

private fun setupMemberMapLayers(style: Style) {
    if (style.getSource(MEMBERS_SOURCE_ID) == null) {
        style.addSource(GeoJsonSource(MEMBERS_SOURCE_ID))
    }
    if (style.getLayer(MEMBERS_CIRCLE_LAYER_ID) == null) {
        val circleLayer = CircleLayer(MEMBERS_CIRCLE_LAYER_ID, MEMBERS_SOURCE_ID).withProperties(
            PropertyFactory.circleRadius(12f),
            PropertyFactory.circleColor("#1565C0"),
            PropertyFactory.circleStrokeWidth(3f),
            PropertyFactory.circleStrokeColor("#FFFFFF")
        )
        style.addLayer(circleLayer)
    }
    if (style.getLayer(MEMBERS_LABEL_LAYER_ID) == null) {
        val symbolLayer = SymbolLayer(MEMBERS_LABEL_LAYER_ID, MEMBERS_SOURCE_ID).withProperties(
            PropertyFactory.textField("{name}"),
            PropertyFactory.textSize(13f),
            PropertyFactory.textOffset(arrayOf(0f, -1.8f)),
            PropertyFactory.textColor("#0D47A1"),
            PropertyFactory.textHaloColor("#FFFFFF"),
            PropertyFactory.textHaloWidth(2f)
        )
        style.addLayer(symbolLayer)
    }
}

private fun updateMembersOnMap(map: MapLibreMap, members: List<DecryptedMemberLocation>) {
    val style = map.style ?: return
    val source = style.getSourceAs<GeoJsonSource>(MEMBERS_SOURCE_ID) ?: return

    val features = members.map { m ->
        Feature.fromGeometry(Point.fromLngLat(m.longitude, m.latitude)).apply {
            addStringProperty("name", "${m.name} (${m.batteryLevel}%)")
        }
    }
    source.setGeoJson(FeatureCollection.fromFeatures(features))
}
