package com.muzrc.keluargamy360.ui.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import com.muzrc.keluargamy360.data.model.DecryptedMemberLocation
import com.muzrc.keluargamy360.data.model.HistoryPoint
import com.muzrc.keluargamy360.data.model.SafePlace
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import kotlin.math.roundToInt

object MapMarkerHelper {

    private const val PLACES_SOURCE_ID = "keluargamy360-places-source"
    private const val PLACES_CIRCLE_LAYER_ID = "keluargamy360-places-circle"
    private const val HISTORY_SOURCE_ID = "keluargamy360-history-source"
    private const val HISTORY_LINE_LAYER_ID = "keluargamy360-history-line"
    private const val MEMBERS_SOURCE_ID = "keluargamy360-members-source"
    private const val MEMBERS_SYMBOL_LAYER_ID = "keluargamy360-members-symbol"

    // Cache tandatangan lencana supaya animasi 60fps tidak menjana semula Bitmap berulang kali
    private val badgeSignatureCache = mutableMapOf<String, String>()

    fun setupLayers(style: Style) {
        badgeSignatureCache.clear()

        if (style.getSource(PLACES_SOURCE_ID) == null) {
            style.addSource(GeoJsonSource(PLACES_SOURCE_ID))
        }
        if (style.getLayer(PLACES_CIRCLE_LAYER_ID) == null) {
            val placeCircle = CircleLayer(PLACES_CIRCLE_LAYER_ID, PLACES_SOURCE_ID).withProperties(
                PropertyFactory.circleRadius(36f),
                PropertyFactory.circleColor("#2E7D32"),
                PropertyFactory.circleOpacity(0.22f),
                PropertyFactory.circleStrokeWidth(2f),
                PropertyFactory.circleStrokeColor("#2E7D32")
            )
            style.addLayer(placeCircle)
        }

        if (style.getSource(HISTORY_SOURCE_ID) == null) {
            style.addSource(GeoJsonSource(HISTORY_SOURCE_ID))
        }
        if (style.getLayer(HISTORY_LINE_LAYER_ID) == null) {
            val historyLine = LineLayer(HISTORY_LINE_LAYER_ID, HISTORY_SOURCE_ID).withProperties(
                PropertyFactory.lineColor("#7B1FA2"),
                PropertyFactory.lineWidth(5f),
                PropertyFactory.lineOpacity(0.85f),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
            )
            style.addLayer(historyLine)
        }

        if (style.getSource(MEMBERS_SOURCE_ID) == null) {
            style.addSource(GeoJsonSource(MEMBERS_SOURCE_ID))
        }
        if (style.getLayer(MEMBERS_SYMBOL_LAYER_ID) == null) {
            val symbolLayer = SymbolLayer(MEMBERS_SYMBOL_LAYER_ID, MEMBERS_SOURCE_ID).withProperties(
                PropertyFactory.iconImage(Expression.get("iconId")),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true),
                PropertyFactory.iconAnchor("bottom")
            )
            style.addLayer(symbolLayer)
        }
    }

    fun updateHistoryRoute(map: MapLibreMap, points: List<HistoryPoint>) {
        val style = map.style ?: return
        val source = style.getSourceAs<GeoJsonSource>(HISTORY_SOURCE_ID) ?: return
        if (points.size < 2) {
            source.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
            return
        }
        val coords = points.map { Point.fromLngLat(it.longitude, it.latitude) }
        val lineFeature = Feature.fromGeometry(LineString.fromLngLats(coords))
        source.setGeoJson(FeatureCollection.fromFeatures(listOf(lineFeature)))
    }

    fun updateSafePlaces(map: MapLibreMap, places: List<SafePlace>) {
        val style = map.style ?: return
        val source = style.getSourceAs<GeoJsonSource>(PLACES_SOURCE_ID) ?: return
        val features = places.map { p ->
            Feature.fromGeometry(Point.fromLngLat(p.longitude, p.latitude))
        }
        source.setGeoJson(FeatureCollection.fromFeatures(features))
    }

    fun updateMembers(map: MapLibreMap, members: List<DecryptedMemberLocation>) {
        val style = map.style ?: return
        val source = style.getSourceAs<GeoJsonSource>(MEMBERS_SOURCE_ID) ?: return

        val features = members.map { m ->
            val isMoving = m.speedKmh >= 5f
            val firstName = m.name.split(" ").first()
            val label = if (isMoving) {
                "$firstName • ${m.speedKmh.roundToInt()} km/j"
            } else {
                "$firstName • ${m.batteryLevel}%"
            }

            val iconId = "member-pin-${m.uid}"
            val signature = "${m.name.take(1)}|$label|$isMoving"

            if (badgeSignatureCache[m.uid] != signature) {
                val bitmap = createBadgeBitmap(
                    initial = m.name.take(1).uppercase(),
                    label = label,
                    isMoving = isMoving
                )
                style.addImage(iconId, bitmap)
                badgeSignatureCache[m.uid] = signature
            }

            Feature.fromGeometry(Point.fromLngLat(m.longitude, m.latitude)).apply {
                addStringProperty("iconId", iconId)
            }
        }
        source.setGeoJson(FeatureCollection.fromFeatures(features))
    }

    private fun createBadgeBitmap(initial: String, label: String, isMoving: Boolean): Bitmap {
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 32f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

        val textWidth = textPaint.measureText(label)
        val pillWidth = (textWidth + 44f).coerceAtLeast(150f)
        val pillHeight = 52f
        val circleRadius = 38f
        val totalWidth = pillWidth.toInt().coerceAtLeast(160)
        val totalHeight = (pillHeight + 10f + circleRadius * 2f + 16f).toInt()

        val bitmap = Bitmap.createBitmap(totalWidth, totalHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val centerX = totalWidth / 2f

        val badgeColor = if (isMoving) Color.parseColor("#2E7D32") else Color.parseColor("#0D47A1")
        val avatarColor = if (isMoving) Color.parseColor("#388E3C") else Color.parseColor("#1976D2")

        val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = badgeColor }
        val pillRect = RectF(centerX - pillWidth / 2f, 4f, centerX + pillWidth / 2f, 4f + pillHeight)
        canvas.drawRoundRect(pillRect, 26f, 26f, pillPaint)

        val textY = 4f + pillHeight / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(label, centerX - textWidth / 2f, textY, textPaint)

        val circleCenterY = 4f + pillHeight + 10f + circleRadius
        val whitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        val bluePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = avatarColor }
        canvas.drawCircle(centerX, circleCenterY, circleRadius, whitePaint)
        canvas.drawCircle(centerX, circleCenterY, circleRadius - 5f, bluePaint)

        val initPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 36f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val initY = circleCenterY - (initPaint.descent() + initPaint.ascent()) / 2f
        canvas.drawText(initial, centerX - initPaint.measureText(initial) / 2f, initY, initPaint)

        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#D32F2F") }
        canvas.drawCircle(centerX, totalHeight - 8f, 7f, dotPaint)

        return bitmap
    }
}
