package com.dayforge.data.repository

import com.dayforge.data.local.entity.CountCommandRecoveryRootEntity
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.SecretKey

/** Normal primary operations only look up a key. Missing/temporarily unavailable keys are
 * not cache misses and cannot silently create a replacement. Trusted initialization uses a
 * separate real bootstrap/lifecycle permit, never this normal operation reader/advancer.
 */
internal class CountCommandSeal {
    class Authenticated private constructor(val header: CountCommandRootCodec.Header,
        private val row: CountCommandRecoveryRootEntity) {
        fun native(): CountCommandRecoveryRootEntity = row.copy(mac = row.mac.copyOf())
        companion object {
            internal fun verify(row: CountCommandRecoveryRootEntity, storage: String, schema: String): Authenticated {
                val header = CountCommandRootCodec.fromEntity(row)
                require(header.storageId == storage && header.schemaHash == schema) { "COMMAND_PRIMARY_BINDING_CHANGED" }
                require(MessageDigest.isEqual(row.mac, tag(requiredKey(), CountCommandRootCodec.body(header)))) { "COMMAND_PRIMARY_MAC_INVALID" }
                return Authenticated(header, row.copy(mac = row.mac.copyOf()))
            }
        }
    }
    fun authenticate(row: CountCommandRecoveryRootEntity, storage: String, schema: String, anchor: () -> String?): Authenticated =
        Authenticated.verify(row, storage, schema).also {
            // MAC is checked before local continuity. Neither failure is an optional miss.
            CountCommandRootAnchor.requireMatches(it.header, row.mac, anchor())
        }

    fun advance(old: Authenticated, fresh: CountCommandRootCodec.Header): CountCommandRecoveryRootEntity {
        CountCommandRootCodec.requireNext(old.header, fresh)
        return fresh.entity(tag(requiredKey(), CountCommandRootCodec.body(fresh)))
    }

    internal fun enrollRetained(old: Authenticated, plan: CountCommandRetainedEnrollmentPlan): CountCommandRecoveryRootEntity {
        val fresh = plan.headerForSigning(old)
        return fresh.entity(tag(requiredKey(), CountCommandRootCodec.body(fresh)))
    }

    internal fun upgradeMetric(old: Authenticated, fresh: CountCommandRootCodec.Header): CountCommandRecoveryRootEntity {
        CountCommandRootCodec.requireMetricUpgrade(old.header, fresh)
        return fresh.entity(tag(requiredKey(), CountCommandRootCodec.body(fresh)))
    }

    /** Only the closed original child-then-parent plan may advance by two logical steps.
     * H1 is validated by requireNext inside the plan and is never represented as Authenticated. */
    suspend fun advanceMetricDelivery(old: Authenticated,
        plan: CountCommandMetricDeliveryPlan): CountCommandRecoveryRootEntity {
        val fresh = plan.headerForSigning(old)
        return fresh.entity(tag(requiredKey(), CountCommandRootCodec.body(fresh)))
    }

    companion object {
        const val ALIAS = "dayforge.count.command.primary.v1"
        private fun requiredKey(): SecretKey = requireNotNull(KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            .getKey(ALIAS, null) as? SecretKey) { "COMMAND_PRIMARY_KEY_MISSING" }
        private fun tag(key: SecretKey, body: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run { init(key); doFinal(body) }
    }
}
