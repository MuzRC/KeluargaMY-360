package com.muzrc.keluargamy360.ui.navigation

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.muzrc.keluargamy360.data.model.DecryptedMemberLocation
import com.muzrc.keluargamy360.ui.map.MapScreen
import com.muzrc.keluargamy360.ui.map.MapViewModel
import com.muzrc.keluargamy360.ui.map.MemberCard
import com.muzrc.keluargamy360.ui.places.PlacesScreen
import com.muzrc.keluargamy360.util.CrashAlertBus
import com.muzrc.keluargamy360.util.DeviceRingerHelper
import com.muzrc.keluargamy360.util.PrivateZoneLogHelper
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainDashboardScreen(
    mapViewModel: MapViewModel,
    onSignOut: () -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(2) }
    val uiState by mapViewModel.uiState.collectAsState()
    val crashDetected by CrashAlertBus.crashDetected.collectAsState()
    val context = LocalContext.current

    var actionTargetMember by remember { mutableStateOf<DecryptedMemberLocation?>(null) }
    var showEditNameDialog by remember { mutableStateOf(false) }
    var showLeaveFamilyDialog by remember { mutableStateOf(false) }
    var showClearPrivateLogDialog by remember { mutableStateOf(false) }
    var newDisplayName by remember { mutableStateOf("") }

    // Penapis tab Selamat: 0 = Log Keluarga (Firebase), 1 = Log Peribadi (.txt tempatan)
    var safetyLogMode by remember { mutableIntStateOf(0) }
    var privateLogs by remember { mutableStateOf(PrivateZoneLogHelper.readLogs(context)) }

    LaunchedEffect(selectedTab, safetyLogMode) {
        if (selectedTab == 3 && safetyLogMode == 1) {
            privateLogs = PrivateZoneLogHelper.readLogs(context)
        }
    }

    // Pengesan Impak Kenderaan: Kiraan undur 20 saat sebelum hantar SOS automatik
    if (crashDetected) {
        var secondsRemaining by remember { mutableIntStateOf(20) }

        LaunchedEffect(Unit) {
            DeviceRingerHelper.ringLoudlyFor15Seconds(context)
            while (secondsRemaining > 0) {
                delay(1000L)
                secondsRemaining--
            }
            if (CrashAlertBus.crashDetected.value) {
                CrashAlertBus.dismissCrashCountdown()
                DeviceRingerHelper.stopRinging()
                mapViewModel.triggerSos("IMPAK KENDERAAN DIKESAN")
            }
        }

        AlertDialog(
            onDismissRequest = {},
            icon = { Icon(Icons.Default.CarCrash, null, tint = Color.Red, modifier = Modifier.size(44.dp)) },
            title = {
                Text(
                    "IMPAK KUAT DIKESAN!",
                    fontWeight = FontWeight.Bold,
                    color = Color.Red,
                    textAlign = TextAlign.Center
                )
            },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "Amaran kecemasan SOS automatik akan dihantar kepada keluarga dalam:",
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "$secondsRemaining saat",
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.Red
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        CrashAlertBus.dismissCrashCountdown()
                        DeviceRingerHelper.stopRinging()
                        Toast.makeText(context, "Amaran impak dibatalkan.", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("BATAL — SAYA SELAMAT", fontWeight = FontWeight.Bold)
                }
            }
        )
    }

    // Dialog Tindakan Pantas untuk Ahli (Sejarah Laluan 24 Jam, Deringkan Telefon, Buang Ahli)
    actionTargetMember?.let { member ->
        val isOwner = uiState.familyGroup?.ownerId == mapViewModel.currentUserId
        val isNotMe = member.uid != mapViewModel.currentUserId

        AlertDialog(
            onDismissRequest = { actionTargetMember = null },
            title = { Text(member.name, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(member.addressText, style = MaterialTheme.typography.bodySmall)

                    FilledTonalButton(
                        onClick = {
                            actionTargetMember = null
                            mapViewModel.load24HourRoute(member)
                            selectedTab = 2
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Timeline, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Lihat Sejarah Laluan 24 Jam")
                    }

                    if (isNotMe) {
                        FilledTonalButton(
                            onClick = {
                                actionTargetMember = null
                                mapViewModel.ringMemberDevice(member)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.NotificationsActive, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Deringkan Telefon (Walaupun Senyap)")
                        }
                    }

                    if (isOwner && isNotMe) {
                        OutlinedButton(
                            onClick = {
                                actionTargetMember = null
                                mapViewModel.removeMember(member)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.PersonRemove, null, tint = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.width(8.dp))
                            Text("Keluarkan dari Keluarga", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { actionTargetMember = null }) { Text("Tutup") }
            }
        )
    }

    if (showEditNameDialog) {
        AlertDialog(
            onDismissRequest = { showEditNameDialog = false },
            title = { Text("Tukar Nama Paparan") },
            text = {
                OutlinedTextField(
                    value = newDisplayName,
                    onValueChange = { newDisplayName = it },
                    label = { Text("Nama Paparan Baharu") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    if (newDisplayName.isNotBlank()) {
                        mapViewModel.updateMyDisplayName(newDisplayName)
                        showEditNameDialog = false
                    }
                }) { Text("Simpan") }
            },
            dismissButton = {
                TextButton(onClick = { showEditNameDialog = false }) { Text("Batal") }
            }
        )
    }

    if (showLeaveFamilyDialog) {
        AlertDialog(
            onDismissRequest = { showLeaveFamilyDialog = false },
            title = { Text("Keluar Kumpulan Keluarga?") },
            text = { Text("Lokasi anda tidak lagi dikongsi dengan kumpulan ini sehingga anda menyertai semula menggunakan kod jemputan.") },
            confirmButton = {
                Button(
                    onClick = {
                        showLeaveFamilyDialog = false
                        mapViewModel.leaveCurrentFamily(onLeftSuccess = onSignOut)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("Keluar Kumpulan") }
            },
            dismissButton = {
                TextButton(onClick = { showLeaveFamilyDialog = false }) { Text("Batal") }
            }
        )
    }

    if (showClearPrivateLogDialog) {
        AlertDialog(
            onDismissRequest = { showClearPrivateLogDialog = false },
            title = { Text("Padam Semua Log Peribadi?") },
            text = { Text("Fail log_zon_peribadi.txt dalam telefon anda akan dikosongkan sepenuhnya.") },
            confirmButton = {
                Button(
                    onClick = {
                        PrivateZoneLogHelper.clearLogs(context)
                        privateLogs = emptyList()
                        showClearPrivateLogDialog = false
                        Toast.makeText(context, "Log zon peribadi dikosongkan.", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("Padam") }
            },
            dismissButton = {
                TextButton(onClick = { showClearPrivateLogDialog = false }) { Text("Batal") }
            }
        )
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.People, contentDescription = "Ahli") },
                    label = { Text("Ahli") }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Default.Place, contentDescription = "Zon") },
                    label = { Text("Zon") }
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = { Icon(Icons.Default.Explore, contentDescription = "Peta") },
                    label = { Text("Peta", fontWeight = FontWeight.Bold) }
                )
                NavigationBarItem(
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 },
                    icon = { Icon(Icons.Default.CheckCircle, contentDescription = "Selamat") },
                    label = { Text("Selamat") }
                )
                NavigationBarItem(
                    selected = selectedTab == 4,
                    onClick = { selectedTab = 4 },
                    icon = { Icon(Icons.Default.Settings, contentDescription = "Tetapan") },
                    label = { Text("Tetapan") }
                )
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            when (selectedTab) {
                0 -> {
                    Scaffold(
                        topBar = {
                            TopAppBar(
                                title = {
                                    Column {
                                        Text("Senarai Ahli Keluarga", fontWeight = FontWeight.Bold)
                                        Text(
                                            "Tekan kad ahli untuk laluan 24j & deringan",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            )
                        }
                    ) { pad ->
                        LazyColumn(
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxSize().padding(pad)
                        ) {
                            items(uiState.members, key = { it.uid }) { member ->
                                MemberCard(
                                    member = member,
                                    isSelected = member.uid == uiState.selectedMember?.uid,
                                    onClick = { actionTargetMember = member }
                                )
                            }
                        }
                    }
                }
                1 -> PlacesScreen(viewModel = mapViewModel)
                2 -> MapScreen(
                    viewModel = mapViewModel,
                    onNavigateToPlaces = { selectedTab = 1 },
                    onSignOut = onSignOut
                )
                3 -> {
                    // Tab Kanan 1: Daftar Masuk Selamat & Dwi-Log (Keluarga vs Peribadi .txt)
                    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text("Daftar Masuk Keselamatan", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text(
                                    "Maklumkan semua ahli keluarga bahawa anda selamat di lokasi semasa.",
                                    style = MaterialTheme.typography.bodySmall,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(vertical = 8.dp)
                                )
                                Button(
                                    onClick = { mapViewModel.sendSafeCheckIn() },
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth().height(48.dp)
                                ) {
                                    Icon(Icons.Default.CheckCircle, null)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Hantar Status 'Saya Selamat'", fontWeight = FontWeight.Bold)
                                }
                            }
                        }

                        Spacer(Modifier.height(14.dp))

                        // Penukar dua sumber log tanpa bercampur
                        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                            SegmentedButton(
                                selected = safetyLogMode == 0,
                                onClick = { safetyLogMode = 0 },
                                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                                icon = { Icon(Icons.Default.Groups, null, modifier = Modifier.size(18.dp)) }
                            ) {
                                Text("Log Keluarga", fontWeight = FontWeight.SemiBold)
                            }
                            SegmentedButton(
                                selected = safetyLogMode == 1,
                                onClick = {
                                    safetyLogMode = 1
                                    privateLogs = PrivateZoneLogHelper.readLogs(context)
                                },
                                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                                icon = { Icon(Icons.Default.Lock, null, modifier = Modifier.size(18.dp)) }
                            ) {
                                Text("Log Peribadi (.txt)", fontWeight = FontWeight.SemiBold)
                            }
                        }

                        Spacer(Modifier.height(10.dp))

                        if (safetyLogMode == 0) {
                            // 1. LOG AKTIVITI KELUARGA (FIREBASE)
                            Text("Log Aktiviti Keluarga", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(8.dp))

                            if (uiState.activityLogs.isEmpty()) {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text("Belum ada rekod aktiviti keluarga hari ini.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            } else {
                                val formatter = remember { SimpleDateFormat("dd MMM, hh:mm a", Locale("ms", "MY")) }
                                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
                                    items(uiState.activityLogs, key = { it.id }) { log ->
                                        val icon = when (log.type) {
                                            "SOS" -> Icons.Default.Warning
                                            "CHECKIN" -> Icons.Default.CheckCircle
                                            "BATTERY" -> Icons.Default.BatteryAlert
                                            "SPEED" -> Icons.Default.Speed
                                            "GEOFENCE" -> Icons.Default.Place
                                            "RING" -> Icons.Default.NotificationsActive
                                            else -> Icons.Default.Info
                                        }
                                        Card(shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                                            Row(
                                                modifier = Modifier.padding(12.dp).fillMaxWidth(),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
                                                Spacer(Modifier.width(12.dp))
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(log.message, style = MaterialTheme.typography.bodyMedium)
                                                    Text(
                                                        formatter.format(Date(log.timestamp)),
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        } else {
                            // 2. LOG ZON PERIBADI TEMPATAN (.TXT DALAM TELEFON)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Log Zon Peribadi (.txt)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                    Text(
                                        "Disimpan dalam telefon sahaja (100% rahsia)",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color(0xFF2E7D32)
                                    )
                                }
                                Row {
                                    IconButton(onClick = {
                                        privateLogs = PrivateZoneLogHelper.readLogs(context)
                                        Toast.makeText(context, "Log disegar semula.", Toast.LENGTH_SHORT).show()
                                    }) {
                                        Icon(Icons.Default.Refresh, contentDescription = "Segar semula")
                                    }
                                    IconButton(onClick = {
                                        PrivateZoneLogHelper.exportToDownloads(context)
                                            .onSuccess { fileName ->
                                                Toast.makeText(context, "Disimpan ke Downloads/$fileName", Toast.LENGTH_LONG).show()
                                            }
                                            .onFailure { err ->
                                                Toast.makeText(context, err.message ?: "Gagal eksport.", Toast.LENGTH_SHORT).show()
                                            }
                                    }) {
                                        Icon(Icons.Default.Download, contentDescription = "Eksport .txt", tint = MaterialTheme.colorScheme.primary)
                                    }
                                    if (privateLogs.isNotEmpty()) {
                                        IconButton(onClick = { showClearPrivateLogDialog = true }) {
                                            Icon(Icons.Default.DeleteOutline, contentDescription = "Padam Log", tint = MaterialTheme.colorScheme.error)
                                        }
                                    }
                                }
                            }

                            Spacer(Modifier.height(6.dp))

                            if (privateLogs.isEmpty()) {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text(
                                        "Belum ada rekod masuk/keluar zon peribadi dalam log_zon_peribadi.txt.",
                                        textAlign = TextAlign.Center,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            } else {
                                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
                                    items(privateLogs) { item ->
                                        val isEnter = item.message.startsWith("[MASUK]")
                                        Card(
                                            shape = RoundedCornerShape(12.dp),
                                            colors = CardDefaults.cardColors(
                                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                                            ),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(12.dp).fillMaxWidth(),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Icon(
                                                    imageVector = if (isEnter) Icons.Default.Login else Icons.Default.Logout,
                                                    contentDescription = null,
                                                    tint = if (isEnter) Color(0xFF2E7D32) else Color(0xFFE65100)
                                                )
                                                Spacer(Modifier.width(12.dp))
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(item.message, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                                    if (item.timestampText.isNotBlank()) {
                                                        Text(
                                                            item.timestampText,
                                                            style = MaterialTheme.typography.labelSmall,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant
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
                }
                4 -> {
                    // Tab Kanan 2: Tetapan Profil, Had Keselamatan & Kumpulan
                    val code = uiState.familyGroup?.inviteCode ?: "-"
                    val me = uiState.members.find { it.uid == mapViewModel.currentUserId }
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text("Tetapan & Keselamatan", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

                        // Kad Profil & Nama Paparan
                        Card(shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.padding(14.dp).fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text("Nama Paparan Anda", style = MaterialTheme.typography.labelMedium)
                                    Text(me?.name ?: "Ahli Keluarga", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                }
                                TextButton(onClick = {
                                    newDisplayName = me?.name.orEmpty()
                                    showEditNameDialog = true
                                }) { Text("Tukar Nama") }
                            }
                        }

                        // Kad Kod Jemputan
                        Card(shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.padding(14.dp).fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text("Kod Jemputan (${uiState.familyGroup?.familyName ?: ""})", style = MaterialTheme.typography.labelMedium)
                                    Text(code, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                                }
                                IconButton(onClick = {
                                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    cm.setPrimaryClip(ClipData.newPlainText("Kod KeluargaMY360", code))
                                    Toast.makeText(context, "Kod jemputan disalin!", Toast.LENGTH_SHORT).show()
                                }) { Icon(Icons.Default.ContentCopy, "Salin Kod") }
                            }
                        }

                        // Kad Kawalan Keselamatan Pintar
                        Card(shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Keselamatan Pintar", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)

                                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                                    Text("Perkongsian GPS Masa Nyata", modifier = Modifier.weight(1f))
                                    Switch(checked = uiState.isTrackingEnabled, onCheckedChange = { mapViewModel.toggleTracking(it) })
                                }

                                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                                    Text("Amaran Automatik Bateri Lemah (< 15%)", modifier = Modifier.weight(1f))
                                    Switch(checked = uiState.lowBatteryAlertEnabled, onCheckedChange = { mapViewModel.toggleLowBatteryAlert(it) })
                                }

                                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                                    Text("Pengesan Impak Kenderaan (> 25 km/j)", modifier = Modifier.weight(1f))
                                    Switch(checked = uiState.crashDetectionEnabled, onCheckedChange = { mapViewModel.toggleCrashDetection(it) })
                                }

                                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                                    Text("Amaran Had Laju (${uiState.speedLimitKmh.roundToInt()} km/j)", modifier = Modifier.weight(1f))
                                    Switch(checked = uiState.speedAlertEnabled, onCheckedChange = { mapViewModel.toggleSpeedAlert(it) })
                                }

                                if (uiState.speedAlertEnabled) {
                                    Slider(
                                        value = uiState.speedLimitKmh,
                                        onValueChange = { mapViewModel.setSpeedLimit(it) },
                                        valueRange = 80f..140f,
                                        steps = 5
                                    )
                                }
                            }
                        }

                        OutlinedButton(
                            onClick = { showLeaveFamilyDialog = true },
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.GroupRemove, null, tint = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.width(8.dp))
                            Text("Keluar Kumpulan Keluarga", color = MaterialTheme.colorScheme.error)
                        }

                        OutlinedButton(
                            onClick = onSignOut,
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ExitToApp, null, tint = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.width(8.dp))
                            Text("Log Keluar Akaun", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
}
