package com.muzrc.keluargamy360.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.muzrc.keluargamy360.data.local.SecureStorage
import com.muzrc.keluargamy360.util.AppDiagnosticLogHelper
import com.muzrc.keluargamy360.util.PermissionHelper

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val validActions = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON"
        )
        if (action !in validActions) return

        val triggerLabel = when (action) {
            Intent.ACTION_MY_PACKAGE_REPLACED -> "Selepas Kemas Kini APK"
            Intent.ACTION_BOOT_COMPLETED -> "Telefon Dihidupkan Semula (Boot)"
            else -> "QuickBoot PowerOn"
        }

        val secureStorage = SecureStorage(context)
        val hasSession = !secureStorage.getFamilyId().isNullOrBlank()
        val isTrackingOn = secureStorage.isTrackingEnabled()
        val hasLocationPerm = PermissionHelper.hasFineLocation(context)

        if (hasSession && isTrackingOn && hasLocationPerm) {
            AppDiagnosticLogHelper.logServiceLifecycle(
                context = context,
                eventTitle = "BootReceiver: Menghidupkan Semula Servis GPS",
                extraDetail = "Pencetus: $triggerLabel"
            )
            try {
                LocationTrackingService.start(context)
            } catch (e: Exception) {
                AppDiagnosticLogHelper.logServiceLifecycle(
                    context = context,
                    eventTitle = "BootReceiver: Gagal Memulakan Servis",
                    extraDetail = "Ralat: ${e.message ?: "Sekatan latar belakang OEM"}"
                )
            }
        } else {
            AppDiagnosticLogHelper.logServiceLifecycle(
                context = context,
                eventTitle = "BootReceiver: Servis Tidak Dimulakan",
                extraDetail = "Pencetus: $triggerLabel | Sesi: $hasSession | Tracking: $isTrackingOn | Kebenaran: $hasLocationPerm"
            )
        }
    }
}
