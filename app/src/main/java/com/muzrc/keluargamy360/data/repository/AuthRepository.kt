package com.muzrc.keluargamy360.data.repository

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.UserProfileChangeRequest
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.muzrc.keluargamy360.R
import com.muzrc.keluargamy360.data.model.User
import kotlinx.coroutines.tasks.await

class AuthRepository(
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance()
) {
    val currentUser: FirebaseUser?
        get() = auth.currentUser

    suspend fun signInWithEmail(email: String, password: String): Result<User> {
        return try {
            val result = auth.signInWithEmailAndPassword(email.trim(), password).await()
            val firebaseUser = result.user ?: return Result.failure(Exception("Gagal mendapatkan maklumat pengguna."))
            val userProfile = syncUserProfile(firebaseUser)
            Result.success(userProfile)
        } catch (e: Exception) {
            Result.failure(Exception(mapFirebaseError(e.message)))
        }
    }

    suspend fun registerWithEmail(name: String, email: String, password: String): Result<User> {
        return try {
            val result = auth.createUserWithEmailAndPassword(email.trim(), password).await()
            val firebaseUser = result.user ?: return Result.failure(Exception("Pendaftaran gagal."))

            val profileUpdates = UserProfileChangeRequest.Builder()
                .setDisplayName(name.trim())
                .build()
            firebaseUser.updateProfile(profileUpdates).await()

            val newUser = User(
                uid = firebaseUser.uid,
                name = name.trim(),
                email = email.trim(),
                photoUrl = ""
            )
            db.collection("users").document(firebaseUser.uid)
                .set(newUser, SetOptions.merge())
                .await()

            Result.success(newUser)
        } catch (e: Exception) {
            Result.failure(Exception(mapFirebaseError(e.message)))
        }
    }

    suspend fun signInWithGoogle(activityContext: Context): Result<User> {
        return try {
            val credentialManager = CredentialManager.create(activityContext)
            val webClientId = activityContext.getString(R.string.default_web_client_id)

            val googleIdOption = GetGoogleIdOption.Builder()
                .setFilterByAuthorizedAccounts(false)
                .setServerClientId(webClientId)
                .setAutoSelectEnabled(false)
                .build()

            val request = GetCredentialRequest.Builder()
                .addCredentialOption(googleIdOption)
                .build()

            val response = credentialManager.getCredential(
                request = request,
                context = activityContext
            )

            val credential = response.credential
            if (credential is CustomCredential &&
                credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
            ) {
                val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
                val firebaseCredential = GoogleAuthProvider.getCredential(googleIdTokenCredential.idToken, null)
                val authResult = auth.signInWithCredential(firebaseCredential).await()
                val firebaseUser = authResult.user
                    ?: return Result.failure(Exception("Pengesahan Google gagal."))

                val userProfile = syncUserProfile(firebaseUser)
                Result.success(userProfile)
            } else {
                Result.failure(Exception("Jenis kelayakan Google tidak sah."))
            }
        } catch (e: GetCredentialCancellationException) {
            Result.failure(Exception("Log masuk Google dibatalkan."))
        } catch (e: Exception) {
            Result.failure(Exception("Ralat Google Sign-In: ${e.localizedMessage ?: "Sila cuba lagi."}"))
        }
    }

    suspend fun getCurrentUserProfile(): User? {
        val uid = auth.currentUser?.uid ?: return null
        return try {
            val doc = db.collection("users").document(uid).get().await()
            if (doc.exists()) {
                doc.toObject(User::class.java)
            } else {
                auth.currentUser?.let { syncUserProfile(it) }
            }
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun syncUserProfile(firebaseUser: FirebaseUser): User {
        val docRef = db.collection("users").document(firebaseUser.uid)
        val snapshot = docRef.get().await()

        return if (snapshot.exists()) {
            val existing = snapshot.toObject(User::class.java) ?: User(uid = firebaseUser.uid)
            val updated = existing.copy(
                name = existing.name.ifBlank { firebaseUser.displayName ?: "Ahli Keluarga" },
                email = firebaseUser.email ?: existing.email,
                photoUrl = firebaseUser.photoUrl?.toString() ?: existing.photoUrl,
                lastSeen = System.currentTimeMillis()
            )
            docRef.set(updated, SetOptions.merge()).await()
            updated
        } else {
            val freshUser = User(
                uid = firebaseUser.uid,
                name = firebaseUser.displayName ?: "Ahli Keluarga",
                email = firebaseUser.email ?: "",
                photoUrl = firebaseUser.photoUrl?.toString() ?: ""
            )
            docRef.set(freshUser).await()
            freshUser
        }
    }

    fun signOut() {
        auth.signOut()
    }

    private fun mapFirebaseError(raw: String?): String {
        val msg = raw.orEmpty()
        return when {
            msg.contains("password is invalid", ignoreCase = true) ||
            msg.contains("INVALID_LOGIN_CREDENTIALS", ignoreCase = true) ->
                "Emel atau kata laluan tidak tepat."
            msg.contains("no user record", ignoreCase = true) ->
                "Akaun tidak dijumpai. Sila daftar akaun baharu."
            msg.contains("already in use", ignoreCase = true) ->
                "Alamat emel ini telah didaftarkan."
            msg.contains("badly formatted", ignoreCase = true) ->
                "Format alamat emel tidak sah."
            msg.contains("at least 6 characters", ignoreCase = true) ->
                "Kata laluan mesti sekurang-kurangnya 6 aksara."
            msg.contains("network error", ignoreCase = true) ->
                "Tiada sambungan internet. Sila semak rangkaian anda."
            else -> "Ralat pengesahan: $msg"
        }
    }
}
