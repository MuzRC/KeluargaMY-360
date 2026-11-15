package com.muzrc.keluargamy360.data.model

data class SafePlace(
    val placeId: String = "",
    val name: String = "",
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val radiusMeters: Float = 150f,
    val createdBy: String = "",
    val isPrivate: Boolean = false
)
