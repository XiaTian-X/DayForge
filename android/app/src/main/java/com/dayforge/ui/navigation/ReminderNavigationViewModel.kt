package com.dayforge.ui.navigation

import androidx.lifecycle.ViewModel
import com.dayforge.reminder.HabitReminderController
import com.dayforge.reminder.ReminderDetailRequest
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class ReminderNavigationViewModel @Inject constructor(private val reminders: HabitReminderController) : ViewModel() {
    suspend fun open(request: ReminderDetailRequest, navigate: (Long) -> Unit): Boolean =
        reminders.openDetail(request, navigate)
}
