package com.dayforge.widget.timer

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.GlanceAppWidgetManager
import com.dayforge.R
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.model.HabitType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Configuration activity for 2x2 timer widget.
 * Allows user to select which TIMER habit to track in the widget.
 * Filters to show only TIMER habits - this is a dedicated TIMER widget.
 *
 * Note: This is a minimal implementation. Full implementation will be in Plan 23-02.
 */
class TimerWidgetConfigActivity : ComponentActivity() {

    companion object {
        private const val TAG = "TimerWidgetConfig"
    }

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Set RESULT_CANCELED initially - if user backs out, widget won't be added
        setResult(RESULT_CANCELED)

        // Get appWidgetId from intent
        appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        Log.d(TAG, "onCreate: appWidgetId=$appWidgetId")

        // If invalid, finish immediately
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            Log.e(TAG, "Invalid appWidgetId, finishing")
            finish()
            return
        }

        setContent {
            MaterialTheme {
                HabitSelectionScreen(
                    onHabitSelected = { habit ->
                        onHabitChosen(habit, appWidgetId)
                    }
                )
            }
        }
    }

    private fun onHabitChosen(habit: HabitEntity, appWidgetId: Int) {
        Toast.makeText(this, getString(R.string.widget_configuring, habit.name), Toast.LENGTH_SHORT).show()

        // Save to SharedPreferences
        val prefs = applicationContext.getSharedPreferences(TimerWidget.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putLong(TimerWidget.PREF_HABIT_ID_PREFIX + appWidgetId, habit.id)
            .commit()
        Log.d(TAG, "Saved habitId=${habit.id} for widget $appWidgetId to SharedPreferences")

        // Load habit data into Glance state and trigger update
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val manager = GlanceAppWidgetManager(applicationContext)
                val glanceId = manager.getGlanceIdBy(appWidgetId)

                // Load data from Room and write into Glance DataStore
                TimerWidget.refreshWidgetData(applicationContext, glanceId, habit.id)
                Log.d(TAG, "Refreshed Glance widget data for widget $appWidgetId")

                // Trigger recomposition
                TimerWidget().update(applicationContext, glanceId)
                Log.d(TAG, "Triggered Glance update for widget $appWidgetId")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update Glance state", e)
            }

            withContext(Dispatchers.Main) {
                val resultValue = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                setResult(RESULT_OK, resultValue)
                finish()
            }
        }
    }
}

@Composable
private fun HabitSelectionScreen(onHabitSelected: (HabitEntity) -> Unit) {
    var habits by remember { mutableStateOf<List<HabitEntity>>(emptyList()) }
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        val database = HabitDatabase.getInstance(context)
        val allHabits = database.habitDao().getAllHabits().first()

        val appWidgetManager = AppWidgetManager.getInstance(context)
        val activeWidgetIds = appWidgetManager.getAppWidgetIds(
            android.content.ComponentName(context, TimerWidgetReceiver::class.java)
        )

        val prefs = context.getSharedPreferences(TimerWidget.PREFS_NAME, Context.MODE_PRIVATE)
        val configuredHabitIds = activeWidgetIds.map {
            prefs.getLong(TimerWidget.PREF_HABIT_ID_PREFIX + it, -1L)
        }.filter { it != -1L }

        // Filter to TIMER habits only - this is a TIMER widget
        habits = allHabits.filter {
            it.habitType == HabitType.TIMER && !configuredHabitIds.contains(it.id)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = stringResource(R.string.widget_timer_select_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        if (habits.isEmpty()) {
            val activity = LocalContext.current as? ComponentActivity
            AlertDialog(
                onDismissRequest = { activity?.finish() },
                title = { Text(stringResource(R.string.widget_empty_habits)) },
                text = { Text(stringResource(R.string.widget_empty_timer_hint)) },
                confirmButton = {
                    TextButton(onClick = { activity?.finish() }) {
                        Text(stringResource(R.string.common_ok))
                    }
                }
            )
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.widget_empty_timer))
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.widget_empty_timer_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            androidx.compose.foundation.lazy.LazyColumn {
                items(habits.size) { index ->
                    HabitSelectionItem(habit = habits[index], onClick = { onHabitSelected(habits[index]) })
                }
            }
        }
    }
}

@Composable
private fun HabitSelectionItem(habit: HabitEntity, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = try {
                androidx.compose.ui.graphics.Color(android.graphics.Color.parseColor(habit.colorHex))
            } catch (e: Exception) {
                MaterialTheme.colorScheme.primary
            }
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = habit.name,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimary
            )
            Text(
                text = stringResource(R.string.widget_target_minutes, habit.targetValue),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimary
            )
        }
    }
}