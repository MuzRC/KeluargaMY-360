package com.muzrc.keluargamy360.ui.navigation

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.muzrc.keluargamy360.ui.map.MapScreen
import com.muzrc.keluargamy360.ui.map.MapViewModel
import com.muzrc.keluargamy360.ui.map.MemberCard
import com.muzrc.keluargamy360.ui.places.PlacesScreen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainDashboardScreen(
    mapViewModel: MapViewModel,
    onSignOut: () -> Unit
) {
    // Indeks 2 = Tab Tengah (Peta Dashboard Utama)
    var selectedTab by remember { mutableIntStateOf(2) }
    val uiState by mapViewModel.uiState.collectAsState()
    val context = LocalContext.current

    Scaffold(
        bottomBar = {
            NavigationBar {
                // 1. Tab Kiri 1: Ahli Keluarga
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.People, contentDescription = "Ahli") },
                    label = { Text("Ahli") }
                )
                // 2. Tab Kiri 2: Zon Selamat
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Default.Place, contentDescription = "Zon") },
                    label = { Text("Zon") }
                )
                // 3. Tab Tengah: Dashboard Utama (Peta)
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = { Icon(Icons.Default.Explore, contentDescription = "Peta") },
                    label = { Text("Peta", fontWeight = FontWeight.Bold) }
                )
                // 4. Tab Kanan 1: Daftar Masuk Selamat
                NavigationBarItem(
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 },
                    icon = { Icon(Icons.Default.CheckCircle, contentDescription = "Selamat") },
                    label = { Text("Selamat") }
                )
                // 5. Tab Kanan 2: Tetapan Keluarga
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
                    // Skrin Senarai Penuh Ahli Keluarga
                    Scaffold(
                        topBar = { TopAppBar(title = { Text("Senarai Ahli Keluarga", fontWeight = FontWeight.Bold) }) }
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
                                    onClick = {
                                        mapViewModel.selectMember(member)
                                        selectedTab = 2 // Lompat ke Peta Utama untuk fokus lokasi ahli
                                    }
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
                    // Tab Daftar Masuk Selamat Pantas
                    Column(
                        modifier = Modifier.fillMaxSize().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(72.dp)
                        )
                        Spacer(Modifier.height(16.dp))
                        Text("Daftar Masuk Keselamatan", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text(
                            text = "Maklumkan kepada semua ahli keluarga bahawa anda berada dalam keadaan selamat di lokasi semasa.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 12.dp)
                        )
                        Button(
                            onClick = { mapViewModel.sendSafeCheckIn() },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth().height(52.dp)
                        ) {
                            Text("Hantar Status 'Saya Selamat'", fontWeight = FontWeight.Bold)
                        }
                    }
                }
                4 -> {
                    // Tab Tetapan Kumpulan & Privasi
                    val code = uiState.familyGroup?.inviteCode ?: "-"
                    Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
                        Text("Tetapan Keluarga", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(16.dp))
                        Card(shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.padding(16.dp).fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text("Kod Jemputan (${uiState.familyGroup?.familyName ?: ""})", style = MaterialTheme.typography.labelMedium)
                                    Text(code, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                                }
                                IconButton(onClick = {
                                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    cm.setPrimaryClip(ClipData.newPlainText("Kod KeluargaMY360", code))
                                    Toast.makeText(context, "Kod jemputan disalin!", Toast.LENGTH_SHORT).show()
                                }) { Icon(Icons.Default.ContentCopy, "Salin Kod") }
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Card(shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.padding(16.dp).fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Perkongsian GPS Masa Nyata", fontWeight = FontWeight.SemiBold)
                                Switch(
                                    checked = uiState.isTrackingEnabled,
                                    onCheckedChange = { mapViewModel.toggleTracking(it) }
                                )
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        OutlinedButton(
                            onClick = onSignOut,
                            modifier = Modifier.fillMaxWidth().height(50.dp),
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
