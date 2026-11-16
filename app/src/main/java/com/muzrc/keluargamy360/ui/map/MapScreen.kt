package com.muzrc.keluargamy360.ui.map

import android.Manifest
import android.animation.ValueAnimator
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.location.Location
import android.os.Build
import android.os.SystemClock
import android.view.animation.LinearInterpolator
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.*
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
import com.muzrc.keluargamy360.data.local.SecureStorage
import com.muzrc.keluargamy360.service.LocationTrackingService
import com.muzrc.keluargamy360.util.PermissionHelper
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

private const val SATELLITE_STYLE_JSON = """
{
  "version": 8,
  "sources": {
    "esri-satellite": {
      "type": "raster",
      "tiles": [
        "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}"
      ],
      "tileSize": 256,
      "attribution": "Esri, Maxar, Earthstar Geographics"
    }
  },
  "layers": [
    {
      "id": "esri-satellite-layer",
      "type": "raster",
      "source": "esri-satellite",
      "minzoom": 0,
      "maxzoom": 19
    }
  ]
}
"""

private fun buildMapStyle(mode: Int): Style.Builder {
    return when (mode) {
        1 -> Style.Builder().fromUri("https://tiles.openfreemap.org/styles/dark")
        2 -> Style.Builder().fromJson(SATELLITE_STYLE_JSON)
        else -> Style.Builder().fromUri("https://tiles.openfreemap.org/styles/liberty")
    }
}

