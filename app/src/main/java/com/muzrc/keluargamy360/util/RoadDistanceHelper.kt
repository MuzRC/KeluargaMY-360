package com.muzrc.keluargamy360.util

import android.location.Location
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

data class RoadRouteEstimate(
    val distanceMeters: Float,
    val durationSeconds: Float?,
    val isRoadDistance: Boolean
) {
    fun formatCompact(): String {
        val distStr = when {
            distanceMeters < 50f -> "Berhampiran anda"
            distanceMeters < 1000f -> "${distanceMeters.roundToInt()} m"
            else -> String.format(Locale.US, "%.1f km", distanceMeters / 1000f)
        }
        if (distanceMeters < 50f) return distStr

        val etaStr = durationSeconds?.let { sec ->
            val mins = (sec / 60f).roundToInt().coerceAtLeast(1)
            if (mins < 60) "$mins min" else "${mins / 60}j ${mins % 60}m"
        }

        return if (isRoadDistance) {
            if (etaStr != null) "$distStr • ~$etaStr memandu" else "$distStr (jalan raya)"
        } else {
            "$distStr dari anda"
        }
    }

    fun formatBadgeShort(): String {
        val distStr = when {
            distanceMeters < 50f -> "Dekat"
            distanceMeters < 1000f -> "${distanceMeters.roundToInt()} m"
            else -> String.format(Locale.US, "%.1f km", distanceMeters / 1000f)
        }
        val etaStr = if (isRoadDistance && durationSeconds != null && distanceMeters >= 50f) {
            val mins = (durationSeconds / 60f).roundToInt().coerceAtLeast(1)
            " (~${mins}m)"
        } else {
            ""
        }
        return "$distStr$etaStr"
    }
}

object RoadDistanceHelper {

    private data class CacheEntry(
        val estimate: RoadRouteEstimate,
        val cachedAtMs: Long
    )

    private const val CACHE_TTL_MS = 120_000L // Simpan cache 2 minit untuk jimat data & bateri
    private val routeCache = ConcurrentHashMap<String, CacheEntry>()

    fun computeStraightLine(
        myLat: Double,
        myLng: Double,
        targetLat: Double,
        targetLng: Double
    ): RoadRouteEstimate {
        val res = FloatArray(1)
        Location.distanceBetween(myLat, myLng, targetLat, targetLng, res)
        return RoadRouteEstimate(
            distanceMeters = res[0],
            durationSeconds = null,
            isRoadDistance = false
        )
    }

    /**
     * Mengira jarak sebenar melalui jalan raya dan anggaran masa memandu menggunakan OSRM
     * (percuma tanpa kunci API). Jika tiada internet atau tamat tempoh, jatuh balik secara automatik
     * kepada kiraan garis lurus supaya paparan sentiasa pantas.
     */
    suspend fun getRoadEstimate(
        myLat: Double,
        myLng: Double,
        targetLat: Double,
        targetLng: Double
    ): RoadRouteEstimate {
        val straight = computeStraightLine(myLat, myLng, targetLat, targetLng)
        if (straight.distanceMeters < 50f) {
            return straight.copy(isRoadDistance = true, durationSeconds = 60f)
        }

        // Bundarkan koordinat kepada ~110m (3 titik perpuluhan) untuk elak panggilan rangkaian berulang akibat GPS drift
        val cacheKey = String.format(
            Locale.US,
            "%.3f,%.3f->%.3f,%.3f",
            myLat,
            myLng,
            targetLat,
            targetLng
        )

        val now = System.currentTimeMillis()
        routeCache[cacheKey]?.let { entry ->
            if ((now - entry.cachedAtMs) < CACHE_TTL_MS) {
                return entry.estimate
            }
        }

        return withContext(Dispatchers.IO) {
            var conn: HttpURLConnection? = null
            try {
                val urlStr = String.format(
                    Locale.US,
                    "https://router.project-osrm.org/route/v1/driving/%.6f,%.6f;%.6f,%.6f?overview=false",
                    myLng,
                    myLat,
                    targetLng,
                    targetLat
                )
                conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 3500
                    readTimeout = 3500
                    setRequestProperty("User-Agent", "KeluargaMY360-Android")
                }

                if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(body)
                    if (json.optString("code") == "Ok") {
                        val routes = json.optJSONArray("routes")
                        if (routes != null && routes.length() > 0) {
                            val firstRoute = routes.getJSONObject(0)
                            val roadMeters = firstRoute.optDouble("distance", -1.0).toFloat()
                            val durationSec = firstRoute.optDouble("duration", -1.0).toFloat()
                            if (roadMeters >= 0f) {
                                val estimate = RoadRouteEstimate(
                                    distanceMeters = roadMeters,
                                    durationSeconds = if (durationSec >= 0f) durationSec else null,
                                    isRoadDistance = true
                                )
                                routeCache[cacheKey] = CacheEntry(estimate, now)
                                return@withContext estimate
                            }
                        }
                    }
                }
                straight
            } catch (_: Exception) {
                straight
            } finally {
                conn?.disconnect()
            }
        }
    }
}
