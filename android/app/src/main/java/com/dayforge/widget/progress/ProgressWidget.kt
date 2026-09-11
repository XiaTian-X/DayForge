package com.dayforge.widget.progress

import android.content.Context
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.glance.*
import androidx.glance.appwidget.*
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.layout.*
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import com.dayforge.R
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.model.HabitType
import com.dayforge.data.repository.HabitRepository
import com.dayforge.domain.service.FailureChecker
import com.dayforge.domain.service.HabitStatusCalculator
import com.dayforge.util.DateTimeUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

class ProgressWidget : GlanceAppWidget() {

    companion object {
        private const val TAG = "ProgressWidget"

        val COMPLETED_COUNT_KEY = intPreferencesKey("completedCount")
        val TOTAL_COUNT_KEY = intPreferencesKey("totalCount")
        val PROGRESS_KEY = floatPreferencesKey("progress")

        suspend fun refreshWidgetData(context: Context) {
            val database = HabitDatabase.getInstance(context.applicationContext)
            val habitDao = database.habitDao()
            val completionDao = database.completionDao()
            val timeLogDao = database.timeLogDao()

            // Create services manually (widgets don't use Hilt)
            val failureChecker = FailureChecker(completionDao, timeLogDao)
            val habitStatusCalculator = HabitStatusCalculator(failureChecker, completionDao, timeLogDao)

            val habits = habitDao.getAllHabits().first()

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

            val manager = GlanceAppWidgetManager(context)
            val glanceIds = manager.getGlanceIds(ProgressWidget::class.java)
            for (id in glanceIds) {
                updateAppWidgetState(context, id) { prefs ->
                    prefs[COMPLETED_COUNT_KEY] = completedCount
                    prefs[TOTAL_COUNT_KEY] = totalCount
                    prefs[PROGRESS_KEY] = progress
                }
            }
        }
    }

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        try {
            refreshWidgetData(context)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error in provideGlance", e)
        }

        provideContent {
            val prefs = currentState<Preferences>()
            val completedCount = prefs[COMPLETED_COUNT_KEY] ?: 0
            val totalCount = prefs[TOTAL_COUNT_KEY] ?: 0
            val progress = prefs[PROGRESS_KEY] ?: 0f

            ProgressWidgetContent(
                completedCount = completedCount,
                totalCount = totalCount,
                progress = progress
            )
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