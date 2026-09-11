package com.dayforge.widget.base

import android.content.Context
import android.util.Log
import com.dayforge.data.local.DataStoreProvider
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.PreferencesManager
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

        val database = HabitDatabase.getInstance(context.applicationContext)

        // Use singleton DataStore provider for consistent access
        val dataStore = DataStoreProvider.get(context.applicationContext)
        val preferencesManager = PreferencesManager(dataStore)

        // Check if user has "never ask again" set for this habit
        val neverAsk = preferencesManager.getNeverAskAgain(habitId).first()
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

        // Get habit name for dialog
        val habit = database.habitDao().getHabitById(habitId)
        if (habit == null) {
            Log.d(TAG, "Habit not found for id=$habitId")
            return
        }

        Log.d(TAG, "Starting MetricPromptActivity for habit=${habit.name}")

        // Launch MetricPromptActivity
        val intent = MetricPromptActivity.createIntent(context, habitId, habit.name)
        context.startActivity(intent)
    }
}