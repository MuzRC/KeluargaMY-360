package com.muzrc.keluargamy360.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class QueuedRoutePoint(
    val uid: String,
    val familyId: String,
    val latitude: Double,
    val longitude: Double,
    val timestamp: Long
)

object OfflineRouteQueueHelper {

    private const val QUEUE_FILE_NAME = "offline_route_queue.json"
    private const val MAX_QUEUE_POINTS = 500
    private const val MAX_AGE_MS = 86_400_000L // 24 jam
    private val lock = Any()

    fun isNetworkAvailable(context: Context): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return false
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } catch (_: Exception) {
            false
        }
    }

    fun enqueue(
        context: Context,
        uid: String,
        familyId: String,
        latitude: Double,
        longitude: Double,
        timestamp: Long
    ) {
        synchronized(lock) {
            try {
                val cutoff = System.currentTimeMillis() - MAX_AGE_MS
                val items = loadValidItemsLocked(context, cutoff)

                // Elak duplikasi cap masa yang sama
                if (items.any { it.timestamp == timestamp && it.uid == uid }) return

                items.add(
                    QueuedRoutePoint(
                        uid = uid,
                        familyId = familyId,
                        latitude = latitude,
                        longitude = longitude,
                        timestamp = timestamp
                    )
                )

                val trimmed = if (items.size > MAX_QUEUE_POINTS) {
                    items.takeLast(MAX_QUEUE_POINTS)
                } else {
                    items
                }
                saveItemsLocked(context, trimmed)
            } catch (_: Exception) {
            }
        }
    }

    fun peekAll(context: Context): List<QueuedRoutePoint> {
        synchronized(lock) {
            return try {
                val cutoff = System.currentTimeMillis() - MAX_AGE_MS
                val valid = loadValidItemsLocked(context, cutoff)
                saveItemsLocked(context, valid)
                valid.toList()
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    fun removeUploadedTimestamps(context: Context, uploadedTimestamps: Set<Long>) {
        if (uploadedTimestamps.isEmpty()) return
        synchronized(lock) {
            try {
                val cutoff = System.currentTimeMillis() - MAX_AGE_MS
                val remaining = loadValidItemsLocked(context, cutoff)
                    .filterNot { it.timestamp in uploadedTimestamps }
                saveItemsLocked(context, remaining)
            } catch (_: Exception) {
            }
        }
    }

    private fun loadValidItemsLocked(context: Context, cutoffMs: Long): MutableList<QueuedRoutePoint> {
        val file = File(context.filesDir, QUEUE_FILE_NAME)
        if (!file.exists()) return mutableListOf()

        val raw = file.readText()
        if (raw.isBlank()) return mutableListOf()

        val arr = JSONArray(raw)
        val result = mutableListOf<QueuedRoutePoint>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val ts = obj.optLong("timestamp", 0L)
            if (ts < cutoffMs) continue
            val uid = obj.optString("uid", "")
            val familyId = obj.optString("familyId", "")
            val lat = obj.optDouble("latitude", Double.NaN)
            val lng = obj.optDouble("longitude", Double.NaN)
            if (uid.isNotBlank() && familyId.isNotBlank() && !lat.isNaN() && !lng.isNaN()) {
                result.add(QueuedRoutePoint(uid, familyId, lat, lng, ts))
            }
        }
        return result
    }

    private fun saveItemsLocked(context: Context, items: List<QueuedRoutePoint>) {
        val file = File(context.filesDir, QUEUE_FILE_NAME)
        if (items.isEmpty()) {
            if (file.exists()) file.delete()
            return
        }
        val arr = JSONArray()
        for (item in items) {
            arr.put(
                JSONObject().apply {
                    put("uid", item.uid)
                    put("familyId", item.familyId)
                    put("latitude", item.latitude)
                    put("longitude", item.longitude)
                    put("timestamp", item.timestamp)
                }
            )
        }
        file.writeText(arr.toString())
    }
}
