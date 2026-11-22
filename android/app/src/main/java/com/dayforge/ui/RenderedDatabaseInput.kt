package com.dayforge.ui

import android.content.Context
import android.widget.Toast
import com.dayforge.R
import com.dayforge.data.local.DatabaseObjectSelection
import com.dayforge.data.model.HabitWithStats
import com.dayforge.domain.model.MetricWithLatestValue
import com.dayforge.ui.screens.nested.ChildHabitWithStats
import com.dayforge.ui.screens.nested.ParentHabitWithChildren

/** Factory inputs are the exact rendered row and its paired origin; no current Host lookup. */
internal fun HabitWithStats.renderedSelection(): DatabaseObjectSelection? =
    sourceEpoch?.let { DatabaseObjectSelection.habit(habit, it) }
internal fun ChildHabitWithStats.renderedSelection(): DatabaseObjectSelection? =
    sourceEpoch?.let { DatabaseObjectSelection.habit(habit, it) }
internal fun ParentHabitWithChildren.renderedSelection(): DatabaseObjectSelection? =
    sourceEpoch?.let { DatabaseObjectSelection.habit(habit, it) }
internal fun MetricWithLatestValue.renderedSelection(): DatabaseObjectSelection? =
    sourceEpoch?.let { DatabaseObjectSelection.metric(metric, it) }

/** Navigation cannot run under publication's assignment lock. The destination must carry
 * and validate this same opaque selection again before loading/adopting an object. This
 * early check only suppresses a known obsolete click; it does not close the transfer race.
 * The numeric callback is exclusively the explicit fixed-graph manual constructor path.
 */
internal fun navigateRenderedDatabaseInput(context: Context, fixedGraph: Boolean, id: Long,
    selection: DatabaseObjectSelection?, legacy: (Long) -> Unit,
    selected: ((DatabaseObjectSelection) -> Unit)?) {
    if (fixedGraph) { legacy(id); return }
    if (selection == null || selection.id != id || selected == null || !selection.publication.isCurrentInMemory()) {
        Toast.makeText(context, R.string.database_view_expired, Toast.LENGTH_LONG).show()
        return
    }
    selected(selection)
}
