package com.dayforge.reminder

import android.content.Intent
import android.os.Bundle
import java.util.UUID

/** A navigation claim, never authority to read an account's data. */
data class ReminderDetailRequest(val habitId: Long, val habitUuid: String, val scope: String) {
    fun save(): Bundle = Bundle().apply {
        putLong("id", habitId); putString("uuid", habitUuid); putString("scope", scope)
    }

    companion object {
        fun decode(intent: Intent): ReminderDetailRequest? {
            val uri = intent.data ?: return null
            if (uri.scheme != "dayforge-reminder" || uri.authority != "detail" || uri.pathSegments.size != 1 ||
                intent.getStringExtra(ReminderNotificationBuilder.EXTRA_NAVIGATION_DESTINATION) != "habit_detail") return null
            val identity = uri.pathSegments.single()
            val separator = identity.lastIndexOf('|')
            if (separator <= 0) return null // Unscoped legacy local IDs are deliberately not adopted.
            return checked(intent.getLongExtra(ReminderNotificationBuilder.EXTRA_HABIT_ID, 0),
                identity.substring(separator + 1), identity.substring(0, separator))
        }

        fun restore(bundle: Bundle?): ReminderDetailRequest? = bundle?.let {
            checked(it.getLong("id"), it.getString("uuid"), it.getString("scope"))
        }

        private fun checked(id: Long, uuid: String?, scope: String?): ReminderDetailRequest? {
            if (id <= 0 || uuid == null || scope.isNullOrBlank()) return null
            return try {
                if (UUID.fromString(uuid).toString() != uuid) null else ReminderDetailRequest(id, uuid, scope)
            } catch (error: IllegalArgumentException) { null }
        }
    }
}
