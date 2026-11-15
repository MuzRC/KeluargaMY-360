package com.muzrc.keluargamy360.ui.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import com.muzrc.keluargamy360.data.model.DecryptedMemberLocation
import com.muzrc.keluargamy360.data.model.SafePlace
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

object MapMarkerHelper {

    private const val PLACES_SOURCE_ID = "keluargamy360-places-source"
    private const val PLACES_CIRCLE_LAYER_ID = "keluargamy360-places-circle"
    private const val MEMBERS_SOURCE_ID = "keluargamy360-members-source"
    private const val MEMBERS_SYMBOL_LAYER_ID = "keluargamy360-members-symbol"

    fun setupLayers(style: Style) {
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
            val iconId = "member-pin-${m.uid}"
            val bitmap = createBadgeBitmap(
                initial = m.name.take(1).uppercase(),
                label = "${m.name.split(" ").first()} • ${m.batteryLevel}%"
            )
            style.addImage(iconId, bitmap)

            Feature.fromGeometry(Point.fromLngLat(m.longitude, m.latitude)).apply {
                addStringProperty("iconId", iconId)
            }
        }
        source.setGeoJson(FeatureCollection.fromFeatures(features))
    }

    private fun createBadgeBitmap(initial: String, label: String): Bitmap {
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

        val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#0D47A1") }
        val pillRect = RectF(centerX - pillWidth / 2f, 4f, centerX + pillWidth / 2f, 4f + pillHeight)
        canvas.drawRoundRect(pillRect, 26f, 26f, pillPaint)

        val textY = 4f + pillHeight / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(label, centerX - textWidth / 2f, textY, textPaint)

        val circleCenterY = 4f + pillHeight + 10f + circleRadius
        val whitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        val bluePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#1976D2") }
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