private data class MemberAnimPlan(
    val startLat: Double,
    val startLng: Double,
    val targetLat: Double,
    val targetLng: Double,
    val durationMs: Long
)

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
    val secureStorage = remember { SecureStorage(context) }

    var mapLibreMap by remember { mutableStateOf<MapLibreMap?>(null) }
    var mapStyleMode by remember { mutableIntStateOf(0) }
    var isFullscreenMap by remember { mutableStateOf(false) }
    var showSosDialog by remember { mutableStateOf(false) }
    var showInviteDialog by remember { mutableStateOf(false) }
    var hideBatteryBanner by remember {
        mutableStateOf(secureStorage.isAutoStartConfigured())
    }
    var hasBatteryExemption by remember {
        mutableStateOf(PermissionHelper.isIgnoringBatteryOptimizations(context))
    }

    // Tekan butang Back telefon untuk keluar daripada mod peta skrin penuh
    BackHandler(enabled = isFullscreenMap) {
        isFullscreenMap = false
    }

    // Keadaan interpolasi masa nyata bagi setiap ahli
    val renderedCoords = remember { mutableMapOf<String, Pair<Double, Double>>() }
    val lastTargetCoords = remember { mutableMapOf<String, Pair<Double, Double>>() }
    val lastCoordArrivalMs = remember { mutableMapOf<String, Long>() }
    var activePinAnimator by remember { mutableStateOf<ValueAnimator?>(null) }
    var isCameraFollowing by remember { mutableStateOf(true) }

    val latestMembers by rememberUpdatedState(uiState.members)
    val latestSelectedUid by rememberUpdatedState(uiState.selectedMember?.uid)
    val latestIsFollowing by rememberUpdatedState(isCameraFollowing)

    val bgPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { if (uiState.isTrackingEnabled) LocationTrackingService.start(context) }

    val fgPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        if (perms[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            if (uiState.isTrackingEnabled) LocationTrackingService.start(context)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                !PermissionHelper.hasBackgroundLocation(context)
            ) {
                bgPermLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }
        }
    }

    LaunchedEffect(Unit) {
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (PermissionHelper.hasFineLocation(context)) {
            if (uiState.isTrackingEnabled) LocationTrackingService.start(context)
        } else {
            fgPermLauncher.launch(perms.toTypedArray())
        }
    }

    LaunchedEffect(uiState.statusMessage) {
        uiState.statusMessage?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            viewModel.clearStatusMessage()
        }
    }

    // Hanya cetuskan animasi apabila koordinat (lat, lng) berubah—elak terbatal bila alamat dikemas kini
    val coordSignature = remember(uiState.members) {
        uiState.members.map { "${it.uid}:${it.latitude}:${it.longitude}" }
    }

    DisposableEffect(coordSignature, mapLibreMap) {
        val map = mapLibreMap
        val targetMembers = latestMembers

        if (map != null && targetMembers.isNotEmpty()) {
            activePinAnimator?.cancel()

            val nowElapsed = SystemClock.elapsedRealtime()
            val distBuffer = FloatArray(1)

            val animPlans = targetMembers.associate { m ->
                val prevRendered = renderedCoords[m.uid]
                val prevTarget = lastTargetCoords[m.uid]
                val targetPair = m.latitude to m.longitude

                val isNewCoordinate = prevTarget == null || prevTarget != targetPair
                val prevArrival = lastCoordArrivalMs[m.uid] ?: nowElapsed
                val arrivalDeltaMs = if (isNewCoordinate) {
                    lastTargetCoords[m.uid] = targetPair
                    lastCoordArrivalMs[m.uid] = nowElapsed
                    (nowElapsed - prevArrival).coerceIn(2000L, 12000L)
                } else {
                    4000L
                }

                if (prevRendered == null) {
                    m.uid to MemberAnimPlan(m.latitude, m.longitude, m.latitude, m.longitude, 0L)
                } else {
                    Location.distanceBetween(
                        prevRendered.first,
                        prevRendered.second,
                        m.latitude,
                        m.longitude,
                        distBuffer
                    )
                    val distanceMeters = distBuffer[0]

                    when {
                        // Lompatan terlalu jauh (> 5 km) atau pegun (< 1.5 m): terus ke titik akhir
                        distanceMeters > 5000f || distanceMeters < 1.5f -> {
                            m.uid to MemberAnimPlan(m.latitude, m.longitude, m.latitude, m.longitude, 0L)
                        }
                        // Kelajuan diketahui: padankan tempoh luncuran dengan kelajuan sebenar kenderaan
                        m.speedKmh > 3f -> {
                            val speedMps = (m.speedKmh / 3.6f).coerceAtLeast(1f)
                            val physicsDurationMs = ((distanceMeters / speedMps) * 1000f).toLong()
                            val smoothDuration = maxOf(physicsDurationMs, arrivalDeltaMs).coerceIn(2500L, 10000L)
                            m.uid to MemberAnimPlan(
                                startLat = prevRendered.first,
                                startLng = prevRendered.second,
                                targetLat = m.latitude,
                                targetLng = m.longitude,
                                durationMs = smoothDuration
                            )
                        }
                        else -> {
                            val smoothDuration = arrivalDeltaMs.coerceIn(2500L, 8000L)
                            m.uid to MemberAnimPlan(
                                startLat = prevRendered.first,
                                startLng = prevRendered.second,
                                targetLat = m.latitude,
                                targetLng = m.longitude,
                                durationMs = smoothDuration
                            )
                        }
                    }
                }
            }

            val maxDuration = animPlans.values.maxOfOrNull { it.durationMs } ?: 0L

            if (maxDuration <= 0L) {
                targetMembers.forEach { m ->
                    renderedCoords[m.uid] = m.latitude to m.longitude
                }
                MapMarkerHelper.updateMembers(map, targetMembers)
                if (latestIsFollowing) {
                    targetMembers.find { it.uid == latestSelectedUid }?.let { sel ->
                        map.moveCamera(CameraUpdateFactory.newLatLng(LatLng(sel.latitude, sel.longitude)))
                    }
                }
            } else {
                val animator = ValueAnimator.ofLong(0L, maxDuration).apply {
                    duration = maxDuration
                    interpolator = LinearInterpolator()
                    addUpdateListener { anim ->
                        val elapsed = anim.animatedValue as Long
                        val currentList = latestMembers
                        var followedLatLng: LatLng? = null

                        val interpolatedList = currentList.map { m ->
                            val plan = animPlans[m.uid]
                            if (plan == null || plan.durationMs <= 0L) {
                                renderedCoords[m.uid] = m.latitude to m.longitude
                                if (m.uid == latestSelectedUid) {
                                    followedLatLng = LatLng(m.latitude, m.longitude)
                                }
                                m
                            } else {
                                val fraction = (elapsed.toFloat() / plan.durationMs.toFloat()).coerceIn(0f, 1f)
                                val curLat = plan.startLat + (plan.targetLat - plan.startLat) * fraction
                                val curLng = plan.startLng + (plan.targetLng - plan.startLng) * fraction
                                renderedCoords[m.uid] = curLat to curLng
                                if (m.uid == latestSelectedUid) {
                                    followedLatLng = LatLng(curLat, curLng)
                                }
                                m.copy(latitude = curLat, longitude = curLng)
                            }
                        }

                        MapMarkerHelper.updateMembers(map, interpolatedList)

                        // Kamera turut meluncur serentak dengan ikon ahli yang dipilih
                        if (latestIsFollowing) {
                            followedLatLng?.let { pos ->
                                map.moveCamera(CameraUpdateFactory.newLatLng(pos))
                            }
                        }
                    }
                    start()
                }
                activePinAnimator = animator
            }
        }

        onDispose {
            activePinAnimator?.cancel()
        }
    }

    LaunchedEffect(uiState.safePlaces, uiState.activeHistoryRoute, mapLibreMap) {
        val map = mapLibreMap ?: return@LaunchedEffect
        MapMarkerHelper.updateSafePlaces(map, uiState.safePlaces)
        MapMarkerHelper.updateHistoryRoute(map, uiState.activeHistoryRoute)
    }

    // Fokus kamera hanya apabila pengguna menukar ahli yang dipilih
    LaunchedEffect(uiState.selectedMember?.uid, mapLibreMap) {
        val map = mapLibreMap ?: return@LaunchedEffect
        val target = uiState.selectedMember ?: return@LaunchedEffect
        isCameraFollowing = true
        val currentPos = renderedCoords[target.uid] ?: (target.latitude to target.longitude)
        map.animateCamera(
            CameraUpdateFactory.newLatLngZoom(LatLng(currentPos.first, currentPos.second), 16.0),
            900
        )
    }

    uiState.activeSosAlert?.let { alertMsg ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissSosAlert() },
            icon = { Icon(Icons.Default.Warning, null, tint = Color.Red) },
            title = { Text("AMARAN KECEMASAN SOS!", fontWeight = FontWeight.Bold, color = Color.Red) },
            text = { Text(alertMsg) },
            confirmButton = {
                Button(
                    onClick = { viewModel.dismissSosAlert() },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Red)
                ) { Text("Difahami") }
            }
        )
    }

    if (showSosDialog) {
        AlertDialog(
            onDismissRequest = { showSosDialog = false },
            icon = { Icon(Icons.Default.Warning, null, tint = Color.Red) },
            title = { Text("Hantar Isyarat Kecemasan SOS?") },
            text = { Text("Amaran kecemasan berserta lokasi masa nyata anda akan dihantar kepada semua ahli keluarga.") },
            confirmButton = {
                Button(
                    onClick = { showSosDialog = false; viewModel.triggerSos() },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Red)
                ) { Text("HANTAR SOS SEKARANG") }
            },
            dismissButton = {
                TextButton(onClick = { showSosDialog = false }) { Text("Batal") }
            }
        )
    }

    if (showInviteDialog) {
        val code = uiState.familyGroup?.inviteCode ?: "-"
        AlertDialog(
            onDismissRequest = { showInviteDialog = false },
            title = { Text(uiState.familyGroup?.familyName ?: "Kod Jemputan") },
            text = {
                Column {
                    Text("Kongsi kod ini bersama PIN 6-digit kepada ahli keluarga:")
                    Spacer(Modifier.height(12.dp))
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
                            Text(code, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                            IconButton(onClick = {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("Kod KeluargaMY360", code))
                                Toast.makeText(context, "Kod jemputan disalin!", Toast.LENGTH_SHORT).show()
                            }) { Icon(Icons.Default.ContentCopy, "Salin") }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showInviteDialog = false }) { Text("Tutup") } }
        )
    }

    Scaffold(
        topBar = {
            if (!isFullscreenMap) {
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
                        TextButton(onClick = { showInviteDialog = true }) { Text("Kod Jemputan") }
                        IconButton(onClick = onSignOut) { Icon(Icons.AutoMirrored.Filled.ExitToApp, "Log Keluar") }
                    }
                )
            }
        }
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            if (!isFullscreenMap && !hideBatteryBanner) {
                Surface(color = MaterialTheme.colorScheme.tertiaryContainer, modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.BatteryAlert, null)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Aktifkan Auto-Lancar & Bateri supaya GPS tidak terhenti.",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
                        if (!hasBatteryExemption) {
                            TextButton(onClick = {
                                context.startActivity(PermissionHelper.createBatteryOptimizationIntent(context))
                                hasBatteryExemption = PermissionHelper.isIgnoringBatteryOptimizations(context)
                            }) { Text("Bateri", fontWeight = FontWeight.Bold) }
                        }
                        TextButton(onClick = {
                            Toast.makeText(
                                context,
                                "Sila hidupkan 'Auto-lancar / Benarkan aktiviti latar belakang' untuk KeluargaMY360.",
                                Toast.LENGTH_LONG
                            ).show()
                            PermissionHelper.openOemAutoStartSettings(context)
                            secureStorage.setAutoStartConfigured(true)
                            hideBatteryBanner = true
                        }) { Text("Auto-Lancar", fontWeight = FontWeight.Bold) }
                        IconButton(onClick = {
                            secureStorage.setAutoStartConfigured(true)
                            hideBatteryBanner = true
                        }) {
                            Icon(Icons.Default.Close, "Tutup", modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }

            uiState.historyOwnerName?.let { ownerName ->
                Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Laluan 24 Jam: $ownerName", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                        TextButton(onClick = { viewModel.clear24HourRoute() }) { Text("Tutup Laluan") }
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(if (isFullscreenMap) 1f else 0.55f)
            ) {
                val mapView = remember {
                    MapView(context).apply {
                        getMapAsync { map ->
                            // Hentikan auto-ikut jika pengguna seret peta secara manual
                            map.addOnCameraMoveStartedListener { reason ->
                                if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
                                    isCameraFollowing = false
                                }
                            }
                            map.setStyle(buildMapStyle(mapStyleMode)) { style ->
                                MapMarkerHelper.setupLayers(style)
                                mapLibreMap = map
                                MapMarkerHelper.updateMembers(map, uiState.members)
                                MapMarkerHelper.updateSafePlaces(map, uiState.safePlaces)
                                MapMarkerHelper.updateHistoryRoute(map, uiState.activeHistoryRoute)
                            }
                        }
                    }
                }

                DisposableEffect(lifecycleOwner, mapView) {
                    val observer = LifecycleEventObserver { _, event ->
                        when (event) {
                            Lifecycle.Event.ON_START -> mapView.onStart()
                            Lifecycle.Event.ON_RESUME -> {
                                mapView.onResume()
                                hasBatteryExemption = PermissionHelper.isIgnoringBatteryOptimizations(context)
                            }
                            Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                            Lifecycle.Event.ON_STOP -> mapView.onStop()
                            Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
                            else -> Unit
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                }

                AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())

                ExtendedFloatingActionButton(
                    onClick = { showSosDialog = true },
                    containerColor = Color(0xFFD32F2F),
                    contentColor = Color.White,
                    icon = { Icon(Icons.Default.Warning, "SOS") },
                    text = { Text("SOS", fontWeight = FontWeight.Bold) },
                    modifier = Modifier.align(Alignment.TopStart).padding(14.dp)
                )

                // Butang Kawalan Kanan Atas: Tukar Mod Peta & Skrin Penuh
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.align(Alignment.TopEnd).padding(14.dp)
                ) {
                    SmallFloatingActionButton(
                        onClick = {
                            mapStyleMode = (mapStyleMode + 1) % 3
                            val modeLabel = when (mapStyleMode) {
                                1 -> "Mod Peta: Gelap"
                                2 -> "Mod Peta: Satelit"
                                else -> "Mod Peta: Cerah"
                            }
                            Toast.makeText(context, modeLabel, Toast.LENGTH_SHORT).show()

                            mapLibreMap?.setStyle(buildMapStyle(mapStyleMode)) { style ->
                                MapMarkerHelper.setupLayers(style)
                                mapLibreMap?.let {
                                    MapMarkerHelper.updateMembers(it, uiState.members)
                                    MapMarkerHelper.updateSafePlaces(it, uiState.safePlaces)
                                    MapMarkerHelper.updateHistoryRoute(it, uiState.activeHistoryRoute)
                                }
                            }
                        }
                    ) {
                        val styleIcon = when (mapStyleMode) {
                            0 -> Icons.Default.DarkMode
                            1 -> Icons.Default.SatelliteAlt
                            else -> Icons.Default.LightMode
                        }
                        Icon(styleIcon, "Tukar Jenis Peta")
                    }

                    SmallFloatingActionButton(
                        onClick = { isFullscreenMap = !isFullscreenMap }
                    ) {
                        Icon(
                            imageVector = if (isFullscreenMap) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                            contentDescription = if (isFullscreenMap) "Keluar Skrin Penuh" else "Peta Skrin Penuh"
                        )
                    }
                }

                // Lencana ringkas ahli aktif semasa dalam mod peta skrin penuh
                if (isFullscreenMap) {
                    uiState.selectedMember?.let { sel ->
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                            shadowElevation = 6.dp,
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(start = 14.dp, bottom = 14.dp, end = 76.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.PersonPinCircle,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f, fill = false)) {
                                    Text(
                                        text = "${sel.name} • ${sel.batteryLevel}%",
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1
                                    )
                                    Text(
                                        text = sel.addressText,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }
                }

                SmallFloatingActionButton(
                    onClick = {
                        isCameraFollowing = true
                        uiState.selectedMember?.let { sel ->
                            val pos = renderedCoords[sel.uid] ?: (sel.latitude to sel.longitude)
                            mapLibreMap?.animateCamera(
                                CameraUpdateFactory.newLatLngZoom(LatLng(pos.first, pos.second), 16.0),
                                700
                            )
                        }
                    },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(14.dp)
                ) {
                    Icon(
                        imageVector = if (isCameraFollowing) Icons.Default.GpsFixed else Icons.Default.MyLocation,
                        contentDescription = "Fokus"
                    )
                }
            }

            AnimatedVisibility(
                visible = !isFullscreenMap,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
                modifier = Modifier.fillMaxWidth().weight(0.45f)
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    shadowElevation = 8.dp,
                    shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
                ) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Ahli Keluarga", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = if (uiState.isTrackingEnabled) "GPS Aktif" else "GPS Dijeda",
                                    style = MaterialTheme.typography.labelMedium
                                )
                                Spacer(Modifier.width(6.dp))
                                Switch(checked = uiState.isTrackingEnabled, onCheckedChange = { viewModel.toggleTracking(it) })
                            }
                        }

                        if (uiState.members.isEmpty()) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("Menunggu isyarat GPS pertama...", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        } else {
                            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxSize()) {
                                items(uiState.members, key = { it.uid }) { member ->
                                    MemberCard(
                                        member = member,
                                        isSelected = member.uid == uiState.selectedMember?.uid,
                                        onClick = {
                                            isCameraFollowing = true
                                            viewModel.selectMember(member)
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
