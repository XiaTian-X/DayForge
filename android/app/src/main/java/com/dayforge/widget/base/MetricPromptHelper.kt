package com.dayforge.widget.base

import android.content.Context
import android.util.Log
import com.dayforge.data.local.HabitDatabaseProvider
import com.dayforge.di.WidgetEntryPoint
import com.dayforge.widget.checkin.MetricPromptActivity
import kotlinx.coroutines.flow.first

/**
 * Shared utility for checking and showing metric prompts after widget actions.
 * Eliminates duplicate checkAndShowMetricPrompt functions in action callbacks.
 */
object MetricPromptHelper {

    private const val TAG = "MetricPromptHelper"

    /**
     * Check if habit has linked metrics with promptOnComplete=true and show prompt if needed.
     *
     * Flow:
     * 1. Check "never ask again" preference for this habit
     * 2. Get linked metrics with promptOnComplete=true
     * 3. If any exist, launch MetricPromptActivity
     *
     * @param context Application context
     * @param habitId The habit ID to check
     */
    suspend fun checkAndShowMetricPrompt(context: Context, habitId: Long) {
        Log.d(TAG, "checkAndShowMetricPrompt: habitId=$habitId")

        val database = HabitDatabaseProvider.getInstance(context.applicationContext)

        val entry = WidgetEntryPoint.from(context)
        val publication = entry.displayPublisher().capturePublication() ?: return
        val session = publication.session ?: return
        val habit = database.habitDao().getVisibleHabitById(habitId) ?: return
        val preferencesManager = entry.preferencesManager()

        // Check if user has "never ask again" set for this habit
        val neverAsk = preferencesManager.getNeverAskAgain(session, habit.uuid).first()
        Log.d(TAG, "neverAsk=$neverAsk")
        if (neverAsk) {
            Log.d(TAG, "Skipping prompt: neverAsk is true")
            return
        }

        // Get linked metrics with promptOnComplete=true
        val links = database.habitMetricLinkDao().getLinksByHabit(habitId).first()
        Log.d(TAG, "total links=${links.size}, promptOnComplete values: ${links.map { it.promptOnComplete }}")
        val promptLinks = links.filter { it.promptOnComplete }
        Log.d(TAG, "promptLinks count=${promptLinks.size}")

        if (promptLinks.isEmpty()) {
            Log.d(TAG, "No prompt links found, skipping")
            return
        }

        Log.d(TAG, "Starting MetricPromptActivity for habit=${habit.name}")

        // Launch MetricPromptActivity
        val intent = MetricPromptActivity.createIntent(context, habitId, habit.name)
        publication {
            if (database.habitDao().getVisibleHabitById(habitId)?.uuid == habit.uuid)
                context.startActivity(intent)
        }
    }
}
