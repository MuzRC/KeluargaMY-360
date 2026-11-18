package com.muzrc.keluargamy360

import android.app.Application
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.PersistentCacheSettings
import com.muzrc.keluargamy360.util.NotificationSoundHelper
import org.maplibre.android.MapLibre
import org.maplibre.android.offline.OfflineManager

class KeluargaApp : Application() {

    companion object {
        const val CHANNEL_TRACKING_ID = "keluargamy360_tracking"
        const val CHANNEL_SOS_ID = "keluargamy360_sos"
        const val CHANNEL_GEOFENCE_ID = "keluargamy360_geofence"
    }

    override fun onCreate() {
        super.onCreate()
        FirebaseApp.initializeApp(this)
        configureFirestoreCache()

        MapLibre.getInstance(this)
        configureMapLibreCache()

        NotificationSoundHelper.ensureAllChannels(this)
    }

    private fun configureFirestoreCache() {
        try {
            val cacheSettings = PersistentCacheSettings.newBuilder()
                .setSizeBytes(100L * 1024L * 1024L) // 100 MB Instant Disk Cache
                .build()
            val settings = FirebaseFirestoreSettings.Builder()
                .setLocalCacheSettings(cacheSettings)
                .build()
            FirebaseFirestore.getInstance().firestoreSettings = settings
        } catch (_: Exception) {
            // Abaikan jika Firestore sudah dimulakan lebih awal
        }
    }

    private fun configureMapLibreCache() {
        try {
            OfflineManager.getInstance(this).setMaximumAmbientCacheSize(
                150L * 1024L * 1024L, // 150 MB Ambient Tile Cache
                object : OfflineManager.FileSourceCallback {
                    override fun onSuccess() = Unit
                    override fun onError(message: String) = Unit
                }
            )
        } catch (_: Exception) {
        }
    }
}
