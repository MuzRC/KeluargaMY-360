package com.muzrc.keluargamy360.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.muzrc.keluargamy360.data.model.SafePlace
import org.json.JSONArray
import org.json.JSONObject

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

    fun savePrivatePlaces(places: List<SafePlace>) {
        val array = JSONArray()
        places.forEach { p ->
            val obj = JSONObject().apply {
                put("placeId", p.placeId)
                put("name", p.name)
                put("latitude", p.latitude)
                put("longitude", p.longitude)
                put("radiusMeters", p.radiusMeters.toDouble())
                put("createdBy", p.createdBy)
            }
            array.put(obj)
        }
        prefs.edit().putString(KEY_PRIVATE_PLACES, array.toString()).apply()
    }

    fun getPrivatePlaces(): List<SafePlace> {
        val raw = prefs.getString(KEY_PRIVATE_PLACES, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                SafePlace(
                    placeId = obj.getString("placeId"),
                    name = obj.getString("name"),
                    latitude = obj.getDouble("latitude"),
                    longitude = obj.getDouble("longitude"),
                    radiusMeters = obj.getDouble("radiusMeters").toFloat(),
                    createdBy = obj.optString("createdBy", ""),
                    isPrivate = true
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun clearSession() {
        val savedPrivate = prefs.getString(KEY_PRIVATE_PLACES, null)
        prefs.edit().clear().apply()
        if (savedPrivate != null) {
            prefs.edit().putString(KEY_PRIVATE_PLACES, savedPrivate).apply()
        }
    }

    companion object {
        private const val KEY_FAMILY_ID = "family_id"
        private const val KEY_FAMILY_MASTER_KEY = "family_master_key"
        private const val KEY_TRACKING_ENABLED = "tracking_enabled"
        private const val KEY_PRIVATE_PLACES = "private_safe_places"
    }
}
