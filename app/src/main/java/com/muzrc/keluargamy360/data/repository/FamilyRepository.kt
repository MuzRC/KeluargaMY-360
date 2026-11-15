package com.muzrc.keluargamy360.data.repository

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.UserProfileChangeRequest
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.muzrc.keluargamy360.data.local.SecureStorage
import com.muzrc.keluargamy360.data.model.FamilyGroup
import com.muzrc.keluargamy360.util.CryptoHelper
import kotlinx.coroutines.tasks.await
import java.security.SecureRandom

class FamilyRepository(
    private val secureStorage: SecureStorage,
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance()
) {
    private val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

    private fun generateInviteCode(): String {
        val random = SecureRandom()
        return (1..6).map { chars[random.nextInt(chars.length)] }.joinToString("")
    }

    suspend fun createFamily(familyName: String, securityPin: String): Result<FamilyGroup> {
        val uid = auth.currentUser?.uid
            ?: return Result.failure(Exception("Sesi log masuk tamat. Sila log masuk semula."))

        return try {
            val familyRef = db.collection("families").document()
            val masterKey = CryptoHelper.generateFamilyMasterKey()
            val salt = CryptoHelper.generateSalt()
            val wrapped = CryptoHelper.wrapKeyWithPin(masterKey, securityPin, salt)
            val inviteCode = generateInviteCode()

            val group = FamilyGroup(
                familyId = familyRef.id,
                familyName = familyName.trim(),
                inviteCode = inviteCode,
                ownerId = uid,
                memberIds = listOf(uid),
                wrappedMasterKey = wrapped.ciphertextBase64,
                keyIv = wrapped.ivBase64,
                keySalt = salt
            )

            familyRef.set(group).await()
            db.collection("users").document(uid)
                .update("familyId", familyRef.id)
                .await()

            secureStorage.saveFamilySession(familyRef.id, masterKey)
            Result.success(group)
        } catch (e: Exception) {
            Result.failure(Exception("Gagal mencipta keluarga: ${e.localizedMessage}"))
        }
    }

    suspend fun joinFamily(inviteCode: String, securityPin: String): Result<FamilyGroup> {
        val uid = auth.currentUser?.uid
            ?: return Result.failure(Exception("Sesi log masuk tamat. Sila log masuk semula."))

        return try {
            val cleanCode = inviteCode.trim().uppercase()
            val query = db.collection("families")
                .whereEqualTo("inviteCode", cleanCode)
                .limit(1)
                .get()
                .await()

            if (query.isEmpty) {
                return Result.failure(Exception("Kod jemputan '$cleanCode' tidak dijumpai."))
            }

            val doc = query.documents.first()
            val group = doc.toObject(FamilyGroup::class.java)
                ?: return Result.failure(Exception("Data kumpulan keluarga tidak sah."))

            val masterKey = CryptoHelper.unwrapKeyWithPin(
                wrappedKeyBase64 = group.wrappedMasterKey,
                ivBase64 = group.keyIv,
                pin = securityPin,
                saltBase64 = group.keySalt
            ) ?: return Result.failure(Exception("PIN Keselamatan salah. Tidak dapat menyahsulit kunci keluarga."))

            db.collection("families").document(group.familyId)
                .update("memberIds", FieldValue.arrayUnion(uid))
                .await()

            db.collection("users").document(uid)
                .update("familyId", group.familyId)
                .await()

            secureStorage.saveFamilySession(group.familyId, masterKey)
            Result.success(group.copy(memberIds = group.memberIds + uid))
        } catch (e: Exception) {
            Result.failure(Exception("Gagal menyertai keluarga: ${e.localizedMessage}"))
        }
    }

    suspend fun unlockFamilyWithPin(familyId: String, securityPin: String): Result<FamilyGroup> {
        return try {
            val doc = db.collection("families").document(familyId).get().await()
            val group = doc.toObject(FamilyGroup::class.java)
                ?: return Result.failure(Exception("Data kumpulan keluarga tidak dijumpai."))

            val masterKey = CryptoHelper.unwrapKeyWithPin(
                wrappedKeyBase64 = group.wrappedMasterKey,
                ivBase64 = group.keyIv,
                pin = securityPin,
                saltBase64 = group.keySalt
            ) ?: return Result.failure(Exception("PIN Keselamatan salah. Sila cuba lagi."))

            secureStorage.saveFamilySession(group.familyId, masterKey)
            Result.success(group)
        } catch (e: Exception) {
            Result.failure(Exception("Gagal membuka kunci keluarga: ${e.localizedMessage}"))
        }
    }

    suspend fun getFamilyGroup(familyId: String): FamilyGroup? {
        return try {
            val doc = db.collection("families").document(familyId).get().await()
            doc.toObject(FamilyGroup::class.java)
        } catch (e: Exception) {
            null
        }
    }

    suspend fun updateDisplayName(newName: String): Result<Unit> {
        val user = auth.currentUser ?: return Result.failure(Exception("Tidak log masuk"))
        val trimmed = newName.trim()
        if (trimmed.isBlank()) return Result.failure(Exception("Nama tidak boleh kosong"))

        return try {
            val req = UserProfileChangeRequest.Builder().setDisplayName(trimmed).build()
            user.updateProfile(req).await()
            db.collection("users").document(user.uid).update("name", trimmed).await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun removeMemberFromFamily(familyId: String, targetUid: String): Result<Unit> {
        return try {
            db.collection("families").document(familyId)
                .update("memberIds", FieldValue.arrayRemove(targetUid))
                .await()
            db.collection("families").document(familyId)
                .collection("locations").document(targetUid)
                .delete()
                .await()
            db.collection("users").document(targetUid)
                .update("familyId", "")
                .await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun leaveFamily(familyId: String): Result<Unit> {
        val uid = auth.currentUser?.uid ?: return Result.failure(Exception("Tidak log masuk"))
        return removeMemberFromFamily(familyId, uid)
    }
}
