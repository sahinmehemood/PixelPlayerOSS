package com.lostf1sh.pixelplayeross.data.security

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Small encrypted store for credentials and TDLib's database encryption key.
 *
 * This deliberately has no plaintext fallback: if Android Keystore cannot open the
 * encrypted preferences, callers receive an error instead of silently weakening storage.
 */
@Singleton
class EncryptedSecretStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val preferences: SharedPreferences by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "pixelplayer_secure_secrets",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun getString(key: String, defaultValue: String? = null): String? =
        preferences.getString(key, defaultValue)

    fun putString(key: String, value: String) {
        preferences.edit().putString(key, value).apply()
    }

    fun remove(key: String) {
        preferences.edit().remove(key).apply()
    }

    fun getOrCreateBytes(key: String, size: Int): ByteArray {
        require(size > 0)
        val encoded = getString(key)
        if (!encoded.isNullOrBlank()) {
            val decoded = Base64.decode(encoded, Base64.NO_WRAP)
            require(decoded.size == size) { "Stored secret '$key' has an invalid length." }
            return decoded
        }
        val generated = ByteArray(size).also(SecureRandom()::nextBytes)
        putString(key, Base64.encodeToString(generated, Base64.NO_WRAP))
        return generated
    }
}
