@file:kotlinx.serialization.UseSerializers(com.dayforge.domain.model.ContractStringSerializer::class,
    com.dayforge.domain.model.ContractLongSerializer::class)

package com.dayforge.data.repository

import android.content.Intent
import android.net.Uri
import com.dayforge.data.api.decodeSyncReply
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.domain.model.TimerActionAuthority
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** Original completed transition plus the plan displayed by its goal dialog. Never an edit permission. */
@Serializable
@ConsistentCopyVisibility
data class TimerCompletionFollowUpClaim internal constructor(
    val habitId: Long,
    val source: TimerActionAuthority,
    val displayedPlan: JsonObject
) {
    internal fun validate() {
        require(habitId > 0)
        source.validate()
        require(source.sessionUuid != null && source.nextSequence != null)
    }
    internal fun attach(intent: Intent) {
        val payload = Json.encodeToString(this)
        intent.putExtra(EXTRA, payload)
        val hash = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        intent.data = Uri.Builder().scheme("dayforge-timer-completion").authority(hash).build()
    }
    companion object {
        private const val EXTRA = "com.dayforge.timer.completion_follow_up"
        internal fun displayed(habit: HabitEntity, source: TimerActionAuthority): TimerCompletionFollowUpClaim {
            require(habit.uuid == source.habitUuid && habit.appearance != null)
            return TimerCompletionFollowUpClaim(habit.id, source, NextStructureMapper.writePlan(habit)).also { it.validate() }
        }
        internal fun read(intent: Intent): TimerCompletionFollowUpClaim? = intent.getStringExtra(EXTRA)?.let {
            require(it.length <= 65_536)
            decodeSyncReply(it.toByteArray(Charsets.UTF_8), 65_536, serializer(), {}).also { claim -> claim.validate() }
        }
    }
}

/** Produced only by an authenticated, fully audited Room snapshot. */
@ConsistentCopyVisibility
data class TimerCompletionSnapshot internal constructor(val habit: HabitEntity, val progress: Int,
    val claim: TimerCompletionFollowUpClaim) {
    val goalReached: Boolean get() = habit.targetCycles?.let { progress >= it } == true
}
