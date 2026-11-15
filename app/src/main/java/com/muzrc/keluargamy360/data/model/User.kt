package com.muzrc.keluargamy360.data.model

data class User(
    val uid: String = "",
    val name: String = "",
    val email: String = "",
    val photoUrl: String = "",
    val familyId: String = "",
    val fcmToken: String = "",
    val batteryLevel: Int = 100,
    val isCharging: Boolean = false,
    val lastSeen: Long = System.currentTimeMillis()
)
