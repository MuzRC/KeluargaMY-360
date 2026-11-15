package com.muzrc.keluargamy360.data.model

data class ActivityLogItem(
    val id: String = "",
    val type: String = "INFO",
    val senderId: String = "",
    val senderName: String = "",
    val message: String = "",
    val timestamp: Long = System.currentTimeMillis()
)
