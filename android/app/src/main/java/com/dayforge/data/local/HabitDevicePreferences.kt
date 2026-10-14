package com.dayforge.data.local

import androidx.datastore.preferences.core.stringPreferencesKey
import com.dayforge.data.api.decodeSyncReply
import com.dayforge.domain.model.StableHabitBinding
import com.dayforge.domain.model.isContractUuid
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Device-only preferences. Pending is a completion hint, not a durable metric draft or fact. */
@Serializable
internal data class HabitDevicePreferences(
    val binding: StableHabitBinding,
    val notifications: Boolean = true,
    val neverAsk: Boolean = false,
    val pending: Boolean = false,
    val pendingEpoch: String? = null
) {
    fun validate() {
        binding.validate()
        require(pendingEpoch == null || isContractUuid(pendingEpoch))
        require(if (pending && binding.serverInstanceId != null) pendingEpoch != null else pendingEpoch == null)
    }

    fun encode(): String = Json.encodeToString(this.also { it.validate() })

    companion object {
        const val PREFIX = "stable_habit_preferences_v1_"
        fun key(binding: StableHabitBinding) = stringPreferencesKey(PREFIX + MessageDigest.getInstance("SHA-256")
            .digest(binding.encode().toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) })

        fun decode(raw: String?): HabitDevicePreferences? {
            if (raw == null || raw.length > 4_096) return null
            return try {
                decodeSyncReply(raw.toByteArray(Charsets.UTF_8), 4_096, serializer(), {}).also { it.validate() }
            } catch (_: IllegalArgumentException) { null }
            catch (_: com.dayforge.data.api.NextSyncReplyInvalid) { null }
        }
    }
}
