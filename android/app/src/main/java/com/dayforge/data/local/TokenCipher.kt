package com.dayforge.data.local

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

interface TokenCipher {
    fun encrypt(value: String): String
    fun decrypt(value: String): String?
}

/** AES-GCM token encryption whose non-exportable key is held by Android Keystore. */
@Singleton
class AndroidKeystoreTokenCipher internal constructor(private val keyAlias: String) : TokenCipher {
    @Inject constructor() : this(KEY_ALIAS)

    override fun encrypt(value: String): String {
        if (value.startsWith(PREFIX)) return value
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val combined = cipher.iv + encrypted
        return PREFIX + Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    override fun decrypt(value: String): String? {
        // Seamlessly read sessions created before encrypted storage. The next
        // login/refresh rewrites them using Keystore encryption.
        if (!value.startsWith(PREFIX)) return value
        return runCatching {
            val combined = Base64.decode(value.removePrefix(PREFIX), Base64.NO_WRAP)
            require(combined.size > IV_LENGTH) { "encrypted token is truncated" }
            val iv = combined.copyOfRange(0, IV_LENGTH)
            val encrypted = combined.copyOfRange(IV_LENGTH, combined.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
            cipher.doFinal(encrypted).toString(Charsets.UTF_8)
        }.getOrNull()
    }

    private fun getOrCreateKey(): SecretKey = synchronized(KEY_LOCK) {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(keyAlias, null) as? SecretKey) ?: KeyGenerator
            .getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
            .apply {
                init(
                    KeyGenParameterSpec.Builder(
                        keyAlias,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build()
                )
            }
            .generateKey()
    }

    private companion object {
        const val PREFIX = "enc:v1:"
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "dayforge.auth.tokens.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LENGTH = 12
        val KEY_LOCK = Any()
    }
}

internal object PlaintextTokenCipher : TokenCipher {
    override fun encrypt(value: String) = value
    override fun decrypt(value: String) = value
}
