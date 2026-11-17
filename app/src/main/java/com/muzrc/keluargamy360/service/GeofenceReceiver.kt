package com.muzrc.keluargamy360.service

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import com.google.firebase.auth.FirebaseAuth
import com.muzrc.keluargamy360.KeluargaApp
import com.muzrc.keluargamy360.MainActivity
import com.muzrc.keluargamy360.data.local.SecureStorage
import com.muzrc.keluargamy360.data.repository.LocationRepository
import com.muzrc.keluargamy360.util.PrivateZoneLogHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class GeofenceReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val geofencingEvent = GeofencingEvent.fromIntent(intent) ?: return
        if (geofencingEvent.hasError()) return

        val transition = geofencingEvent.geofenceTransition
        if (transition != Geofence.GEOFENCE_TRANSITION_ENTER &&
            transition != Geofence.GEOFENCE_TRANSITION_EXIT
        ) return

        val isEnter = transition == Geofence.GEOFENCE_TRANSITION_ENTER
        val triggeringGeofences = geofencingEvent.triggeringGeofences ?: return
        val secureStorage = SecureStorage(context)
        val locationRepo = LocationRepository(secureStorage)
        val userName = FirebaseAuth.getInstance().currentUser?.displayName?.takeIf { it.isNotBlank() }
            ?: secureStorage.getCachedUserName()
            ?: "Ahli Keluarga"
        val cooldownPrefs = context.getSharedPreferences("keluargamy360_geofence_cooldown", Context.MODE_PRIVATE)
        val statePrefs = context.getSharedPreferences("keluargamy360_private_zone_state", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()

        val privatePlaces = secureStorage.getPrivatePlaces()
        val familyPlaces = secureStorage.getFamilyPlaces()
        val pendingFamilyAlerts = mutableListOf<String>()

        for (geofence in triggeringGeofences) {
            val placeName = geofence.requestId
            val eventKey = "${placeName}_$transition"
            val lastTriggered = cooldownPrefs.getLong(eventKey, 0L)

            // Penampan masa 10 minit (600,000 ms) untuk elak rekod berulang di sempadan zon
            if (now - lastTriggered < 600_000L) continue
            cooldownPrefs.edit().putLong(eventKey, now).apply()

            val localMsg = if (isEnter) {
                "Anda telah tiba di zon selamat: $placeName."
            } else {
                "Anda telah keluar dari zon selamat: $placeName."
            }
            val familyMsg = if (isEnter) {
                "[MASUK] $userName telah tiba di zon selamat: $placeName."
            } else {
                "[KELUAR] $userName telah keluar dari zon selamat: $placeName."
            }

            showGeofenceNotification(context, placeName, localMsg)

            val privatePlace = privatePlaces.find { it.name == placeName }
            if (privatePlace != null) {
                // Rekod terus ke fail .txt tempatan (tidak dihantar ke pelayan keluarga)
                statePrefs.edit().putBoolean("inside_${privatePlace.placeId}", isEnter).apply()
                PrivateZoneLogHelper.appendEvent(context, placeName, isEnter)
            } else {
                val familyPlace = familyPlaces.find { it.name == placeName }
                if (familyPlace != null) {
                    statePrefs.edit().putBoolean("inside_${familyPlace.placeId}", isEnter).apply()
                }
                pendingFamilyAlerts.add(familyMsg)
            }
        }

        if (pendingFamilyAlerts.isNotEmpty()) {
            val pendingResult = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    for (msg in pendingFamilyAlerts) {
                        locationRepo.postFamilyAlert(type = "GEOFENCE", message = msg)
                    }
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }

    private fun showGeofenceNotification(context: Context, title: String, body: String) {
        if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) return

        val openIntent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context,
            title.hashCode(),
            openIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, KeluargaApp.CHANNEL_GEOFENCE_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_map)
            .setContentTitle("Kemas Kini Zon: $title")
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        NotificationManagerCompat.from(context).notify(title.hashCode(), notification)
    }
}
