package com.dayforge.widget.motivation

import android.content.Context
import com.dayforge.R
import kotlin.random.Random

/**
 * Streak-based motivation messages.
 * Selects appropriate message based on current streak count.
 */
object MotivationMessages {

    /**
     * Returns a motivation message based on the current streak.
     * @param context Android context for string resource access
     * @param currentStreak The current streak count
     * @return Appropriate motivation message
     */
    fun getMessage(context: Context, currentStreak: Int): String {
        return when (currentStreak) {
            0 -> {
                val options = listOf(
                    context.getString(R.string.motivation_start_1),
                    context.getString(R.string.motivation_start_2)
                )
                options[Random.nextInt(options.size)]
            }
            in 1..3 -> {
                val options = listOf(
                    context.getString(R.string.motivation_3days_1),
                    context.getString(R.string.motivation_3days_2)
                )
                options[Random.nextInt(options.size)]
            }
            in 4..6 -> {
                val options = listOf(
                    context.getString(R.string.motivation_5days_1),
                    context.getString(R.string.motivation_5days_2)
                )
                options[Random.nextInt(options.size)]
            }
            in 7..13 -> {
                val options = listOf(
                    context.getString(R.string.motivation_7days_1),
                    context.getString(R.string.motivation_7days_2)
                )
                options[Random.nextInt(options.size)]
            }
            in 14..29 -> {
                val options = listOf(
                    context.getString(R.string.motivation_14days_1),
                    context.getString(R.string.motivation_14days_2)
                )
                options[Random.nextInt(options.size)]
            }
            else -> {
                val options = listOf(
                    context.getString(R.string.motivation_30days_1),
                    context.getString(R.string.motivation_30days_2)
                )
                options[Random.nextInt(options.size)]
            }
        }
    }
}