package com.dayforge.widget.progress

import com.dayforge.widget.base.DeviceWidgetTheme
import android.content.Context
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.glance.*
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.*
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.layout.*
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import com.dayforge.R
import com.dayforge.data.local.HabitDatabaseProvider
import com.dayforge.di.WidgetEntryPoint
import com.dayforge.widget.base.WidgetEmptyStates
import com.dayforge.widget.timer.WidgetTimerRefreshCallback

class ProgressWidget : GlanceAppWidget() {

    companion object {
        private const val TAG = "ProgressWidget"

        val COMPLETED_COUNT_KEY = intPreferencesKey("completedCount")
        val TOTAL_COUNT_KEY = intPreferencesKey("totalCount")
        val PROGRESS_KEY = floatPreferencesKey("progress")
        val DATA_LOADED_KEY = booleanPreferencesKey("dataLoaded")
        val READ_FAILED_KEY = booleanPreferencesKey("readFailed")

        suspend fun refreshWidgetData(context: Context, glanceId: GlanceId? = null) {
            val appContext = context.applicationContext
            val ids = glanceId?.let { listOf(it) } ?: GlanceAppWidgetManager(appContext)
                .getGlanceIds(ProgressWidget::class.java)
            for (id in ids) {
                WidgetEntryPoint.from(appContext).displayPublisher().renderPrepared(
                    onReadFailure = { error ->
                        Log.w(TAG, "Progress widget source unavailable", error)
                        updateAppWidgetState(appContext, id) { prefs ->
                            prefs[DATA_LOADED_KEY] = false
                            prefs[READ_FAILED_KEY] = true
                        }
                    }
                ) { prepareWidgetData(appContext, id) }
            }
        }

        private suspend fun prepareWidgetData(context: Context, id: GlanceId): suspend () -> Unit {
            val database = HabitDatabaseProvider.getInstance(context.applicationContext)
            val habitDao = database.habitDao()
            val habitStatusCalculator = WidgetEntryPoint.calculator(context, database)

            val habits = habitDao.getVisibleHabitsOnce().filter { it.completionPolicy != "one_and_done" }

            // Calculate status for each habit
            val stats = habits.map { habit ->
                habitStatusCalculator.calculate(habit)
            }

            // Filter eligible habits (check-in day + not failed + not goal-completed)
            val eligibleStats = stats.filter { it.shouldCountToday }

            val completedCount = eligibleStats.count { it.completedToday }
            val totalCount = eligibleStats.size
            val progress = if (totalCount == 0) 0f else completedCount.toFloat() / totalCount

            Log.d(TAG, "Progress refresh: $completedCount / $totalCount (eligible from ${habits.size} total)")

            // Display I/O runs outside Room, under the original account publication lock.
            return suspend {
                updateAppWidgetState(context, id) { prefs ->
                    prefs[COMPLETED_COUNT_KEY] = completedCount
                    prefs[TOTAL_COUNT_KEY] = totalCount
                    prefs[PROGRESS_KEY] = progress
                    prefs[DATA_LOADED_KEY] = true
                    prefs[READ_FAILED_KEY] = false
                }
            }
        }
    }

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            DeviceWidgetTheme(context) {
                val prefs = currentState<Preferences>()
                if (prefs[READ_FAILED_KEY] == true) {
                    WidgetEmptyStates.EmptyConfigState(context.getString(R.string.data_read_failed) + "\n" +
                        context.getString(R.string.action_retry), GlanceModifier.clickable(
                            actionRunCallback<WidgetTimerRefreshCallback>(actionParametersOf(
                                ActionParameters.Key<String>("widget") to "progress"))))
                } else if (prefs[DATA_LOADED_KEY] != true) {
                    WidgetEmptyStates.EmptyConfigState(context.getString(R.string.common_loading))
                } else {
                    ProgressWidgetContent(
                        completedCount = prefs[COMPLETED_COUNT_KEY] ?: 0,
                        totalCount = prefs[TOTAL_COUNT_KEY] ?: 0,
                        progress = prefs[PROGRESS_KEY] ?: 0f
                    )
                }
            }
        }
    }

    @Composable
    private fun ProgressWidgetContent(
        completedCount: Int,
        totalCount: Int,
        progress: Float
    ) {
        val context = androidx.glance.LocalContext.current
        Box(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(GlanceTheme.colors.surface)
                .padding(16.dp)
        ) {
            Column(
                modifier = GlanceModifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                LinearProgressIndicator(
                    progress = progress,
                    modifier = GlanceModifier
                        .fillMaxWidth()
                        .height(8.dp),
                    color = GlanceTheme.colors.tertiary,
                    backgroundColor = GlanceTheme.colors.surfaceVariant
                )

                Spacer(modifier = GlanceModifier.height(16.dp))

                Text(
                    text = "$completedCount / $totalCount",
                    style = TextStyle(
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = GlanceTheme.colors.onSurface,
                        textAlign = TextAlign.Center
                    )
                )

                Spacer(modifier = GlanceModifier.height(4.dp))

                Text(
                    text = context.getString(R.string.widget_today_progress),
                    style = TextStyle(
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = GlanceTheme.colors.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                )
            }
        }
    }
}
