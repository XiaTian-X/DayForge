package com.dayforge.di

import android.content.Context
import com.dayforge.reminder.HabitReminderController
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface ReminderEntryPoint {
    fun habitReminderController(): HabitReminderController
    companion object {
        fun from(context: Context): HabitReminderController = dagger.hilt.android.EntryPointAccessors
            .fromApplication(context.applicationContext, ReminderEntryPoint::class.java).habitReminderController()
    }
}
