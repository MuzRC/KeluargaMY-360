package com.muzrc.keluargamy360.data.repository

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.muzrc.keluargamy360.data.local.SecureStorage
import com.muzrc.keluargamy360.data.model.UserLocation
import com.muzrc.keluargamy360.util.CryptoHelper
import kotlinx.coroutines.tasks.await

class LocationRepository(
    private val secureStorage: SecureStorage,
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance()
) {
    /**
     * Sulitkan koordinat GPS dengan AES-256-GCM dan muat naik ke Firestore.
     */
    suspend fun uploadEncryptedLocation(
        latitude: Double,
        longitude: Double,
        speedKmh: Float,
        batteryLevel: Int,
        isCharging: Boolean
    ) {
        val uid = auth.currentUser?.uid ?: return
        val familyId = secureStorage.getFamilyId() ?: return
        val masterKey = secureStorage.getFamilyMasterKey() ?: return

        val rawPayload = "$latitude|$longitude|$speedKmh|$batteryLevel|$isCharging"
        val encrypted = CryptoHelper.encrypt(rawPayload, masterKey)

        val locationDoc = UserLocation(
            uid = uid,
            encryptedPayload = encrypted.ciphertextBase64,
            iv = encrypted.ivBase64,
            updatedAt = System.currentTimeMillis()
        )

        try {
            db.collection("families").document(familyId)
                .collection("locations").document(uid)
                .set(locationDoc, SetOptions.merge())
                .await()

            db.collection("users").document(uid)
                .update(
                    mapOf(
                        "batteryLevel" to batteryLevel,
                        "isCharging" to isCharging,
                        "lastSeen" to System.currentTimeMillis()
                    )
                ).await()
        } catch (_: Exception) {
        }
    }

    /**
     * Hantar amaran kecemasan SOS berserta koordinat tersulit kepada seluruh keluarga.
     */
    suspend fun sendSosAlert(senderName: String, latitude: Double, longitude: Double): Result<Unit> {
        val uid = auth.currentUser?.uid ?: return Result.failure(Exception("Tidak log masuk"))
        val familyId = secureStorage.getFamilyId() ?: return Result.failure(Exception("Tiada keluarga"))
        val masterKey = secureStorage.getFamilyMasterKey() ?: return Result.failure(Exception("Kunci tiada"))

        return try {
            val encryptedCoords = CryptoHelper.encrypt("$latitude|$longitude", masterKey)
            val alertData = mapOf(
                "senderId" to uid,
                "senderName" to senderName,
                "encryptedCoords" to encryptedCoords.ciphertextBase64,
                "iv" to encryptedCoords.ivBase64,
                "timestamp" to System.currentTimeMillis()
            )
            db.collection("families").document(familyId)
                .collection("alerts")
                .add(alertData)
                .await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
