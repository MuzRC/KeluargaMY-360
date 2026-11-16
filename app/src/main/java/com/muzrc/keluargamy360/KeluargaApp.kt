package com.muzrc.keluargamy360

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import com.google.firebase.FirebaseApp
import org.maplibre.android.MapLibre

class KeluargaApp : Application() {

    companion object {
        const val CHANNEL_TRACKING_ID = "keluargamy360_tracking"
        const val CHANNEL_SOS_ID = "keluargamy360_sos"
        const val CHANNEL_GEOFENCE_ID = "keluargamy360_geofence"
    }

    override fun onCreate() {
        super.onCreate()
        FirebaseApp.initializeApp(this)
        MapLibre.getInstance(this)
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)

            // 1. Saluran senyap untuk Foreground Service GPS 24/7
            val trackingChannel = NotificationChannel(
                CHANNEL_TRACKING_ID,
                getString(R.string.channel_tracking_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.channel_tracking_desc)
                setShowBadge(false)
            }

            // 2. Saluran senyap (tanpa bunyi bising) untuk kemas kini tiba/keluar Zon Selamat
            val geofenceChannel = NotificationChannel(
                CHANNEL_GEOFENCE_ID,
                "Kemas Kini Zon Selamat",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Makluman senyap apabila ahli keluarga tiba atau keluar dari zon selamat."
                enableVibration(false)
                setSound(null, null)
                setShowBadge(true)
            }

            // 3. Saluran bunyi kuat dikhaskan untuk amaran kecemasan SOS sahaja
            val alarmSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

            val audioAttributes = AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setUsage(AudioAttributes.USAGE_ALARM)
                .build()

            val sosChannel = NotificationChannel(
                CHANNEL_SOS_ID,
                getString(R.string.channel_sos_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = getString(R.string.channel_sos_desc)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 250, 500, 250, 800)
                setSound(alarmSound, audioAttributes)
            }

            manager.createNotificationChannel(trackingChannel)
            manager.createNotificationChannel(geofenceChannel)
            manager.createNotificationChannel(sosChannel)
        }
    }
}
