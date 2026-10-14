package com.dayforge.domain.model

import android.content.Intent
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** The incumbent seen by a widget, including explicit absence. Not a permission or fresh capture. */
@Serializable
@ConsistentCopyVisibility
data class TimerStartGuard internal constructor(
    internal val incumbentHabitId: Long?,
    internal val incumbent: TimerActionAuthority?
) {
    internal fun validate() {
        require((incumbentHabitId == null) == (incumbent == null))
        require(incumbentHabitId == null || incumbentHabitId > 0)
        incumbent?.let { it.validate(); require(it.sessionUuid != null) }
    }

    internal fun attach(intent: Intent) { intent.putExtra(EXTRA, Json.encodeToString(this)) }

    companion object {
        private const val EXTRA = "com.dayforge.timer.start_guard"
        internal fun read(intent: Intent?): TimerStartGuard? = intent?.getStringExtra(EXTRA)?.let {
            require(it.length <= 65_536)
            com.dayforge.data.api.decodeSyncReply(it.toByteArray(Charsets.UTF_8), 65_536, serializer(), {}).also { guard -> guard.validate() }
        }
    }
}
