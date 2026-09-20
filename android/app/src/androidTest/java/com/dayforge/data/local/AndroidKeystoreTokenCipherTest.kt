package com.dayforge.data.local

import android.content.Context
import android.util.Base64
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.security.KeyStore
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real platform crypto; synthetic tokens and a disposable alias never touch the app's key. */
@RunWith(AndroidJUnit4::class)
class AndroidKeystoreTokenCipherTest {
    private val alias = "dayforge.test.tokens.${UUID.randomUUID()}"
    private val cipher = AndroidKeystoreTokenCipher(alias)
    private var storeScope: CoroutineScope? = null
    private var storeFile: File? = null

    @After fun cleanup() = runBlocking {
        storeScope?.coroutineContext?.get(Job)?.cancelAndJoin()
        storeFile?.delete()
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
        Unit
    }

    @Test fun roundTripUsesRandomIvAndNonExportableKey() {
        val secret = "synthetic-access-token-测试"
        val first = cipher.encrypt(secret)
        val second = cipher.encrypt(secret)
        assertTrue(first.startsWith("enc:v1:"))
        assertFalse(first.contains(secret))
        assertNotEquals(first, second)
        assertEquals(secret, AndroidKeystoreTokenCipher(alias).decrypt(first))
        assertEquals(secret, cipher.decrypt(second))
        val key = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.getKey(alias, null)
        assertNotNull(key)
        assertNull(key.encoded)
    }

    @Test fun corruptedTruncatedAndLostKeyCiphertextsFailClosed() {
        val encrypted = cipher.encrypt("synthetic-refresh-token")
        val bytes = Base64.decode(encrypted.removePrefix("enc:v1:"), Base64.NO_WRAP)
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        assertNull(cipher.decrypt("enc:v1:" + Base64.encodeToString(bytes, Base64.NO_WRAP)))
        assertNull(cipher.decrypt("enc:v1:AQID"))
        assertNull(cipher.decrypt("enc:v1:***"))
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
        assertNull(AndroidKeystoreTokenCipher(alias).decrypt(encrypted))
    }

    @Test fun legacyPlaintextRemainsReadableAndCiphertextIsNotEncryptedTwice() {
        assertEquals("legacy-token", cipher.decrypt("legacy-token"))
        val encrypted = cipher.encrypt("synthetic-token")
        assertEquals(encrypted, cipher.encrypt(encrypted))
        assertEquals("synthetic-token", cipher.decrypt(encrypted))
    }

    @Test fun tokenManagerStoresNoPlaintextAndReopensWithTheSameKeystoreKey() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.cacheDir, "token-test-${UUID.randomUUID()}.preferences_pb")
        storeFile = file
        fun newManager(): TokenManager {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            storeScope = scope
            return TokenManager(PreferenceDataStoreFactory.create(scope = scope, produceFile = { file }),
                AndroidKeystoreTokenCipher(alias))
        }
        newManager().saveTokens("synthetic-access-secret", "synthetic-refresh-secret", "member", "account-a", false)
        storeScope!!.coroutineContext[Job]!!.cancelAndJoin()
        val persisted = file.readBytes().toString(Charsets.ISO_8859_1)
        assertFalse(persisted.contains("synthetic-access-secret"))
        assertFalse(persisted.contains("synthetic-refresh-secret"))
        val reopened = newManager()
        assertEquals("synthetic-access-secret", reopened.accessToken.first())
        assertEquals("synthetic-refresh-secret", reopened.refreshToken.first())
        assertEquals("account-a", reopened.userId.first())
    }
}
