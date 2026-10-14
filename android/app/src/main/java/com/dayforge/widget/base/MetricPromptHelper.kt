package com.dayforge.widget.base

import android.content.Context
import com.dayforge.data.local.HabitDatabaseProvider
import com.dayforge.data.repository.LegacyWidgetRestartClaim
import com.dayforge.di.WidgetEntryPoint
import com.dayforge.widget.WidgetRefreshScheduler
import com.dayforge.widget.checkin.MetricPromptActivity
import kotlinx.coroutines.flow.first

/** A follow-up retains its originating operation's identity, including delayed Activity delivery. */
object MetricPromptHelper {
    suspend fun checkAndShowMetricPrompt(context: Context, habitId: Long,
        legacyIdentity: LegacyWidgetRestartClaim? = null) {
        if (legacyIdentity == null) { WidgetRefreshScheduler.request(context); return }
        require(legacyIdentity.habitId == habitId) { "LEGACY_WIDGET_PROMPT_CHANGED" }
        val entry = WidgetEntryPoint.from(context)
        val database = HabitDatabaseProvider.getInstance(context.applicationContext)
        entry.legacyFactWorkflow().publish(legacyIdentity) { habit ->
            if (entry.preferencesManager().getNeverAskAgain(legacyIdentity.session(), habit.uuid).first()) return@publish
            if (database.habitMetricLinkDao().getLinksByHabitSync(habit.id).none { it.promptOnComplete }) return@publish
            context.startActivity(MetricPromptActivity.createIntent(context, habit.id, habit.name, legacyIdentity = legacyIdentity))
        }
    }
}
