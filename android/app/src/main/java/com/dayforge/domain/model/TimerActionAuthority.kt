package com.dayforge.domain.model

import android.content.Intent
import android.net.Uri
import com.dayforge.data.local.AuthenticationSession
import com.dayforge.data.local.LocalDataSession
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Credential-free action identity. The local writer must verify it again, never treat it as permission. */
@Serializable
@ConsistentCopyVisibility
data class TimerActionAuthority internal constructor(
    internal val accountId: String,
    internal val authenticationGeneration: String,
    internal val serverInstanceId: String?,
    internal val syncEpoch: String?,
    internal val deviceId: String?,
    val habitUuid: String,
    val sessionUuid: String?,
    internal val nextSequence: Int?,
    internal val originalPlan: String?
) {
    internal fun session() = LocalDataSession(AuthenticationSession(accountId, authenticationGeneration), serverInstanceId, syncEpoch)
    internal fun attach(intent: Intent) {
        intent.putExtra(EXTRA, Json.encodeToString(this))
        // Extras do not distinguish PendingIntents. Bind identity to immutable data, including the transition.
        intent.data = Uri.Builder().scheme("dayforge-timer").authority(habitUuid)
            .appendPath(authenticationGeneration).appendPath(sessionUuid ?: "start")
            .appendPath(serverInstanceId ?: "offline").appendPath(syncEpoch ?: "offline")
            .appendPath(deviceId ?: "unregistered")
            .appendPath(nextSequence?.toString() ?: "new").appendPath(intent.action ?: "confirm").build()
    }
    companion object {
        private const val EXTRA = "com.dayforge.timer.authority"
        internal fun read(intent: Intent?): TimerActionAuthority? = intent?.getStringExtra(EXTRA)?.let {
            require(it.length <= 65_536)
            Json.decodeFromString<TimerActionAuthority>(it).also { value ->
                require(listOf(value.accountId, value.authenticationGeneration, value.habitUuid).all(::isContractUuid))
                require((value.serverInstanceId == null) == (value.syncEpoch == null))
                listOfNotNull(value.serverInstanceId, value.syncEpoch, value.deviceId, value.sessionUuid).forEach { id -> require(isContractUuid(id)) }
                require((value.sessionUuid == null) == (value.nextSequence == null))
                require(value.nextSequence == null || value.nextSequence > 1)
                require((value.sessionUuid == null) == (value.originalPlan != null))
            }
        }
    }
}
