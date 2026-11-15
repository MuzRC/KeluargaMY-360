package com.muzrc.keluargamy360.ui.family

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.muzrc.keluargamy360.data.model.FamilyGroup

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JoinCreateScreen(
    viewModel: FamilyViewModel,
    onFamilyReady: (FamilyGroup) -> Unit,
    onSignOut: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    var selectedTab by remember { mutableIntStateOf(0) }

    var inviteCode by remember { mutableStateOf("") }
    var joinPin by remember { mutableStateOf("") }

    var familyName by remember { mutableStateOf("") }
    var createPin by remember { mutableStateOf("") }

    LaunchedEffect(uiState.joinedFamily) {
        uiState.joinedFamily?.let { onFamilyReady(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Kumpulan Keluarga") },
                actions = {
                    TextButton(onClick = onSignOut) {
                        Text("Log Keluar", color = MaterialTheme.colorScheme.error)
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp)
        ) {
            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0; viewModel.clearError() },
                    text = { Text("Sertai Keluarga") }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1; viewModel.clearError() },
                    text = { Text("Cipta Baharu") }
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            if (selectedTab == 0) {
                Text(
                    text = "Masukkan Kod Jemputan",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Dapatkan kod 6-aksara dan PIN keselamatan daripada ketua keluarga anda.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 16.dp)
                )

                OutlinedTextField(
                    value = inviteCode,
                    onValueChange = { if (it.length <= 6) inviteCode = it.uppercase(); viewModel.clearError() },
                    label = { Text("Kod Jemputan (6 Aksara)") },
                    leadingIcon = { Icon(Icons.Default.GroupAdd, contentDescription = null) },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = joinPin,
                    onValueChange = { if (it.length <= 6) joinPin = it; viewModel.clearError() },
                    label = { Text("PIN Keselamatan Kumpulan (6 Digit)") },
                    leadingIcon = { Icon(Icons.Default.Key, contentDescription = null) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                uiState.errorMessage?.let { error ->
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                Button(
                    onClick = { viewModel.joinFamily(inviteCode, joinPin) },
                    enabled = !uiState.isLoading,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().height(50.dp)
                ) {
                    if (uiState.isLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                    } else {
                        Text("Sertai Keluarga Sekarang", fontWeight = FontWeight.SemiBold)
                    }
                }
            } else {
                Text(
                    text = "Cipta Kumpulan Keluarga",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "PIN 6-digit digunakan untuk menyulitkan kunci peta keluarga anda secara hujung-ke-hujung.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 16.dp)
                )

                OutlinedTextField(
                    value = familyName,
                    onValueChange = { familyName = it; viewModel.clearError() },
                    label = { Text("Nama Keluarga (cth: Keluarga Muz)") },
                    leadingIcon = { Icon(Icons.Default.Shield, contentDescription = null) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = createPin,
                    onValueChange = { if (it.length <= 6) createPin = it; viewModel.clearError() },
                    label = { Text("Tetapkan PIN Keselamatan (6 Digit)") },
                    leadingIcon = { Icon(Icons.Default.Key, contentDescription = null) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                uiState.errorMessage?.let { error ->
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                Button(
                    onClick = { viewModel.createFamily(familyName, createPin) },
                    enabled = !uiState.isLoading,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().height(50.dp)
                ) {
                    if (uiState.isLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                    } else {
                        Text("Cipta Keluarga & Jana Kod", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}
