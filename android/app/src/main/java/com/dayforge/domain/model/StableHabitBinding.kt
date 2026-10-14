package com.dayforge.domain.model

import com.dayforge.data.api.decodeSyncReply
import com.dayforge.data.local.LocalDataSession
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Persistent identity only, NEVER permission to read or act on a current replica. */
@Serializable
@ConsistentCopyVisibility
data class StableHabitBinding internal constructor(
    internal val accountId: String,
    internal val serverInstanceId: String?,
    internal val provisionalAuthenticationGeneration: String?,
    val habitUuid: String
) {
    internal fun validate() {
        require(isContractUuid(accountId) && isContractUuid(habitUuid))
        require((serverInstanceId == null) != (provisionalAuthenticationGeneration == null))
        listOfNotNull(serverInstanceId, provisionalAuthenticationGeneration).forEach { require(isContractUuid(it)) }
    }

    /** A returned promotion must be persisted with original-value CAS before it is used. */
    internal fun resolve(session: LocalDataSession, expectedHabitUuid: String): StableHabitBinding? {
        validate()
        if (accountId != session.authentication.userId || habitUuid != expectedHabitUuid) return null
        return if (serverInstanceId != null) takeIf { serverInstanceId == session.serverInstanceId }
        else if (provisionalAuthenticationGeneration != session.authentication.generation) null
        else if (session.serverInstanceId == null) this
        else capture(session, expectedHabitUuid)
    }

    internal fun encode(): String = Json.encodeToString(this.also { it.validate() })

    companion object {
        internal fun capture(session: LocalDataSession, habitUuid: String) = StableHabitBinding(
            session.authentication.userId, session.serverInstanceId,
            session.authentication.generation.takeIf { session.serverInstanceId == null }, habitUuid
        ).also { it.validate() }

        internal fun decodeOrNull(raw: String?): StableHabitBinding? {
            if (raw == null || raw.length > 4_096) return null
            return try {
                decodeSyncReply(raw.toByteArray(Charsets.UTF_8), 4_096, serializer(), {}).also { it.validate() }
            } catch (_: IllegalArgumentException) { null }
            catch (_: com.dayforge.data.api.NextSyncReplyInvalid) { null }
        }
    }
}
