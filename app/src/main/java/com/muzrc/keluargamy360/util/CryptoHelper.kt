package com.muzrc.keluargamy360.util

import android.util.Base64
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object CryptoHelper {

    private const val AES_ALGORITHM = "AES/GCM/NoPadding"
    private const val GCM_TAG_LENGTH = 128
    private const val GCM_IV_LENGTH = 12
    private const val PBKDF2_ITERATIONS = 65536
    private const val KEY_SIZE = 256

    data class EncryptedPayload(
        val ciphertextBase64: String,
        val ivBase64: String
    )

    /**
     * Menjana Kunci Utama Keluarga (AES-256) baharu dalam format Base64.
     */
    fun generateFamilyMasterKey(): String {
        val keyGen = KeyGenerator.getInstance("AES")
        keyGen.init(KEY_SIZE, SecureRandom())
        val secretKey = keyGen.generateKey()
        return Base64.encodeToString(secretKey.encoded, Base64.NO_WRAP)
    }

    /**
     * Menyulitkan teks (cth: "5.3473|115.7455|45.0|88") menggunakan Kunci Keluarga.
     */
    fun encrypt(plainText: String, base64Key: String): EncryptedPayload {
        val keyBytes = Base64.decode(base64Key, Base64.NO_WRAP)
        val secretKey = SecretKeySpec(keyBytes, "AES")

        val iv = ByteArray(GCM_IV_LENGTH)
        SecureRandom().nextBytes(iv)

        val cipher = Cipher.getInstance(AES_ALGORITHM)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, spec)

        val encryptedBytes = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))

        return EncryptedPayload(
            ciphertextBase64 = Base64.encodeToString(encryptedBytes, Base64.NO_WRAP),
            ivBase64 = Base64.encodeToString(iv, Base64.NO_WRAP)
        )
    }

    /**
     * Menyahsulit teks tersulit kembali kepada koordinat asal.
     */
    fun decrypt(ciphertextBase64: String, ivBase64: String, base64Key: String): String? {
        return try {
            val keyBytes = Base64.decode(base64Key, Base64.NO_WRAP)
            val secretKey = SecretKeySpec(keyBytes, "AES")
            val iv = Base64.decode(ivBase64, Base64.NO_WRAP)
            val cipherBytes = Base64.decode(ciphertextBase64, Base64.NO_WRAP)

            val cipher = Cipher.getInstance(AES_ALGORITHM)
            val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)

            val decryptedBytes = cipher.doFinal(cipherBytes)
            String(decryptedBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Membungkus Kunci Keluarga menggunakan PIN Keselamatan Kumpulan sebelum disimpan ke Firestore
     * supaya hanya ahli yang tahu PIN boleh menyahsulit Kunci Keluarga.
     */
    fun wrapKeyWithPin(familyKeyBase64: String, pin: String, saltBase64: String): EncryptedPayload {
        val derivedKey = deriveKeyFromPin(pin, saltBase64)
        return encrypt(familyKeyBase64, derivedKey)
    }

    /**
     * Membuka Kunci Keluarga menggunakan PIN Keselamatan Kumpulan.
     */
    fun unwrapKeyWithPin(
        wrappedKeyBase64: String,
        ivBase64: String,
        pin: String,
        saltBase64: String
    ): String? {
        val derivedKey = deriveKeyFromPin(pin, saltBase64)
        return decrypt(wrappedKeyBase64, ivBase64, derivedKey)
    }

    fun generateSalt(): String {
        val salt = ByteArray(16)
        SecureRandom().nextBytes(salt)
        return Base64.encodeToString(salt, Base64.NO_WRAP)
    }

    private fun deriveKeyFromPin(pin: String, saltBase64: String): String {
        val salt = Base64.decode(saltBase64, Base64.NO_WRAP)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(pin.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_SIZE)
        val tmp: SecretKey = factory.generateSecret(spec)
        return Base64.encodeToString(tmp.encoded, Base64.NO_WRAP)
    }
}
