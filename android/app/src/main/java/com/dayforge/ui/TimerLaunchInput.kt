package com.dayforge.ui

import com.dayforge.data.local.DatabaseEpochValue
import com.dayforge.data.local.DatabaseObjectSelection
import com.dayforge.domain.model.ActiveTimerState

/** Immutable UI intent captured before confirmation/permission. Never a business authority. */
internal class TimerLaunchInput(val habitId: Long, val targetMinutes: Int,
    val selection: DatabaseObjectSelection?, val visibleTimer: DatabaseEpochValue<ActiveTimerState?>?) {
    fun isCurrentFor(fixedGraph: Boolean): Boolean = fixedGraph ||
        selection?.let { it.kind == DatabaseObjectSelection.Kind.HABIT && it.id == habitId &&
            it.publication.isCurrentInMemory() && (visibleTimer == null || visibleTimer.publication.isCurrentInMemory()) } == true
}
