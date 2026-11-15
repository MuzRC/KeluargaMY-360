package com.muzrc.keluargamy360.data.model

data class UserLocation(
    val uid: String = "",
    val encryptedPayload: String = "",
    val iv: String = "",
    val updatedAt: Long = System.currentTimeMillis()
)

data class DecryptedMemberLocation(
    val uid: String,
    val name: String,
    val photoUrl: String,
    val latitude: Double,
    val longitude: Double,
    val speedKmh: Float,
    val batteryLevel: Int,
    val isCharging: Boolean,
    val updatedAt: Long,
    val addressText: String = "Mengesan lokasi..."
)
