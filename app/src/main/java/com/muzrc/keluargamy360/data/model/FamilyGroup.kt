package com.muzrc.keluargamy360.data.model

data class FamilyGroup(
    val familyId: String = "",
    val familyName: String = "",
    val inviteCode: String = "",
    val ownerId: String = "",
    val memberIds: List<String> = emptyList(),
    val wrappedMasterKey: String = "",
    val keyIv: String = "",
    val keySalt: String = "",
    val createdAt: Long = System.currentTimeMillis()
)
