package com.muzrc.keluargamy360.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.firebase.auth.FirebaseAuth
import com.muzrc.keluargamy360.data.local.SecureStorage
import com.muzrc.keluargamy360.util.PermissionHelper

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action !in SUPPORTED_ACTIONS) return

        try {
            val secureStorage = SecureStorage(context)

            // Hanya hidupkan servis jika pengguna sudah log masuk, menyertai keluarga,
            // dan perkongsian GPS tidak dimatikan dalam Tetapan.
            val isLoggedIn = FirebaseAuth.getInstance().currentUser != null
            val hasFamily = !secureStorage.getFamilyId().isNullOrBlank()
            val isTrackingOn = secureStorage.isTrackingEnabled()
            val hasLocationPerm = PermissionHelper.hasFineLocation(context)

            if (isLoggedIn && hasFamily && isTrackingOn && hasLocationPerm) {
                LocationTrackingService.start(context)
            }
        } catch (_: Exception) {
            // Elak crash jika peranti masih dalam mod Direct Boot sebelum nyahkunci pertama
        }
    }

    companion object {
        private val SUPPORTED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON"
        )
    }
}
