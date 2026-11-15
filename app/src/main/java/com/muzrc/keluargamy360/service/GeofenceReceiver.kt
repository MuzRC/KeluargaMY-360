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
import com.muzrc.keluargamy360.KeluargaApp
import com.muzrc.keluargamy360.MainActivity

class GeofenceReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val geofencingEvent = GeofencingEvent.fromIntent(intent) ?: return
        if (geofencingEvent.hasError()) return

        val transition = geofencingEvent.geofenceTransition
        val triggeringGeofences = geofencingEvent.triggeringGeofences ?: return

        for (geofence in triggeringGeofences) {
            val placeName = geofence.requestId
            val message = when (transition) {
                Geofence.GEOFENCE_TRANSITION_ENTER -> "Anda telah tiba di zon selamat: $placeName."
                Geofence.GEOFENCE_TRANSITION_EXIT -> "Anda telah keluar dari zon selamat: $placeName."
                else -> continue
            }
            showGeofenceNotification(context, placeName, message)
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

        val notification = NotificationCompat.Builder(context, KeluargaApp.CHANNEL_SOS_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_map)
            .setContentTitle("Kemas Kini Zon: $title")
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        NotificationManagerCompat.from(context).notify(title.hashCode(), notification)
    }
}
