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
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.muzrc.keluargamy360.data.local.SecureStorage
import com.muzrc.keluargamy360.data.repository.AuthRepository
import com.muzrc.keluargamy360.data.repository.FamilyRepository
import com.muzrc.keluargamy360.service.LocationTrackingService
import com.muzrc.keluargamy360.ui.auth.AuthViewModel
import com.muzrc.keluargamy360.ui.auth.LoginScreen
import com.muzrc.keluargamy360.ui.auth.RegisterScreen
import com.muzrc.keluargamy360.ui.family.FamilyViewModel
import com.muzrc.keluargamy360.ui.family.JoinCreateScreen
import com.muzrc.keluargamy360.ui.family.UnlockFamilyScreen
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
                val profile = authRepository.getCurrentUserProfile()
                val existingFamilyId = profile?.familyId.orEmpty()
                startDestination = if (existingFamilyId.isNotBlank()) {
                    "unlock_family/$existingFamilyId"
                } else {
                    "family_lobby"
                }
            }
        }
    }

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
                onLoginSuccess = { user ->
                    val hasLocalKey = !secureStorage.getFamilyId().isNullOrBlank() &&
                            !secureStorage.getFamilyMasterKey().isNullOrBlank()
                    val dest = when {
                        hasLocalKey -> "map"
                        user.familyId.isNotBlank() -> "unlock_family/${user.familyId}"
                        else -> "family_lobby"
                    }
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

        composable(
            route = "unlock_family/{familyId}",
            arguments = listOf(navArgument("familyId") { type = NavType.StringType })
        ) { backStackEntry ->
            val familyId = backStackEntry.arguments?.getString("familyId").orEmpty()
            val familyViewModel: FamilyViewModel = viewModel { FamilyViewModel(familyRepository) }
            UnlockFamilyScreen(
                familyId = familyId,
                viewModel = familyViewModel,
                onUnlocked = {
                    navController.navigate("map") {
                        popUpTo("unlock_family/$familyId") { inclusive = true }
                    }
                },
                onSwitchFamily = {
                    navController.navigate("family_lobby") {
                        popUpTo("unlock_family/$familyId") { inclusive = true }
                    }
                },
                onSignOut = {
                    LocationTrackingService.stop(context)
                    secureStorage.clearSession()
                    authRepository.signOut()
                    navController.navigate("login") {
                        popUpTo(0) { inclusive = true }
                    }
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
                        popUpTo(0) { inclusive = true }
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
                        popUpTo(0) { inclusive = true }
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
