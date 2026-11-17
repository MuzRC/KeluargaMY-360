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

    fun setAutoStartConfigured(configured: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_START_CONFIGURED, configured).apply()
    }

    fun isAutoStartConfigured(): Boolean = prefs.getBoolean(KEY_AUTO_START_CONFIGURED, false)

    fun setLowBatteryAlertEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_LOW_BATTERY_ALERT, enabled).apply()
    }

    fun isLowBatteryAlertEnabled(): Boolean = prefs.getBoolean(KEY_LOW_BATTERY_ALERT, true)

    fun setSpeedAlertEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SPEED_ALERT_ENABLED, enabled).apply()
    }

    fun isSpeedAlertEnabled(): Boolean = prefs.getBoolean(KEY_SPEED_ALERT_ENABLED, true)

    fun setSpeedLimitKmh(limit: Float) {
        prefs.edit().putFloat(KEY_SPEED_LIMIT_KMH, limit).apply()
    }

    fun getSpeedLimitKmh(): Float = prefs.getFloat(KEY_SPEED_LIMIT_KMH, 110f)

    fun setCrashDetectionEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_CRASH_DETECTION, enabled).apply()
    }

    fun isCrashDetectionEnabled(): Boolean = prefs.getBoolean(KEY_CRASH_DETECTION, true)

    fun saveMemberNickname(uid: String, nickname: String) {
        val clean = nickname.trim()
        val map = getMemberNicknames().toMutableMap()
        if (clean.isEmpty()) {
            map.remove(uid)
        } else {
            map[uid] = clean
        }
        val obj = JSONObject()
        map.forEach { (k, v) -> obj.put(k, v) }
        prefs.edit().putString(KEY_MEMBER_NICKNAMES, obj.toString()).apply()
    }

    fun getMemberNicknames(): Map<String, String> {
        val raw = prefs.getString(KEY_MEMBER_NICKNAMES, null) ?: return emptyMap()
        return try {
            val obj = JSONObject(raw)
            val result = mutableMapOf<String, String>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val v = obj.optString(k, "").trim()
                if (k.isNotBlank() && v.isNotBlank()) {
                    result[k] = v
                }
            }
            result
        } catch (_: Exception) {
            emptyMap()
        }
    }

    fun saveCachedUserName(name: String) {
        val clean = name.trim()
        if (clean.isNotEmpty()) {
            prefs.edit().putString(KEY_CACHED_USER_NAME, clean).apply()
        }
    }

    fun getCachedUserName(): String? = prefs.getString(KEY_CACHED_USER_NAME, null)

    fun saveFamilyPlaces(places: List<SafePlace>) {
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
        prefs.edit().putString(KEY_FAMILY_PLACES, array.toString()).apply()
    }

    fun getFamilyPlaces(): List<SafePlace> {
        val raw = prefs.getString(KEY_FAMILY_PLACES, null) ?: return emptyList()
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
                    isPrivate = false
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

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
        val savedAutoStart = prefs.getBoolean(KEY_AUTO_START_CONFIGURED, false)
        val savedNicknames = prefs.getString(KEY_MEMBER_NICKNAMES, null)
        prefs.edit().clear().apply()
        prefs.edit().putBoolean(KEY_AUTO_START_CONFIGURED, savedAutoStart).apply()
        if (savedPrivate != null) {
            prefs.edit().putString(KEY_PRIVATE_PLACES, savedPrivate).apply()
        }
        if (savedNicknames != null) {
            prefs.edit().putString(KEY_MEMBER_NICKNAMES, savedNicknames).apply()
        }
    }

    companion object {
        private const val KEY_FAMILY_ID = "family_id"
        private const val KEY_FAMILY_MASTER_KEY = "family_master_key"
        private const val KEY_TRACKING_ENABLED = "tracking_enabled"
        private const val KEY_AUTO_START_CONFIGURED = "auto_start_configured"
        private const val KEY_LOW_BATTERY_ALERT = "low_battery_alert"
        private const val KEY_SPEED_ALERT_ENABLED = "speed_alert_enabled"
        private const val KEY_SPEED_LIMIT_KMH = "speed_limit_kmh"
        private const val KEY_CRASH_DETECTION = "crash_detection_enabled"
        private const val KEY_CACHED_USER_NAME = "cached_user_display_name"
        private const val KEY_FAMILY_PLACES = "family_safe_places"
        private const val KEY_PRIVATE_PLACES = "private_safe_places"
        private const val KEY_MEMBER_NICKNAMES = "member_custom_nicknames"
    }
}
