package com.muzrc.keluargamy360.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.provider.Settings
import com.muzrc.keluargamy360.R

object NotificationSoundHelper {

    private const val PREFS_NAME = "keluargamy360_notif_sound_prefs"
    private const val KEY_GEOFENCE_URI = "geofence_sound_uri"
    private const val KEY_GEOFENCE_LABEL = "geofence_sound_label"
    private const val KEY_GEOFENCE_VERSION = "geofence_channel_version"

    private const val KEY_SOS_URI = "sos_sound_uri"
    private const val KEY_SOS_LABEL = "sos_sound_label"
    private const val KEY_SOS_VERSION = "sos_channel_version"

    fun getGeofenceChannelId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val ver = prefs.getInt(KEY_GEOFENCE_VERSION, 0)
        return if (ver == 0) "keluargamy360_geofence" else "keluargamy360_geofence_v$ver"
    }

    fun getSosChannelId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val ver = prefs.getInt(KEY_SOS_VERSION, 0)
        return if (ver == 0) "keluargamy360_sos" else "keluargamy360_sos_v$ver"
    }

    fun getGeofenceSoundUri(context: Context): Uri? {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_GEOFENCE_URI, null)
        return raw?.takeIf { it.isNotBlank() }?.let { Uri.parse(it) }
    }

    fun getGeofenceSoundLabel(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_GEOFENCE_LABEL, null) ?: "Senyap (Lalai)"
    }

    fun getSosSoundUri(context: Context): Uri? {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_SOS_URI, null)
        return raw?.takeIf { it.isNotBlank() }?.let { Uri.parse(it) }
    }

    fun getSosSoundLabel(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_SOS_LABEL, null) ?: "Penggera Lalai Sistem"
    }

    fun resolveSoundDisplayName(context: Context, uri: Uri?): String {
        if (uri == null) return "Senyap (Tiada Bunyi)"
        try {
            val ringtone = RingtoneManager.getRingtone(context, uri)
            val title = ringtone?.getTitle(context)
            if (!title.isNullOrBlank()) return title
        } catch (_: Exception) {
        }
        try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) {
                            val name = cursor.getString(idx)
                            if (!name.isNullOrBlank()) return name
                        }
                    }
                }
        } catch (_: Exception) {
        }
        return uri.lastPathSegment ?: "Nada Pilihan"
    }

    fun setGeofenceSound(context: Context, uri: Uri?, customLabel: String? = null) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val oldChannelId = getGeofenceChannelId(context)
        val nextVer = prefs.getInt(KEY_GEOFENCE_VERSION, 0) + 1
        val label = customLabel ?: if (uri == null) "Senyap (Lalai)" else resolveSoundDisplayName(context, uri)

        prefs.edit()
            .putString(KEY_GEOFENCE_URI, uri?.toString().orEmpty())
            .putString(KEY_GEOFENCE_LABEL, label)
            .putInt(KEY_GEOFENCE_VERSION, nextVer)
            .apply()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.deleteNotificationChannel(oldChannelId)
        }
        ensureAllChannels(context)
    }

    fun setSosSound(context: Context, uri: Uri?, customLabel: String? = null) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val oldChannelId = getSosChannelId(context)
        val nextVer = prefs.getInt(KEY_SOS_VERSION, 0) + 1
        val label = customLabel ?: if (uri == null) "Penggera Lalai Sistem" else resolveSoundDisplayName(context, uri)

        prefs.edit()
            .putString(KEY_SOS_URI, uri?.toString().orEmpty())
            .putString(KEY_SOS_LABEL, label)
            .putInt(KEY_SOS_VERSION, nextVer)
            .apply()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.deleteNotificationChannel(oldChannelId)
        }
        ensureAllChannels(context)
    }

    fun ensureAllChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        // 1. Saluran senyap untuk Foreground Service GPS 24/7
        val trackingChannel = NotificationChannel(
            "keluargamy360_tracking",
            context.getString(R.string.channel_tracking_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.channel_tracking_desc)
            setShowBadge(false)
        }

        // 2. Saluran Zon Selamat (Senyap jika tiada bunyi dipilih, atau bunyi pilihan pengguna)
        val geoUri = getGeofenceSoundUri(context)
        val geoImportance = if (geoUri != null) {
            NotificationManager.IMPORTANCE_HIGH
        } else {
            NotificationManager.IMPORTANCE_LOW
        }
        val geofenceChannel = NotificationChannel(
            getGeofenceChannelId(context),
            "Kemas Kini Zon Selamat",
            geoImportance
        ).apply {
            description = "Makluman apabila ahli keluarga tiba atau keluar dari zon selamat."
            setShowBadge(true)
            if (geoUri != null) {
                val attrs = AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .build()
                enableVibration(true)
                setSound(geoUri, attrs)
            } else {
                enableVibration(false)
                setSound(null, null)
            }
        }

        // 3. Saluran Amaran Kecemasan SOS
        val customSosUri = getSosSoundUri(context)
        val effectiveSosUri = customSosUri
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

        val sosAttrs = AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .setUsage(AudioAttributes.USAGE_ALARM)
            .build()

        val sosChannel = NotificationChannel(
            getSosChannelId(context),
            context.getString(R.string.channel_sos_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.channel_sos_desc)
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 500, 250, 500, 250, 800)
            setSound(effectiveSosUri, sosAttrs)
        }

        manager.createNotificationChannel(trackingChannel)
        manager.createNotificationChannel(geofenceChannel)
        manager.createNotificationChannel(sosChannel)
    }

    fun openSystemChannelSettings(context: Context, channelId: String) {
        try {
            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    putExtra(Settings.EXTRA_CHANNEL_ID, channelId)
                }
            } else {
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", context.packageName, null)
                }
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (_: Exception) {
        }
    }
}
