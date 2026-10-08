package com.dayforge.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.dayforge.reminder.ReminderDetailRequest
import kotlinx.coroutines.CancellationException

/** Wait for graph installation, handle both cold/warm intents once, never route over Login. */
@Composable
internal fun ReminderNavigationEffect(
    request: ReminderDetailRequest?, graphReady: Boolean, loginVisible: Boolean,
    open: suspend (ReminderDetailRequest, (Long) -> Unit) -> Boolean,
    consume: (ReminderDetailRequest) -> Unit, navigate: (Long) -> Unit
) {
    LaunchedEffect(request, graphReady) {
        if (request == null || !graphReady) return@LaunchedEffect
        try {
            if (!loginVisible) open(request, navigate)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            android.util.Log.e("HabitReminder", "Cannot resolve reminder navigation", error)
        } finally { consume(request) }
    }
}
