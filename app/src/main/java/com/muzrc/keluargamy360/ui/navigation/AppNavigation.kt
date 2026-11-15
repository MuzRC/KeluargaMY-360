package com.muzrc.keluargamy360.ui.navigation

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.muzrc.keluargamy360.data.local.SecureStorage
import com.muzrc.keluargamy360.data.repository.AuthRepository
import com.muzrc.keluargamy360.data.repository.FamilyRepository
import com.muzrc.keluargamy360.service.LocationTrackingService
import com.muzrc.keluargamy360.ui.auth.AuthViewModel
import com.muzrc.keluargamy360.ui.auth.LoginScreen
import com.muzrc.keluargamy360.ui.auth.RegisterScreen
import com.muzrc.keluargamy360.ui.family.FamilyViewModel
import com.muzrc.keluargamy360.ui.family.JoinCreateScreen
import com.muzrc.keluargamy360.ui.map.MapScreen
import com.muzrc.keluargamy360.ui.map.MapViewModel
import com.muzrc.keluargamy360.ui.places.PlacesScreen
import com.muzrc.keluargamy360.util.AppUpdateInfo
import com.muzrc.keluargamy360.util.UpdateChecker

@Composable
fun AppNavigation() {
    val context = LocalContext.current
    val navController = rememberNavController()

    val secureStorage = remember { SecureStorage(context) }
    val authRepository = remember { AuthRepository() }
    val familyRepository = remember { FamilyRepository(secureStorage) }

    var startDestination by remember { mutableStateOf<String?>(null) }
    var availableUpdate by remember { mutableStateOf<AppUpdateInfo?>(null) }

    // Semak sesi pengguna & kemas kini aplikasi semasa mula dibuka
    LaunchedEffect(Unit) {
        availableUpdate = UpdateChecker.checkForUpdate()

        val firebaseUser = authRepository.currentUser
        if (firebaseUser == null) {
            secureStorage.clearSession()
            startDestination = "login"
        } else {
            val savedFamilyId = secureStorage.getFamilyId()
            val savedMasterKey = secureStorage.getFamilyMasterKey()
            if (!savedFamilyId.isNullOrBlank() && !savedMasterKey.isNullOrBlank()) {
                startDestination = "map"
            } else {
                startDestination = "family_lobby"
            }
        }
    }

    // Papar dialog jika terdapat versi APK baharu di GitHub Releases
    availableUpdate?.let { update ->
        AlertDialog(
            onDismissRequest = { availableUpdate = null },
            title = { Text("Kemas Kini Baharu v${update.latestVersion}", fontWeight = FontWeight.Bold) },
            text = { Text(update.releaseNotes) },
            confirmButton = {
                Button(onClick = {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(update.downloadUrl))
                    context.startActivity(intent)
                    availableUpdate = null
                }) {
                    Text("Muat Turun APK")
                }
            },
            dismissButton = {
                TextButton(onClick = { availableUpdate = null }) {
                    Text("Nanti")
                }
            }
        )
    }

    val initialRoute = startDestination
    if (initialRoute == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    NavHost(navController = navController, startDestination = initialRoute) {
        composable("login") {
            val authViewModel: AuthViewModel = viewModel { AuthViewModel(authRepository) }
            LoginScreen(
                viewModel = authViewModel,
                onLoginSuccess = {
                    val hasFamilyKey = !secureStorage.getFamilyId().isNullOrBlank() &&
                            !secureStorage.getFamilyMasterKey().isNullOrBlank()
                    val dest = if (hasFamilyKey) "map" else "family_lobby"
                    navController.navigate(dest) {
                        popUpTo("login") { inclusive = true }
                    }
                },
                onNavigateToRegister = {
                    navController.navigate("register")
                }
            )
        }

        composable("register") {
            val authViewModel: AuthViewModel = viewModel { AuthViewModel(authRepository) }
            RegisterScreen(
                viewModel = authViewModel,
                onRegisterSuccess = {
                    navController.navigate("family_lobby") {
                        popUpTo("login") { inclusive = true }
                    }
                },
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }

        composable("family_lobby") {
            val familyViewModel: FamilyViewModel = viewModel { FamilyViewModel(familyRepository) }
            JoinCreateScreen(
                viewModel = familyViewModel,
                onFamilyReady = {
                    navController.navigate("map") {
                        popUpTo("family_lobby") { inclusive = true }
                    }
                },
                onSignOut = {
                    LocationTrackingService.stop(context)
                    secureStorage.clearSession()
                    authRepository.signOut()
                    navController.navigate("login") {
                        popUpTo("family_lobby") { inclusive = true }
                    }
                }
            )
        }

        composable("map") {
            val mapViewModel: MapViewModel = viewModel {
                MapViewModel(context.applicationContext, secureStorage)
            }
            MapScreen(
                viewModel = mapViewModel,
                onNavigateToPlaces = {
                    navController.navigate("places")
                },
                onSignOut = {
                    LocationTrackingService.stop(context)
                    secureStorage.clearSession()
                    authRepository.signOut()
                    navController.navigate("login") {
                        popUpTo("map") { inclusive = true }
                    }
                }
            )
        }

        composable("places") {
            val mapViewModel: MapViewModel = viewModel {
                MapViewModel(context.applicationContext, secureStorage)
            }
            PlacesScreen(
                viewModel = mapViewModel,
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }
    }
}
