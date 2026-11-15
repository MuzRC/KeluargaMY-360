package com.muzrc.keluargamy360.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class SecureStorage(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "keluargamy360_secure_vault",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun saveFamilySession(familyId: String, familyMasterKey: String) {
        prefs.edit()
            .putString(KEY_FAMILY_ID, familyId)
            .putString(KEY_FAMILY_MASTER_KEY, familyMasterKey)
            .apply()
    }

    fun getFamilyId(): String? = prefs.getString(KEY_FAMILY_ID, null)

    fun getFamilyMasterKey(): String? = prefs.getString(KEY_FAMILY_MASTER_KEY, null)

    fun setTrackingEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_TRACKING_ENABLED, enabled).apply()
    }

    fun isTrackingEnabled(): Boolean = prefs.getBoolean(KEY_TRACKING_ENABLED, true)

    fun clearSession() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val KEY_FAMILY_ID = "family_id"
        private const val KEY_FAMILY_MASTER_KEY = "family_master_key"
        private const val KEY_TRACKING_ENABLED = "tracking_enabled"
    }
}
