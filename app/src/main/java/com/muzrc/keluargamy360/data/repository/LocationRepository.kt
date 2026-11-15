package com.muzrc.keluargamy360.data.repository

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import com.muzrc.keluargamy360.data.local.SecureStorage
import com.muzrc.keluargamy360.data.model.EncryptedHistoryPoint
import com.muzrc.keluargamy360.data.model.HistoryPoint
import com.muzrc.keluargamy360.data.model.UserLocation
import com.muzrc.keluargamy360.util.CryptoHelper
import kotlinx.coroutines.tasks.await

class LocationRepository(
    private val secureStorage: SecureStorage,
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance()
) {
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
        val now = System.currentTimeMillis()

        val rawPayload = "$latitude|$longitude|$speedKmh|$batteryLevel|$isCharging"
        val encrypted = CryptoHelper.encrypt(rawPayload, masterKey)

        val locationDoc = UserLocation(
            uid = uid,
            encryptedPayload = encrypted.ciphertextBase64,
            iv = encrypted.ivBase64,
            updatedAt = now
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
                        "lastSeen" to now
                    )
                ).await()
        } catch (_: Exception) {
        }
    }

    suspend fun appendHistoryPoint(latitude: Double, longitude: Double) {
        val uid = auth.currentUser?.uid ?: return
        val familyId = secureStorage.getFamilyId() ?: return
        val masterKey = secureStorage.getFamilyMasterKey() ?: return
        val now = System.currentTimeMillis()

        try {
            val enc = CryptoHelper.encrypt("$latitude|$longitude", masterKey)
            val point = EncryptedHistoryPoint(
                uid = uid,
                encryptedCoords = enc.ciphertextBase64,
                iv = enc.ivBase64,
                timestamp = now
            )
            db.collection("families").document(familyId)
                .collection("history")
                .add(point)
                .await()

            // Padam rekod sejarah lebih lama daripada 24 jam secara automatik
            val cutoff = now - 86_400_000L
            val oldDocs = db.collection("families").document(familyId)
                .collection("history")
                .whereEqualTo("uid", uid)
                .whereLessThan("timestamp", cutoff)
                .limit(20)
                .get()
                .await()
            for (doc in oldDocs.documents) {
                doc.reference.delete()
            }
        } catch (_: Exception) {
        }
    }

    suspend fun get24HourHistory(targetUid: String): List<HistoryPoint> {
        val familyId = secureStorage.getFamilyId() ?: return emptyList()
        val masterKey = secureStorage.getFamilyMasterKey() ?: return emptyList()
        val cutoff = System.currentTimeMillis() - 86_400_000L

        return try {
            val snap = db.collection("families").document(familyId)
                .collection("history")
                .whereEqualTo("uid", targetUid)
                .whereGreaterThan("timestamp", cutoff)
                .orderBy("timestamp", Query.Direction.ASCENDING)
                .get()
                .await()

            snap.documents.mapNotNull { doc ->
                val enc = doc.toObject(EncryptedHistoryPoint::class.java) ?: return@mapNotNull null
                val plain = CryptoHelper.decrypt(enc.encryptedCoords, enc.iv, masterKey) ?: return@mapNotNull null
                val parts = plain.split("|")
                val lat = parts.getOrNull(0)?.toDoubleOrNull() ?: return@mapNotNull null
                val lng = parts.getOrNull(1)?.toDoubleOrNull() ?: return@mapNotNull null
                HistoryPoint(lat, lng, enc.timestamp)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun postFamilyAlert(
        type: String,
        message: String,
        targetUid: String = ""
    ): Result<Unit> {
        val uid = auth.currentUser?.uid ?: return Result.failure(Exception("Tidak log masuk"))
        val familyId = secureStorage.getFamilyId() ?: return Result.failure(Exception("Tiada keluarga"))
        val senderName = auth.currentUser?.displayName ?: "Ahli Keluarga"

        return try {
            val alertData = mapOf(
                "type" to type,
                "senderId" to uid,
                "senderName" to senderName,
                "targetUid" to targetUid,
                "message" to message,
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

    suspend fun sendSosAlert(senderName: String, latitude: Double, longitude: Double): Result<Unit> {
        val uid = auth.currentUser?.uid ?: return Result.failure(Exception("Tidak log masuk"))
        val familyId = secureStorage.getFamilyId() ?: return Result.failure(Exception("Tiada keluarga"))
        val masterKey = secureStorage.getFamilyMasterKey() ?: return Result.failure(Exception("Kunci tiada"))

        return try {
            val encryptedCoords = CryptoHelper.encrypt("$latitude|$longitude", masterKey)
            val alertData = mapOf(
                "type" to "SOS",
                "senderId" to uid,
                "senderName" to senderName,
                "message" to "AMARAN SOS daripada $senderName! Sila semak lokasi beliau segera.",
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

    suspend fun sendSafeCheckIn(senderName: String, addressText: String): Result<Unit> {
        return postFamilyAlert(
            type = "CHECKIN",
            message = "$senderName telah daftar masuk selamat di $addressText."
        )
    }

    suspend fun sendRingRequest(targetUid: String, targetName: String): Result<Unit> {
        val senderName = auth.currentUser?.displayName ?: "Ahli Keluarga"
        return postFamilyAlert(
            type = "RING",
            targetUid = targetUid,
            message = "$senderName membunyikan penggera telefon $targetName."
        )
    }
}
