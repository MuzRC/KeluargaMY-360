package com.muzrc.keluargamy360.data.model

data class EncryptedHistoryPoint(
    val uid: String = "",
    val encryptedCoords: String = "",
    val iv: String = "",
    val timestamp: Long = System.currentTimeMillis()
)

data class HistoryPoint(
    val latitude: Double,
    val longitude: Double,
    val timestamp: Long
)
