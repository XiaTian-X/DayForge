package com.dayforge.di

import com.dayforge.data.repository.HabitRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Dependencies used by Glance callbacks, which cannot receive constructor injection. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
    fun habitRepository(): HabitRepository
    fun habitStatusCalculator(): com.dayforge.domain.service.HabitStatusCalculator

    companion object {
        fun from(context: android.content.Context): WidgetEntryPoint = dagger.hilt.android.EntryPointAccessors
            .fromApplication(context.applicationContext, WidgetEntryPoint::class.java)

        fun calculator(context: android.content.Context, database: com.dayforge.data.local.HabitDatabase): com.dayforge.domain.service.HabitStatusCalculator =
            try { from(context).habitStatusCalculator() }
            catch (error: IllegalStateException) {
                // Isolated legacy fixtures have no Hilt graph. Typed counters still fail closed
                // in the calculator; production must use its account-coordinated reader.
                com.dayforge.domain.service.HabitStatusCalculator(com.dayforge.domain.service.FailureChecker(
                    database.completionDao(), database.timeLogDao()), database.completionDao(), database.timeLogDao())
            }
    }
}
