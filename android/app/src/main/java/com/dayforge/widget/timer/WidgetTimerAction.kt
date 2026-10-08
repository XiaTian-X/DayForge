package com.dayforge.widget.timer

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.glance.action.Action
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import com.dayforge.data.api.decodeSyncReply
import com.dayforge.data.repository.WidgetTimerReadSnapshot
import com.dayforge.domain.model.TimerActionAuthority
import com.dayforge.domain.model.TimerStartGuard
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Display-time claim, not a credential. Both the Activity and the transaction verify it. */
@Serializable
@ConsistentCopyVisibility
internal data class WidgetTimerAction internal constructor(
    val habitId: Long,
    val authority: TimerActionAuthority,
    val startGuard: TimerStartGuard?
) {
    fun encode(): String = Json.encodeToString(this)

    fun intent(context: Context, action: String, confirmation: Boolean = false): Intent {
        require(action in setOf("start", "pause", "resume", "stop"))
        val payload = encode()
        val hash = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray()).joinToString("") { "%02x".format(it) }
        return Intent(context, if (confirmation) TimerConfirmationActivity::class.java else WidgetTimerActionActivity::class.java).apply {
            this.action = action
            data = Uri.Builder().scheme("dayforge-widget-timer").authority(hash).appendPath(action).build()
            putExtra(EXTRA, payload)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
        }
    }

    companion object {
        private const val EXTRA = "com.dayforge.widget.timer.action"
        fun from(snapshot: WidgetTimerReadSnapshot) = WidgetTimerAction(snapshot.habit.id, snapshot.authority, snapshot.startGuard)
        fun decode(payload: String): WidgetTimerAction {
            require(payload.length <= 65_536)
            val bytes = payload.toByteArray(Charsets.UTF_8)
            return decodeSyncReply(bytes, 65_536, serializer(), {}).also {
                require(it.habitId > 0)
                it.authority.validate(); it.startGuard?.validate()
                require((it.authority.sessionUuid == null) == (it.startGuard != null))
            }
        }
        fun read(intent: Intent): WidgetTimerAction? = intent.getStringExtra(EXTRA)?.let(::decode)
    }
}

/** Legacy buttons remain on v4; typed buttons directly launch the non-exported foreground entry. */
internal fun widgetTimerAction(context: Context, habitId: Long, targetMinutes: Int, action: String, proof: String?): Action =
    if (proof != null) {
        val claim = WidgetTimerAction.decode(proof)
        require(claim.habitId == habitId)
        actionStartActivity(claim.intent(context, action))
    } else actionRunCallback<TimerActionCallback>(actionParametersOf(
        ActionParameters.Key<Long>("habitId") to habitId,
        ActionParameters.Key<String>("action") to action,
        ActionParameters.Key<Int>("targetMinutes") to targetMinutes))
