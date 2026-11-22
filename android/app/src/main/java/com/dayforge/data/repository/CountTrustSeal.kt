package com.dayforge.data.repository

import android.database.Cursor
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.sqlite.db.SupportSQLiteDatabase
import com.dayforge.data.history.CountTrustIndex
import com.dayforge.data.history.HistoryBlockCodec
import com.dayforge.data.local.LocalCoreWriteAccess
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import kotlinx.serialization.json.*

/** Device-local authentication of a complete root tuple. No authentication method creates a key,
 * repairs storage or grants a SQL/display capability. Whole-database rollback is not added here.
 */
internal class CountTrustSeal {
    data class Header(val binding: String, val schemaHash: String, val root: HistoryBlockCodec.Ref,
        val revision: Long, val generation: Long, private val authentication: List<Byte>,
        val validator: String = VALIDATOR) {
        fun mac(): ByteArray = authentication.toByteArray()
    }

    fun prepare(binding: String, schemaHash: String, root: HistoryBlockCodec.Ref,
        revision: Long, generation: Long, validator: String = VALIDATOR): Header {
        val body = body(binding, schemaHash, root, revision, generation, validator)
        val key = key() ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                .setDigests(KeyProperties.DIGEST_SHA256).build()); generateKey()
        }
        return Header(binding, schemaHash, root, revision, generation, tag(key, body).toList(), validator)
    }

    /** Exact schema-envelope conversion; missing old key is fatal and is never recreated. */
    internal fun prepareWithExistingKey(binding: String, schemaHash: String, root: HistoryBlockCodec.Ref,
        revision: Long, generation: Long, validator: String): Header {
        val key = requireNotNull(key()) { "COUNT_TRUST_EXISTING_KEY_REQUIRED" }
        return Header(binding, schemaHash, root, revision, generation,
            tag(key, body(binding, schemaHash, root, revision, generation, validator)).toList(), validator)
    }

    fun matches(header: Header, binding: String, schemaHash: String): Boolean {
        if (header.binding != binding || header.schemaHash != schemaHash) return false
        val key = key() ?: return false
        return MessageDigest.isEqual(header.mac(), tag(key,
            body(header.binding, header.schemaHash, header.root, header.revision, header.generation, header.validator)))
    }

    private fun key(): SecretKey? = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.getKey(ALIAS, null) as? SecretKey
    private fun tag(key: SecretKey, body: ByteArray) = Mac.getInstance("HmacSHA256").run { init(key); doFinal(body) }

    companion object {
        const val FORMAT = 1
        const val LEGACY_VALIDATOR = "count-trust/plain-recurring-v1/native-members-and-rules/2026-10-22"
        const val LEGACY_ROUND_VALIDATOR = "count-trust/round-recurring-v2/native-members-and-rules/2026-10-23"
        const val LEGACY_ENVIRONMENT_VALIDATOR = "count-trust/round-recurring-v3/environment-and-native-rules/2026-10-24"
        const val LEGACY_DISPLAY_VALIDATOR = "count-trust/round-recurring-v4/compact-calendar-and-native-rules/2026-10-25"
        const val LEGACY_CHECK_VALIDATOR = "count-trust/round-recurring-v5/check-presence-and-native-rules/2026-11-02"
        const val LEGACY_TIMER_VALIDATOR = "count-trust/round-recurring-v6/timer-millis-and-native-rules/2026-11-02"
        const val LEGACY_EXPLICIT_TIMER_VALIDATOR = "count-trust/round-recurring-v7/explicit-timer-birth-and-native-rules/2026-11-03"
        const val LEGACY_ONCE_VALIDATOR = "count-trust/round-recurring-v8/once-current-state-and-native-rules/2026-11-03"
        const val VALIDATOR = "count-trust/round-recurring-v9/metric-named-ranges-and-native-rules/2026-11-03"
        private const val ALIAS = "dayforge.count.trust.root.v1"

        fun binding(access: LocalCoreWriteAccess, storageId: String, validator: String = VALIDATOR): String = CountTrustIndex.hash(buildJsonObject {
            put("account", access.session.authentication.userId)
            put("server", access.session.serverInstanceId?.let(::JsonPrimitive) ?: JsonNull)
            put("epoch", access.session.syncEpoch?.let(::JsonPrimitive) ?: JsonNull)
            put("device", access.capturedDeviceId?.let(::JsonPrimitive) ?: JsonNull)
            put("storage", storageId); put("validator", validator)
        }.toString().toByteArray(Charsets.UTF_8))

        fun read(sql: SupportSQLiteDatabase): Header? = sql.query(
            "SELECT id,binding,format,validator,schemaHash,rootHash,rootHeight,rootCount,revision,generation,typeof(mac),length(mac),mac FROM count_trust_root"
        ).use { c ->
            if (!c.moveToFirst()) return@use null
            require(listOf(0, 2, 6, 7, 8, 9, 11).all { c.getType(it) == Cursor.FIELD_TYPE_INTEGER } &&
                listOf(1, 3, 4, 5, 10).all { c.getType(it) == Cursor.FIELD_TYPE_STRING }) { "COUNT_TRUST_ROOT_TYPE" }
            require(c.getLong(0) == 1L && c.getLong(2) == FORMAT.toLong() && c.getString(3) in setOf(VALIDATOR, LEGACY_ONCE_VALIDATOR, LEGACY_EXPLICIT_TIMER_VALIDATOR, LEGACY_TIMER_VALIDATOR, LEGACY_CHECK_VALIDATOR, LEGACY_DISPLAY_VALIDATOR, LEGACY_ENVIRONMENT_VALIDATOR, LEGACY_ROUND_VALIDATOR, LEGACY_VALIDATOR) &&
                c.getString(10) == "blob" && c.getLong(11) == 32L)
            val binding = c.getString(1); val schema = c.getString(4); val digest = c.getString(5)
            require(listOf(binding, schema, digest).all(CountTrustIndex::validHash) &&
                c.getLong(6) in 0..HistoryBlockCodec.MAX_DEPTH.toLong() && c.getLong(7) > 0 &&
                c.getLong(8) > 0 && c.getLong(9) >= 0)
            Header(binding, schema, CountTrustIndex.ref(binding, c.getInt(6), c.getLong(7), digest),
                c.getLong(8), c.getLong(9), c.getBlob(12).toList(), c.getString(3)).also { require(!c.moveToNext()) }
        }

        fun store(sql: SupportSQLiteDatabase, header: Header) {
            sql.execSQL("INSERT OR REPLACE INTO count_trust_root(id,binding,format,validator,schemaHash,rootHash,rootHeight,rootCount,revision,generation,mac) VALUES(1,?,?,?,?,?,?,?,?,?,?)",
                arrayOf(header.binding, FORMAT, header.validator, header.schemaHash, header.root.digest,
                    header.root.height, header.root.count, header.revision, header.generation, header.mac()))
            require(read(sql) == header)
            sql.execSQL("UPDATE count_trust_control SET dirty=0 WHERE id=1 AND generation=?", arrayOf(header.generation))
            sql.query("SELECT generation,dirty FROM count_trust_control WHERE id=1").use {
                require(it.moveToFirst() && it.getLong(0) == header.generation && it.getLong(1) == 0L && !it.moveToNext())
            }
        }

        private fun body(binding: String, schemaHash: String, root: HistoryBlockCodec.Ref,
            revision: Long, generation: Long, validator: String): ByteArray {
            require(validator in setOf(VALIDATOR, LEGACY_ONCE_VALIDATOR, LEGACY_EXPLICIT_TIMER_VALIDATOR, LEGACY_TIMER_VALIDATOR, LEGACY_CHECK_VALIDATOR, LEGACY_DISPLAY_VALIDATOR, LEGACY_ENVIRONMENT_VALIDATOR, LEGACY_ROUND_VALIDATOR, LEGACY_VALIDATOR))
            require(listOf(binding, schemaHash, root.digest).all(CountTrustIndex::validHash) &&
                root == CountTrustIndex.ref(binding, root.height, root.count, root.digest) &&
                root.height in 0..HistoryBlockCodec.MAX_DEPTH && root.count > 0 && revision > 0 && generation >= 0)
            return buildJsonObject {
                put("domain", "DayForge/count-trust-root"); put("format", FORMAT); put("validator", validator)
                put("binding", binding); put("schema", schemaHash); put("index", "count-trust-v1")
                put("scope", "directory/1970-01/null-round/open-bounds")
                put("height", root.height); put("count", root.count); put("digest", root.digest)
                put("revision", revision); put("generation", generation)
            }.toString().toByteArray(Charsets.UTF_8)
        }
    }
}
