package com.muzrc.keluargamy360.ui.navigation

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.app.Activity
import android.location.Location
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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
import com.muzrc.keluargamy360.BuildConfig
import com.muzrc.keluargamy360.data.model.DecryptedMemberLocation
import com.muzrc.keluargamy360.ui.map.MapScreen
import com.muzrc.keluargamy360.ui.map.MapViewModel
import com.muzrc.keluargamy360.ui.map.MemberCard
import com.muzrc.keluargamy360.ui.places.PlacesScreen
import com.muzrc.keluargamy360.util.ApkDownloadState
import com.muzrc.keluargamy360.util.ApkUpdateInstaller
import com.muzrc.keluargamy360.util.AppBatteryLogHelper
import com.muzrc.keluargamy360.util.AppDiagnosticLogHelper
import com.muzrc.keluargamy360.util.DiagnosticCategory
import com.muzrc.keluargamy360.util.NotificationSoundHelper
import com.muzrc.keluargamy360.util.OfflineRouteQueueHelper
import com.muzrc.keluargamy360.util.AppUpdateHelper
import com.muzrc.keluargamy360.util.AppUpdateResult
import com.muzrc.keluargamy360.util.BatteryHelper
import com.muzrc.keluargamy360.util.CrashAlertBus
import com.muzrc.keluargamy360.util.DeviceRingerHelper
import com.muzrc.keluargamy360.util.PrivateZoneLogHelper
import com.muzrc.keluargamy360.util.RoadDistanceHelper
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
    val apkDownloadState by ApkUpdateInstaller.downloadState.collectAsState()
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val myLocation = remember(uiState.members, mapViewModel.currentUserId) {
        uiState.members.find { it.uid == mapViewModel.currentUserId }
    }

    var actionTargetMember by remember { mutableStateOf<DecryptedMemberLocation?>(null) }
    var nicknameTargetMember by remember { mutableStateOf<DecryptedMemberLocation?>(null) }
    var nicknameInput by remember { mutableStateOf("") }
    var showEditNameDialog by remember { mutableStateOf(false) }
    var showLeaveFamilyDialog by remember { mutableStateOf(false) }
    var showClearPrivateLogDialog by remember { mutableStateOf(false) }
    var newDisplayName by remember { mutableStateOf("") }

    // Status semakan kemas kini & pemasangan dalam aplikasi
    var isCheckingUpdate by remember { mutableStateOf(false) }
    var availableUpdate by remember { mutableStateOf<AppUpdateResult.UpdateAvailable?>(null) }
    var showInstallPermDialog by remember { mutableStateOf(false) }

    // Penapis tab Selamat: 0 = Keluarga, 1 = Zon (.txt), 2 = Bateri, 3 = Diagnostik & Perjalanan
    var safetyLogMode by remember { mutableIntStateOf(0) }
    var familyZoneOnlyFilter by remember { mutableStateOf(false) }
    var diagCategoryFilter by remember { mutableStateOf<DiagnosticCategory?>(null) }
    var privateLogs by remember { mutableStateOf(PrivateZoneLogHelper.readLogs(context)) }
    var appBatteryLogs by remember { mutableStateOf(AppBatteryLogHelper.readLogs(context)) }
    var batterySinceCharge by remember { mutableStateOf(AppBatteryLogHelper.getUsageSinceLastCharge(context)) }
    var diagnosticLogs by remember { mutableStateOf(AppDiagnosticLogHelper.readLogs(context, null)) }
    var todayTripSummary by remember { mutableStateOf(AppDiagnosticLogHelper.getTodayTripSummary(context)) }
    var offlineQueueCount by remember { mutableIntStateOf(OfflineRouteQueueHelper.peekAll(context).size) }

    // Tetapan Bunyi Notifikasi Tersuai (Zon Selamat & SOS)
    var geofenceSoundLabel by remember { mutableStateOf(NotificationSoundHelper.getGeofenceSoundLabel(context)) }
    var sosSoundLabel by remember { mutableStateOf(NotificationSoundHelper.getSosSoundLabel(context)) }
    var pickingSoundForSos by remember { mutableStateOf(false) }

    val ringtonePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            @Suppress("DEPRECATION")
            val pickedUri: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                result.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
            } else {
                result.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            }
            if (pickingSoundForSos) {
                NotificationSoundHelper.setSosSound(context, pickedUri)
                sosSoundLabel = NotificationSoundHelper.getSosSoundLabel(context)
                Toast.makeText(context, "Bunyi amaran SOS dikemas kini: $sosSoundLabel", Toast.LENGTH_SHORT).show()
            } else {
                NotificationSoundHelper.setGeofenceSound(context, pickedUri)
                geofenceSoundLabel = NotificationSoundHelper.getGeofenceSoundLabel(context)
                Toast.makeText(context, "Bunyi zon selamat dikemas kini: $geofenceSoundLabel", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val audioFilePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {
            }
            if (pickingSoundForSos) {
                NotificationSoundHelper.setSosSound(context, uri)
                sosSoundLabel = NotificationSoundHelper.getSosSoundLabel(context)
                Toast.makeText(context, "Fail audio SOS dipilih: $sosSoundLabel", Toast.LENGTH_SHORT).show()
            } else {
                NotificationSoundHelper.setGeofenceSound(context, uri)
                geofenceSoundLabel = NotificationSoundHelper.getGeofenceSoundLabel(context)
                Toast.makeText(context, "Fail audio zon selamat dipilih: $geofenceSoundLabel", Toast.LENGTH_SHORT).show()
            }
        }
    }

    LaunchedEffect(selectedTab, safetyLogMode, diagCategoryFilter) {
        if (selectedTab == 3) {
            when (safetyLogMode) {
                1 -> privateLogs = PrivateZoneLogHelper.readLogs(context)
                2 -> {
                    appBatteryLogs = AppBatteryLogHelper.readLogs(context)
                    batterySinceCharge = AppBatteryLogHelper.getUsageSinceLastCharge(context)
                }
                3 -> {
                    AppDiagnosticLogHelper.flushGpsDriftSummary(context)
                    diagnosticLogs = AppDiagnosticLogHelper.readLogs(context, diagCategoryFilter)
                    todayTripSummary = AppDiagnosticLogHelper.getTodayTripSummary(context)
                    offlineQueueCount = OfflineRouteQueueHelper.peekAll(context).size
                }
            }
        }
    }

    // Dialog Kebenaran Pasang Aplikasi dari Sumber Ini (Android 8+)
    if (showInstallPermDialog) {
        AlertDialog(
            onDismissRequest = { showInstallPermDialog = false },
            icon = { Icon(Icons.Default.Security, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp)) },
            title = { Text("Benarkan Pemasangan Kemas Kini", fontWeight = FontWeight.Bold, textAlign = TextAlign.Center) },
            text = {
                Text(
                    "Sila hidupkan kebenaran 'Benarkan daripada sumber ini' untuk KeluargaMY360 supaya kemas kini APK dapat dipasang terus tanpa melalui pelayar."
                )
            },
            confirmButton = {
                Button(onClick = {
                    showInstallPermDialog = false
                    ApkUpdateInstaller.openInstallPermissionSettings(context)
                }) {
                    Text("Buka Tetapan")
                }
            },
            dismissButton = {
                TextButton(onClick = { showInstallPermDialog = false }) { Text("Batal") }
            }
        )
    }

    // Dialog Kemas Kini Baharu Tersedia (Muat Turun Dalam App + Bar Kemajuan + Pasang Terus)
    availableUpdate?.let { update ->
        val isDownloading = apkDownloadState is ApkDownloadState.Downloading
        val readyState = apkDownloadState as? ApkDownloadState.ReadyToInstall
        val failedState = apkDownloadState as? ApkDownloadState.Failed

        AlertDialog(
            onDismissRequest = {
                if (!isDownloading) {
                    ApkUpdateInstaller.resetState()
                    availableUpdate = null
                }
            },
            icon = { Icon(Icons.Default.SystemUpdate, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp)) },
            title = {
                Text(
                    "Versi Baharu Tersedia (v${update.latestVersion})",
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Versi semasa anda: v${update.currentVersion}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "Fail terkini: ${update.apkFileName}",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = update.releaseNotes.take(400),
                        style = MaterialTheme.typography.bodySmall
                    )

                    when (val dl = apkDownloadState) {
                        is ApkDownloadState.Downloading -> {
                            Spacer(Modifier.height(4.dp))
                            if (dl.totalMb > 0f) {
                                LinearProgressIndicator(
                                    progress = { dl.progressPercent / 100f },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Text(
                                    text = String.format(
                                        Locale.US,
                                        "Memuat turun: %d%% (%.1f / %.1f MB)",
                                        dl.progressPercent,
                                        dl.downloadedMb,
                                        dl.totalMb
                                    ),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.SemiBold
                                )
                            } else {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                Text(
                                    text = "Menyediakan muat turun...",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        is ApkDownloadState.ReadyToInstall -> {
                            Text(
                                text = "Muat turun selesai! Tekan 'Pasang Sekarang' jika tetingkap pemasangan tidak muncul.",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF2E7D32),
                                fontWeight = FontWeight.Bold
                            )
                        }
                        is ApkDownloadState.Failed -> {
                            Text(
                                text = dl.reason,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        else -> Unit
                    }
                }
            },
            confirmButton = {
                if (readyState != null) {
                    Button(
                        onClick = {
                            if (!ApkUpdateInstaller.canInstallPackages(context)) {
                                showInstallPermDialog = true
                            } else {
                                ApkUpdateInstaller.launchInstaller(context, readyState.apkFile)
                            }
                        }
                    ) {
                        Icon(Icons.Default.InstallMobile, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Pasang Sekarang")
                    }
                } else {
                    Button(
                        enabled = !isDownloading,
                        onClick = {
                            if (!ApkUpdateInstaller.canInstallPackages(context)) {
                                showInstallPermDialog = true
                            } else {
                                ApkUpdateInstaller.startDownloadAndInstall(
                                    context = context,
                                    downloadUrl = update.downloadUrl,
                                    apkFileName = update.apkFileName
                                )
                            }
                        }
                    ) {
                        Icon(Icons.Default.Download, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            when {
                                isDownloading -> "Memuat Turun..."
                                failedState != null -> "Cuba Muat Turun Semula"
                                else -> "Muat Turun & Pasang"
                            }
                        )
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        ApkUpdateInstaller.resetState()
                        availableUpdate = null
                    }
                ) {
                    Text(if (isDownloading) "Sembunyi" else "Nanti")
                }
            }
        )
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

    // Dialog Tindakan Pantas untuk Ahli (Navigasi Waze/Maps, Laluan 7 Hari, Deringkan, Buang Ahli)
    actionTargetMember?.let { member ->
        val isOwner = uiState.familyGroup?.ownerId == mapViewModel.currentUserId
        val isNotMe = member.uid != mapViewModel.currentUserId
        var roadEst by remember(member.uid, myLocation?.latitude, myLocation?.longitude, member.latitude, member.longitude) {
            mutableStateOf(
                if (!isNotMe || myLocation == null) null
                else RoadDistanceHelper.computeStraightLine(
                    myLocation.latitude,
                    myLocation.longitude,
                    member.latitude,
                    member.longitude
                )
            )
        }
        LaunchedEffect(member.uid, myLocation?.latitude, myLocation?.longitude, member.latitude, member.longitude) {
            if (isNotMe && myLocation != null) {
                roadEst = RoadDistanceHelper.getRoadEstimate(
                    myLocation.latitude,
                    myLocation.longitude,
                    member.latitude,
                    member.longitude
                )
            }
        }
        val distanceText = roadEst?.formatCompact()

        AlertDialog(
            onDismissRequest = { actionTargetMember = null },
            title = {
                Column {
                    Text(member.name, fontWeight = FontWeight.Bold)
                    if (distanceText != null) {
                        Text(
                            text = distanceText,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(member.addressText, style = MaterialTheme.typography.bodySmall)

                    if (isNotMe) {
                        Button(
                            onClick = {
                                actionTargetMember = null
                                val uri = Uri.parse(
                                    "geo:${member.latitude},${member.longitude}?q=${member.latitude},${member.longitude}(${Uri.encode(member.name)})"
                                )
                                val navIntent = Intent(Intent.ACTION_VIEW, uri)
                                try {
                                    context.startActivity(Intent.createChooser(navIntent, "Pandu ke ${member.name}"))
                                } catch (_: Exception) {
                                    Toast.makeText(context, "Tiada aplikasi peta/navigasi dijumpai.", Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Navigation, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Pandu ke Lokasi (Waze / Maps)", fontWeight = FontWeight.Bold)
                        }
                    }

                    FilledTonalButton(
                        onClick = {
                            actionTargetMember = null
                            nicknameTargetMember = member
                            nicknameInput = uiState.memberNicknames[member.uid] ?: member.name
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Edit, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Ubah Nama Panggilan (Telefon Ini)")
                    }

                    FilledTonalButton(
                        onClick = {
                            actionTargetMember = null
                            mapViewModel.load24HourRoute(member, 0)
                            selectedTab = 2
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Timeline, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Lihat Sejarah Laluan (7 Hari)")
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

    nicknameTargetMember?.let { target ->
        AlertDialog(
            onDismissRequest = { nicknameTargetMember = null },
            title = { Text("Ubah Nama Panggilan") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Nama panggilan ini hanya dipaparkan pada telefon anda (tidak mengubah profil asal ahli).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = nicknameInput,
                        onValueChange = { nicknameInput = it },
                        label = { Text("Nama Panggilan (cth: Ayah, Ibu, Adik)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    mapViewModel.setMemberNickname(target.uid, nicknameInput)
                    nicknameTargetMember = null
                }) { Text("Simpan") }
            },
            dismissButton = {
                Row {
                    if (uiState.memberNicknames.containsKey(target.uid)) {
                        TextButton(onClick = {
                            mapViewModel.setMemberNickname(target.uid, "")
                            nicknameTargetMember = null
                        }) { Text("Set Semula") }
                    }
                    TextButton(onClick = { nicknameTargetMember = null }) { Text("Batal") }
                }
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
        val clearTitle = when (safetyLogMode) {
            2 -> "Padam Semua Log Bateri App?"
            3 -> "Padam Semua Log Diagnostik?"
            else -> "Padam Semua Log Peribadi?"
        }
        val clearDesc = when (safetyLogMode) {
            2 -> "Fail log_bateri_app.txt dalam telefon anda akan dikosongkan sepenuhnya."
            3 -> "Fail log_diagnostik_app.txt dalam telefon anda akan dikosongkan sepenuhnya."
            else -> "Fail log_zon_peribadi.txt dalam telefon anda akan dikosongkan sepenuhnya."
        }
        AlertDialog(
            onDismissRequest = { showClearPrivateLogDialog = false },
            title = { Text(clearTitle) },
            text = { Text(clearDesc) },
            confirmButton = {
                Button(
                    onClick = {
                        when (safetyLogMode) {
                            2 -> {
                                AppBatteryLogHelper.clearLogs(context)
                                appBatteryLogs = emptyList()
                                Toast.makeText(context, "Log bateri app dikosongkan.", Toast.LENGTH_SHORT).show()
                            }
                            3 -> {
                                AppDiagnosticLogHelper.clearLogs(context)
                                diagnosticLogs = emptyList()
                                Toast.makeText(context, "Log diagnostik dikosongkan.", Toast.LENGTH_SHORT).show()
                            }
                            else -> {
                                PrivateZoneLogHelper.clearLogs(context)
                                privateLogs = emptyList()
                                Toast.makeText(context, "Log zon peribadi dikosongkan.", Toast.LENGTH_SHORT).show()
                            }
                        }
                        showClearPrivateLogDialog = false
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
                                            "Tekan kad ahli untuk navigasi, laluan 24j & deringan",
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
                                    myLocation = myLocation,
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
                    // Tab Kanan 1: Daftar Masuk Selamat & 3 Sumber Log Terasing
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

                        Spacer(Modifier.height(12.dp))

                        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                            SegmentedButton(
                                selected = safetyLogMode == 0,
                                onClick = { safetyLogMode = 0 },
                                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 4),
                                icon = {}
                            ) {
                                Text("Keluarga", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, maxLines = 1)
                            }
                            SegmentedButton(
                                selected = safetyLogMode == 1,
                                onClick = {
                                    safetyLogMode = 1
                                    privateLogs = PrivateZoneLogHelper.readLogs(context)
                                },
                                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 4),
                                icon = {}
                            ) {
                                Text("Zon", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, maxLines = 1)
                            }
                            SegmentedButton(
                                selected = safetyLogMode == 2,
                                onClick = {
                                    safetyLogMode = 2
                                    appBatteryLogs = AppBatteryLogHelper.readLogs(context)
                                    batterySinceCharge = AppBatteryLogHelper.getUsageSinceLastCharge(context)
                                },
                                shape = SegmentedButtonDefaults.itemShape(index = 2, count = 4),
                                icon = {}
                            ) {
                                Text("Bateri", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, maxLines = 1)
                            }
                            SegmentedButton(
                                selected = safetyLogMode == 3,
                                onClick = {
                                    safetyLogMode = 3
                                    AppDiagnosticLogHelper.flushGpsDriftSummary(context)
                                    diagnosticLogs = AppDiagnosticLogHelper.readLogs(context, diagCategoryFilter)
                                    todayTripSummary = AppDiagnosticLogHelper.getTodayTripSummary(context)
                                    offlineQueueCount = OfflineRouteQueueHelper.peekAll(context).size
                                },
                                shape = SegmentedButtonDefaults.itemShape(index = 3, count = 4),
                                icon = {}
                            ) {
                                Text("Sistem", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, maxLines = 1)
                            }
                        }

                        Spacer(Modifier.height(10.dp))

                        when (safetyLogMode) {
                            0 -> {
                                val displayedFamilyLogs = remember(uiState.activityLogs, familyZoneOnlyFilter) {
                                    if (familyZoneOnlyFilter) {
                                        uiState.activityLogs.filter { it.type == "GEOFENCE" }
                                    } else {
                                        uiState.activityLogs
                                    }
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = if (familyZoneOnlyFilter) "Sejarah Zon Selamat Keluarga" else "Log Aktiviti Keluarga",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.weight(1f)
                                    )
                                    FilterChip(
                                        selected = familyZoneOnlyFilter,
                                        onClick = { familyZoneOnlyFilter = !familyZoneOnlyFilter },
                                        label = { Text("Zon Sahaja", style = MaterialTheme.typography.labelSmall) },
                                        leadingIcon = {
                                            Icon(
                                                Icons.Default.Place,
                                                contentDescription = null,
                                                modifier = Modifier.size(15.dp)
                                            )
                                        }
                                    )
                                }
                                Spacer(Modifier.height(6.dp))

                                if (displayedFamilyLogs.isEmpty()) {
                                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        Text(
                                            if (familyZoneOnlyFilter) {
                                                "Belum ada rekod masuk atau keluar zon selamat keluarga."
                                            } else {
                                                "Belum ada rekod aktiviti keluarga hari ini."
                                            },
                                            textAlign = TextAlign.Center,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                } else {
                                    val formatter = remember { SimpleDateFormat("dd MMM, hh:mm a", Locale("ms", "MY")) }
                                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
                                        items(displayedFamilyLogs, key = { it.id }) { log ->
                                            val isZoneEnter = log.type == "GEOFENCE" && (
                                                log.message.startsWith("[MASUK]") || log.message.contains("telah tiba")
                                            )
                                            val isZoneExit = log.type == "GEOFENCE" && (
                                                log.message.startsWith("[KELUAR]") || log.message.contains("telah keluar")
                                            )
                                            val icon = when {
                                                isZoneEnter -> Icons.Default.Login
                                                isZoneExit -> Icons.Default.Logout
                                                log.type == "SOS" -> Icons.Default.Warning
                                                log.type == "CHECKIN" -> Icons.Default.CheckCircle
                                                log.type == "BATTERY" -> Icons.Default.BatteryAlert
                                                log.type == "SPEED" -> Icons.Default.Speed
                                                log.type == "GEOFENCE" -> Icons.Default.Place
                                                log.type == "RING" -> Icons.Default.NotificationsActive
                                                else -> Icons.Default.Info
                                            }
                                            val iconTint = when {
                                                isZoneEnter -> Color(0xFF2E7D32)
                                                isZoneExit -> Color(0xFFE65100)
                                                log.type == "SOS" -> Color.Red
                                                else -> MaterialTheme.colorScheme.primary
                                            }
                                            Card(shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                                                Row(
                                                    modifier = Modifier.padding(12.dp).fillMaxWidth(),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Icon(icon, null, tint = iconTint)
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
                            }
                            1 -> {
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
                            2 -> {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("Log Bateri App (.txt)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                        Text(
                                            "Ditapis khas untuk CPU, GPS & data app ini sahaja",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    Row {
                                        IconButton(onClick = {
                                            val bat = BatteryHelper.getBatteryStatus(context)
                                            AppBatteryLogHelper.logAppUsageCheckpoint(
                                                context = context,
                                                reason = "Semakan Manual",
                                                profileName = if (uiState.isTrackingEnabled) "AKTIF" else "DIMATIKAN",
                                                phoneBatteryLevel = bat.level,
                                                isCharging = bat.isCharging
                                            )
                                            appBatteryLogs = AppBatteryLogHelper.readLogs(context)
                                            batterySinceCharge = AppBatteryLogHelper.getUsageSinceLastCharge(context)
                                            Toast.makeText(context, "Rekod bateri app disimpan!", Toast.LENGTH_SHORT).show()
                                        }) {
                                            Icon(Icons.Default.AddCircleOutline, contentDescription = "Rekod Sekarang", tint = MaterialTheme.colorScheme.primary)
                                        }
                                        IconButton(onClick = {
                                            appBatteryLogs = AppBatteryLogHelper.readLogs(context)
                                            batterySinceCharge = AppBatteryLogHelper.getUsageSinceLastCharge(context)
                                            Toast.makeText(context, "Log bateri disegar semula.", Toast.LENGTH_SHORT).show()
                                        }) {
                                            Icon(Icons.Default.Refresh, contentDescription = "Segar semula")
                                        }
                                        IconButton(onClick = {
                                            AppBatteryLogHelper.exportToDownloads(context)
                                                .onSuccess { fileName ->
                                                    Toast.makeText(context, "Disimpan ke Downloads/$fileName", Toast.LENGTH_LONG).show()
                                                }
                                                .onFailure { err ->
                                                    Toast.makeText(context, err.message ?: "Gagal eksport.", Toast.LENGTH_SHORT).show()
                                                }
                                        }) {
                                            Icon(Icons.Default.Download, contentDescription = "Eksport .txt", tint = MaterialTheme.colorScheme.primary)
                                        }
                                        if (appBatteryLogs.isNotEmpty()) {
                                            IconButton(onClick = { showClearPrivateLogDialog = true }) {
                                                Icon(Icons.Default.DeleteOutline, contentDescription = "Padam Log", tint = MaterialTheme.colorScheme.error)
                                            }
                                        }
                                    }
                                }

                                Spacer(Modifier.height(6.dp))

                                val sinceFmt = remember { SimpleDateFormat("dd MMM, hh:mm a", Locale("ms", "MY")) }
                                Card(
                                    shape = RoundedCornerShape(14.dp),
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.65f)
                                    ),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                "Jumlah Sejak Kali Terakhir Dicas",
                                                style = MaterialTheme.typography.labelLarge,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onSecondaryContainer
                                            )
                                            Text(
                                                if (batterySinceCharge.isCurrentlyCharging) {
                                                    "Sedang Dicas (${batterySinceCharge.currentBatteryPercent}%)"
                                                } else {
                                                    "Sejak ${sinceFmt.format(Date(batterySinceCharge.sinceTimestampMs))}"
                                                },
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }

                                        Spacer(Modifier.height(8.dp))

                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    "Digunakan App Ini",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                                val pctText = batterySinceCharge.appUsedPercentEstimate?.let {
                                                    String.format(Locale.US, " (~%.2f%%)", it)
                                                } ?: ""
                                                Text(
                                                    String.format(Locale.US, "~%.2f mAh%s", batterySinceCharge.appUsedMah, pctText),
                                                    style = MaterialTheme.typography.titleMedium,
                                                    fontWeight = FontWeight.Bold,
                                                    color = MaterialTheme.colorScheme.primary
                                                )
                                            }

                                            Column(horizontalAlignment = Alignment.End) {
                                                Text(
                                                    "Bateri Telefon Turun",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                                Text(
                                                    if (batterySinceCharge.isCurrentlyCharging) {
                                                        "0% (Dicas)"
                                                    } else {
                                                        "${batterySinceCharge.phoneBatteryUsedPercent}% (${batterySinceCharge.startBatteryPercent}% → ${batterySinceCharge.currentBatteryPercent}%)"
                                                    },
                                                    style = MaterialTheme.typography.titleMedium,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        }

                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            "Aktiviti sejak cabut cas: ${batterySinceCharge.totalGpsFixes}x semakan GPS • ${batterySinceCharge.totalUploads}x hantar lokasi",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }

                                Spacer(Modifier.height(8.dp))

                                if (appBatteryLogs.isEmpty()) {
                                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        Text(
                                            "Belum ada rekod. Rekod dijana automatik setiap 30 minit, perubahan 5% bateri, atau pertukaran mod GPS.",
                                            textAlign = TextAlign.Center,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                } else {
                                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
                                        items(appBatteryLogs) { item ->
                                            Card(
                                                shape = RoundedCornerShape(12.dp),
                                                colors = CardDefaults.cardColors(
                                                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                                                ),
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Column(modifier = Modifier.padding(12.dp).fillMaxWidth()) {
                                                    Text(
                                                        item.summary,
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        fontWeight = FontWeight.SemiBold
                                                    )
                                                    if (item.detail.isNotBlank()) {
                                                        Text(
                                                            item.detail,
                                                            style = MaterialTheme.typography.bodySmall,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                            modifier = Modifier.padding(top = 2.dp)
                                                        )
                                                    }
                                                    if (item.timestampText.isNotBlank()) {
                                                        Text(
                                                            item.timestampText,
                                                            style = MaterialTheme.typography.labelSmall,
                                                            color = MaterialTheme.colorScheme.primary,
                                                            modifier = Modifier.padding(top = 4.dp)
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            3 -> {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("Log Diagnostik & Perjalanan (.txt)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                        Text(
                                            "Rangkaian: ${AppDiagnosticLogHelper.detectNetworkMode(context)} • Barisan: $offlineQueueCount titik",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    Row {
                                        IconButton(onClick = {
                                            AppDiagnosticLogHelper.logTripCheckpointNow(context)
                                            diagnosticLogs = AppDiagnosticLogHelper.readLogs(context, diagCategoryFilter)
                                            todayTripSummary = AppDiagnosticLogHelper.getTodayTripSummary(context)
                                            Toast.makeText(context, "Ringkasan perjalanan direkod!", Toast.LENGTH_SHORT).show()
                                        }) {
                                            Icon(Icons.Default.AddCircleOutline, contentDescription = "Rekod Perjalanan", tint = MaterialTheme.colorScheme.primary)
                                        }
                                        IconButton(onClick = {
                                            AppDiagnosticLogHelper.flushGpsDriftSummary(context)
                                            diagnosticLogs = AppDiagnosticLogHelper.readLogs(context, diagCategoryFilter)
                                            todayTripSummary = AppDiagnosticLogHelper.getTodayTripSummary(context)
                                            offlineQueueCount = OfflineRouteQueueHelper.peekAll(context).size
                                            Toast.makeText(context, "Log sistem disegar semula.", Toast.LENGTH_SHORT).show()
                                        }) {
                                            Icon(Icons.Default.Refresh, contentDescription = "Segar semula")
                                        }
                                        IconButton(onClick = {
                                            AppDiagnosticLogHelper.exportToDownloads(context)
                                                .onSuccess { fileName ->
                                                    Toast.makeText(context, "Disimpan ke Downloads/$fileName", Toast.LENGTH_LONG).show()
                                                }
                                                .onFailure { err ->
                                                    Toast.makeText(context, err.message ?: "Gagal eksport.", Toast.LENGTH_SHORT).show()
                                                }
                                        }) {
                                            Icon(Icons.Default.Download, contentDescription = "Eksport .txt", tint = MaterialTheme.colorScheme.primary)
                                        }
                                        if (diagnosticLogs.isNotEmpty()) {
                                            IconButton(onClick = { showClearPrivateLogDialog = true }) {
                                                Icon(Icons.Default.DeleteOutline, contentDescription = "Padam Log", tint = MaterialTheme.colorScheme.error)
                                            }
                                        }
                                    }
                                }

                                Spacer(Modifier.height(6.dp))

                                // Kad Ringkasan Perjalanan & Jarak Pemanduan Hari Ini
                                Card(
                                    shape = RoundedCornerShape(14.dp),
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.65f)
                                    ),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                "Perjalanan & Jarak Hari Ini",
                                                style = MaterialTheme.typography.labelLarge,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onSecondaryContainer
                                            )
                                            Text(
                                                "${todayTripSummary.tripCount}x perjalanan • ${todayTripSummary.movingSeconds / 60L} min bergerak",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                        Spacer(Modifier.height(6.dp))
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Column {
                                                Text("Jumlah Jarak", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                Text(
                                                    String.format(Locale.US, "%.2f km", todayTripSummary.distanceKm),
                                                    style = MaterialTheme.typography.titleMedium,
                                                    fontWeight = FontWeight.Bold,
                                                    color = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                            Column(horizontalAlignment = Alignment.End) {
                                                Text("Kelajuan Maksimum", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                Text(
                                                    "${todayTripSummary.maxSpeedKmh.roundToInt()} km/j",
                                                    style = MaterialTheme.typography.titleMedium,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        }
                                    }
                                }

                                Spacer(Modifier.height(6.dp))

                                // Penapis kategori 4 log diagnostik
                                LazyRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    item {
                                        FilterChip(
                                            selected = diagCategoryFilter == null,
                                            onClick = {
                                                diagCategoryFilter = null
                                                diagnosticLogs = AppDiagnosticLogHelper.readLogs(context, null)
                                            },
                                            label = { Text("Semua", style = MaterialTheme.typography.labelSmall) }
                                        )
                                    }
                                    items(DiagnosticCategory.entries) { cat ->
                                        FilterChip(
                                            selected = diagCategoryFilter == cat,
                                            onClick = {
                                                diagCategoryFilter = cat
                                                diagnosticLogs = AppDiagnosticLogHelper.readLogs(context, cat)
                                            },
                                            label = { Text(cat.label, style = MaterialTheme.typography.labelSmall) }
                                        )
                                    }
                                }

                                Spacer(Modifier.height(6.dp))

                                if (diagnosticLogs.isEmpty()) {
                                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        Text(
                                            "Belum ada rekod dalam kategori ini. Rekod dijana automatik semasa servis berjalan, muat naik gagal/pulih, atau pergerakan dikesan.",
                                            textAlign = TextAlign.Center,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                } else {
                                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
                                        items(diagnosticLogs) { item ->
                                            val catIcon = when (item.category) {
                                                DiagnosticCategory.SYNC -> Icons.Default.SyncProblem
                                                DiagnosticCategory.SERVIS -> Icons.Default.HealthAndSafety
                                                DiagnosticCategory.GPS -> Icons.Default.GpsFixed
                                                DiagnosticCategory.TRIP -> Icons.Default.DirectionsCar
                                            }
                                            val catColor = when (item.category) {
                                                DiagnosticCategory.SYNC -> Color(0xFFE65100)
                                                DiagnosticCategory.SERVIS -> Color(0xFF1565C0)
                                                DiagnosticCategory.GPS -> Color(0xFF6A1B9A)
                                                DiagnosticCategory.TRIP -> Color(0xFF2E7D32)
                                            }
                                            Card(
                                                shape = RoundedCornerShape(12.dp),
                                                colors = CardDefaults.cardColors(
                                                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                                                ),
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Row(
                                                    modifier = Modifier.padding(12.dp).fillMaxWidth(),
                                                    verticalAlignment = Alignment.Top
                                                ) {
                                                    Icon(catIcon, null, tint = catColor, modifier = Modifier.padding(top = 2.dp))
                                                    Spacer(Modifier.width(10.dp))
                                                    Column(modifier = Modifier.weight(1f)) {
                                                        Text(
                                                            "[${item.category.tag}] ${item.title}",
                                                            style = MaterialTheme.typography.bodyMedium,
                                                            fontWeight = FontWeight.SemiBold
                                                        )
                                                        if (item.detail.isNotBlank()) {
                                                            Text(
                                                                item.detail,
                                                                style = MaterialTheme.typography.bodySmall,
                                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                                modifier = Modifier.padding(top = 2.dp)
                                                            )
                                                        }
                                                        if (item.timestampText.isNotBlank()) {
                                                            Text(
                                                                item.timestampText,
                                                                style = MaterialTheme.typography.labelSmall,
                                                                color = catColor,
                                                                modifier = Modifier.padding(top = 4.dp)
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
                }
                4 -> {
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

                        Card(shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.padding(14.dp).fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Nama Paparan Akaun Anda", style = MaterialTheme.typography.labelMedium)
                                    Text(me?.name ?: "Ahli Keluarga", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                }
                                TextButton(onClick = {
                                    newDisplayName = me?.name.orEmpty()
                                    showEditNameDialog = true
                                }) { Text("Tukar Nama") }
                            }
                        }

                        // Kad Nama Panggilan Peribadi Ahli Keluarga (Real-Time)
                        if (uiState.members.isNotEmpty()) {
                            Card(shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                                Column(
                                    modifier = Modifier.padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        "Nama Panggilan Ahli (Telefon Ini)",
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        "Tetapkan gelaran peribadi seperti Ayah, Ibu atau Abang. Perubahan terus dikemas kini secara masa nyata.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    uiState.members.forEach { member ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = member.name,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    fontWeight = FontWeight.SemiBold
                                                )
                                                if (uiState.memberNicknames.containsKey(member.uid)) {
                                                    Text(
                                                        text = "Nama panggilan peribadi aktif",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.primary
                                                    )
                                                }
                                            }
                                            TextButton(onClick = {
                                                nicknameTargetMember = member
                                                nicknameInput = uiState.memberNicknames[member.uid] ?: member.name
                                            }) {
                                                Text("Ubah Gelaran")
                                            }
                                        }
                                    }
                                }
                            }
                        }

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

                        // Kad Tetapan Bunyi Notifikasi Tersuai (Nada Sistem / Fail Audio MP3)
                        Card(shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.NotificationsActive, null, tint = MaterialTheme.colorScheme.primary)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Bunyi Notifikasi Tersuai", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                }

                                // 1. Bunyi Zon Selamat (Masuk / Keluar Zon)
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text("Zon Selamat (Masuk / Keluar Zon)", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        text = "Semasa: $geofenceSoundLabel",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                        FilledTonalButton(
                                            onClick = {
                                                pickingSoundForSos = false
                                                val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                                                    putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION)
                                                    putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Pilih Bunyi Zon Selamat")
                                                    putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
                                                    putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, NotificationSoundHelper.getGeofenceSoundUri(context))
                                                }
                                                ringtonePickerLauncher.launch(intent)
                                            },
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Text("Nada Sistem", style = MaterialTheme.typography.labelSmall)
                                        }
                                        OutlinedButton(
                                            onClick = {
                                                pickingSoundForSos = false
                                                audioFilePickerLauncher.launch(arrayOf("audio/*"))
                                            },
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Text("Fail MP3/Audio", style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                }

                                HorizontalDivider()

                                // 2. Bunyi Amaran Kecemasan SOS
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text("Amaran Kecemasan SOS", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        text = "Semasa: $sosSoundLabel",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                        FilledTonalButton(
                                            onClick = {
                                                pickingSoundForSos = true
                                                val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                                                    putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
                                                    putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Pilih Bunyi Amaran SOS")
                                                    putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                                                    putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, NotificationSoundHelper.getSosSoundUri(context))
                                                }
                                                ringtonePickerLauncher.launch(intent)
                                            },
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Text("Nada Penggera", style = MaterialTheme.typography.labelSmall)
                                        }
                                        OutlinedButton(
                                            onClick = {
                                                pickingSoundForSos = true
                                                audioFilePickerLauncher.launch(arrayOf("audio/*"))
                                            },
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Text("Fail MP3/Audio", style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                }
                            }
                        }

                        Card(shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.padding(14.dp).fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Versi Aplikasi", style = MaterialTheme.typography.labelMedium)
                                    Text(
                                        "KeluargaMY360 v${BuildConfig.VERSION_NAME}",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                FilledTonalButton(
                                    enabled = !isCheckingUpdate,
                                    onClick = {
                                        isCheckingUpdate = true
                                        coroutineScope.launch {
                                            val result = AppUpdateHelper.checkForUpdates()
                                            isCheckingUpdate = false
                                            when (result) {
                                                is AppUpdateResult.UpToDate -> {
                                                    Toast.makeText(
                                                        context,
                                                        "Aplikasi sudah menggunakan versi terkini (v${result.currentVersion}).",
                                                        Toast.LENGTH_SHORT
                                                    ).show()
                                                }
                                                is AppUpdateResult.UpdateAvailable -> {
                                                    availableUpdate = result
                                                }
                                                is AppUpdateResult.Error -> {
                                                    Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
                                                }
                                            }
                                        }
                                    }
                                ) {
                                    if (isCheckingUpdate) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            strokeWidth = 2.dp
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text("Menyemak...")
                                    } else {
                                        Icon(Icons.Default.SystemUpdate, null, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text("Semak Kemas Kini")
                                    }
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
