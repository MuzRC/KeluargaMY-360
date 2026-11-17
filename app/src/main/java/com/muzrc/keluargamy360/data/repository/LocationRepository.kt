package com.muzrc.keluargamy360.data.repository

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.muzrc.keluargamy360.data.local.SecureStorage
import com.muzrc.keluargamy360.data.model.EncryptedHistoryPoint
import com.muzrc.keluargamy360.data.model.HistoryPoint
import com.muzrc.keluargamy360.data.model.UserLocation
import com.muzrc.keluargamy360.util.AppDiagnosticLogHelper
import com.muzrc.keluargamy360.util.CryptoHelper
import com.muzrc.keluargamy360.util.OfflineRouteQueueHelper
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout

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
        isCharging: Boolean,
        context: Context? = null
    ): Boolean {
        val uid = auth.currentUser?.uid ?: return false
        val familyId = secureStorage.getFamilyId() ?: return false
        val masterKey = secureStorage.getFamilyMasterKey() ?: return false
        val now = System.currentTimeMillis()

        val rawPayload = "$latitude|$longitude|$speedKmh|$batteryLevel|$isCharging"
        val encrypted = CryptoHelper.encrypt(rawPayload, masterKey)

        val locationDoc = UserLocation(
            uid = uid,
            encryptedPayload = encrypted.ciphertextBase64,
            iv = encrypted.ivBase64,
            updatedAt = now
        )

        return try {
            withTimeout(4000L) {
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
            }
            true
        } catch (e: Exception) {
            if (context != null) {
                AppDiagnosticLogHelper.logSyncIssue(
                    context = context,
                    operation = "Lokasi Masa Nyata",
                    reason = e.message ?: "Tamat tempoh sambungan (4s)"
                )
            }
            false
        }
    }

    /**
     * Merakam titik laluan sejarah (disimpan sehingga 7 hari).
     * Jika tiada sambungan internet atau muat naik tamat tempoh, titik berserta cap masa asal
     * akan disimpan dalam barisan luar talian tempatan.
     */
    suspend fun appendHistoryPoint(
        latitude: Double,
        longitude: Double,
        context: Context? = null,
        recordedAtMs: Long = System.currentTimeMillis()
    ): Boolean {
        val uid = auth.currentUser?.uid ?: return false
        val familyId = secureStorage.getFamilyId() ?: return false
        val masterKey = secureStorage.getFamilyMasterKey() ?: return false

        if (context != null && !OfflineRouteQueueHelper.isNetworkAvailable(context)) {
            OfflineRouteQueueHelper.enqueue(context, uid, familyId, latitude, longitude, recordedAtMs)
            AppDiagnosticLogHelper.logSyncIssue(
                context = context,
                operation = "Simpan Titik Laluan",
                reason = "Tiada liputan internet"
            )
            return false
        }

        return try {
            val enc = CryptoHelper.encrypt("$latitude|$longitude", masterKey)
            val point = EncryptedHistoryPoint(
                uid = uid,
                encryptedCoords = enc.ciphertextBase64,
                iv = enc.ivBase64,
                timestamp = recordedAtMs
            )
            withTimeout(4000L) {
                db.collection("families").document(familyId)
                    .collection("history")
                    .add(point)
                    .await()
            }

            // Padam rekod sejarah lebih lama daripada 7 hari menggunakan indeks tunggal automatik
            val cutoff = System.currentTimeMillis() - SEVEN_DAYS_MS
            val oldDocs = db.collection("families").document(familyId)
                .collection("history")
                .whereLessThan("timestamp", cutoff)
                .limit(20)
                .get()
                .await()
            for (doc in oldDocs.documents) {
                doc.reference.delete()
            }
            true
        } catch (e: Exception) {
            if (context != null) {
                OfflineRouteQueueHelper.enqueue(context, uid, familyId, latitude, longitude, recordedAtMs)
                AppDiagnosticLogHelper.logSyncIssue(
                    context = context,
                    operation = "Simpan Titik Laluan",
                    reason = e.message ?: "Muat naik tamat tempoh (4s)"
                )
            }
            false
        }
    }

    /**
     * Memuat naik semula semua titik laluan yang tertunggak semasa luar talian secara berkelompok
     * (1 WriteBatch untuk sehingga 50 titik sekaligus) sebaik sambungan internet pulih.
     */
    suspend fun flushOfflineRouteQueue(context: Context): Int {
        val uid = auth.currentUser?.uid ?: return 0
        val familyId = secureStorage.getFamilyId() ?: return 0
        val masterKey = secureStorage.getFamilyMasterKey() ?: return 0

        if (!OfflineRouteQueueHelper.isNetworkAvailable(context)) return 0

        val pending = OfflineRouteQueueHelper.peekAll(context)
            .filter { it.uid == uid && it.familyId == familyId }
        if (pending.isEmpty()) return 0

        val batchSlice = pending.take(50)
        return try {
            val batch = db.batch()
            val historyRef = db.collection("families").document(familyId).collection("history")
            val uploadedTimestamps = mutableSetOf<Long>()

            for (item in batchSlice) {
                val enc = CryptoHelper.encrypt("${item.latitude}|${item.longitude}", masterKey)
                val point = EncryptedHistoryPoint(
                    uid = item.uid,
                    encryptedCoords = enc.ciphertextBase64,
                    iv = enc.ivBase64,
                    timestamp = item.timestamp
                )
                batch.set(historyRef.document(), point)
                uploadedTimestamps.add(item.timestamp)
            }

            withTimeout(6000L) {
                batch.commit().await()
            }
            OfflineRouteQueueHelper.removeUploadedTimestamps(context, uploadedTimestamps)
            val remaining = OfflineRouteQueueHelper.peekAll(context)
                .count { it.uid == uid && it.familyId == familyId }
            AppDiagnosticLogHelper.logBatchSyncSuccess(context, uploadedTimestamps.size, remaining)
            uploadedTimestamps.size
        } catch (e: Exception) {
            AppDiagnosticLogHelper.logSyncIssue(
                context = context,
                operation = "Batch Sync Luar Talian",
                reason = e.message ?: "Gagal komit batch Firestore"
            )
            0
        }
    }

    /**
     * Mengambil sejarah laluan mengikut julat masa hari tertentu (startTimeMs..endTimeMs)
     * menggunakan indeks tunggal timestamp sahaja supaya menjimatkan kuota bacaan Firestore
     * dan bebas daripada ralat Composite Index.
     */
    suspend fun getHistoryForRange(
        targetUid: String,
        startTimeMs: Long,
        endTimeMs: Long,
        context: Context? = null
    ): List<HistoryPoint> {
        val familyId = secureStorage.getFamilyId() ?: return emptyList()
        val masterKey = secureStorage.getFamilyMasterKey() ?: return emptyList()

        val remotePoints = try {
            val snap = withTimeout(6000L) {
                db.collection("families").document(familyId)
                    .collection("history")
                    .whereGreaterThanOrEqualTo("timestamp", startTimeMs)
                    .whereLessThanOrEqualTo("timestamp", endTimeMs)
                    .get()
                    .await()
            }

            snap.documents.mapNotNull { doc ->
                val enc = doc.toObject(EncryptedHistoryPoint::class.java) ?: return@mapNotNull null
                val docUid = enc.uid.ifBlank { doc.getString("uid").orEmpty() }
                if (docUid != targetUid) return@mapNotNull null

                val plain = CryptoHelper.decrypt(enc.encryptedCoords, enc.iv, masterKey) ?: return@mapNotNull null
                val parts = plain.split("|")
                val lat = parts.getOrNull(0)?.toDoubleOrNull() ?: return@mapNotNull null
                val lng = parts.getOrNull(1)?.toDoubleOrNull() ?: return@mapNotNull null
                HistoryPoint(lat, lng, enc.timestamp)
            }
        } catch (_: Exception) {
            emptyList()
        }

        val localQueuedPoints = if (context != null) {
            OfflineRouteQueueHelper.peekAll(context)
                .filter {
                    it.uid == targetUid &&
                        it.familyId == familyId &&
                        it.timestamp in startTimeMs..endTimeMs
                }
                .map { HistoryPoint(it.latitude, it.longitude, it.timestamp) }
        } else {
            emptyList()
        }

        return (remotePoints + localQueuedPoints)
            .distinctBy { it.timestamp }
            .sortedBy { it.timestamp }
    }

    suspend fun get24HourHistory(targetUid: String, context: Context? = null): List<HistoryPoint> {
        val now = System.currentTimeMillis()
        return getHistoryForRange(targetUid, now - 86_400_000L, now, context)
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

    companion object {
        private const val SEVEN_DAYS_MS = 7L * 86_400_000L
    }
}
