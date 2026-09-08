package dev.recally.data.settings

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Symmetric encryption for values at rest in the settings DataStore. The
 * interface exists so unit tests run on the JVM (no AndroidKeyStore there)
 * with a fake cipher over the real DataStore.
 */
interface SettingsCipher {
    fun encrypt(plaintext: String): String

    fun decrypt(ciphertext: String): String
}

/**
 * AES/GCM with a 256-bit key held in the AndroidKeyStore — the key material
 * never leaves the TEE/StrongBox. This is the documented store for the base
 * URL and API key (docs/android.md, "Connecting to the backend"), explicitly
 * not the deprecated SharedPreferences encryption wrapper — the doc carries
 * the why.
 *
 * Format: Base64(iv ‖ ciphertext ‖ GCM tag), one string per value.
 */
class KeystoreSettingsCipher : SettingsCipher {
    private val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun secretKey(): SecretKey {
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec
                .Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_SIZE_BITS)
                .build(),
        )
        return generator.generateKey()
    }

    override fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
    }

    override fun decrypt(ciphertext: String): String {
        val decoded = Base64.decode(ciphertext, Base64.NO_WRAP)
        val iv = decoded.copyOfRange(0, GCM_IV_BYTES)
        val encrypted = decoded.copyOfRange(GCM_IV_BYTES, decoded.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return String(cipher.doFinal(encrypted), Charsets.UTF_8)
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "recally_settings"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_SIZE_BITS = 256
        const val GCM_IV_BYTES = 12
        const val GCM_TAG_BITS = 128
    }
}
