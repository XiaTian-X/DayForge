@file:kotlinx.serialization.UseSerializers(com.dayforge.domain.model.ContractStringSerializer::class,
    com.dayforge.domain.model.ContractIntegerSerializer::class)

package com.dayforge.data.repository

import android.content.Intent
import android.net.Uri
import com.dayforge.data.api.decodeSyncReply
import com.dayforge.domain.model.isContractUuid
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Original v4 widget identity, never a v5 ticket or a credential. No calendar expiry for active controls. */
@Serializable
@ConsistentCopyVisibility
data class LegacyWidgetTimerClaim internal constructor(
    val identity: LegacyWidgetRestartClaim,
    val sessionUuid: String?,
    internal val nextSequence: Int?,
    internal val incumbent: LegacyWidgetTimerIncumbent? = null
) {
    internal fun validate() {
        LegacyWidgetRestartClaim.decode(identity.encode())
        require((sessionUuid == null) == (nextSequence == null))
        sessionUuid?.let { require(isContractUuid(it) && requireNotNull(nextSequence) > 1 && incumbent == null) }
        incumbent?.let {
            LegacyWidgetRestartClaim.decode(it.identity.encode())
            require(isContractUuid(it.sessionUuid) && it.nextSequence > 1 && it.identity.habitId != identity.habitId)
            require(it.identity.session() == identity.session() && it.identity.deviceId == identity.deviceId)
        }
    }
    fun encode(): String = Json.encodeToString(this)
    internal fun running(uuid: String, sequence: Int) = copy(sessionUuid = uuid, nextSequence = sequence, incumbent = null)
    internal fun incumbentClaim(): LegacyWidgetTimerClaim = requireNotNull(incumbent).let {
        LegacyWidgetTimerClaim(it.identity, it.sessionUuid, it.nextSequence)
    }
    internal fun attach(intent: Intent) {
        val payload = encode()
        intent.putExtra(EXTRA, payload)
        val digest = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        intent.data = Uri.Builder().scheme("dayforge-legacy-widget-timer").authority(digest)
            .appendPath(intent.action ?: "confirm").build()
    }
    companion object {
        const val PARAMETER = "legacyTimerClaim"
        private const val EXTRA = "com.dayforge.widget.timer.legacy_claim"
        fun decode(value: String): LegacyWidgetTimerClaim {
            require(value.length <= 65_536)
            return decodeSyncReply(value.toByteArray(Charsets.UTF_8), 65_536, serializer(), {}).also { it.validate() }
        }
        internal fun isPresent(intent: Intent?): Boolean = intent?.hasExtra(EXTRA) == true
        internal fun read(intent: Intent?): LegacyWidgetTimerClaim? = intent?.getStringExtra(EXTRA)?.let(::decode)
    }
}

@Serializable
@ConsistentCopyVisibility
internal data class LegacyWidgetTimerIncumbent internal constructor(
    val identity: LegacyWidgetRestartClaim,
    val sessionUuid: String,
    val nextSequence: Int
)
