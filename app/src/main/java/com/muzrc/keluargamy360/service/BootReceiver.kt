package com.muzrc.keluargamy360.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.firebase.auth.FirebaseAuth
import com.muzrc.keluargamy360.data.local.SecureStorage

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON"
        ) {
            val user = FirebaseAuth.getInstance().currentUser
            val secureStorage = SecureStorage(context)
            if (user != null &&
                !secureStorage.getFamilyId().isNullOrBlank() &&
                secureStorage.isTrackingEnabled()
            ) {
                LocationTrackingService.start(context)
            }
        }
    }
}
