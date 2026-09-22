package com.dayforge.widget.counting

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.core.graphics.toColorInt
import androidx.glance.appwidget.GlanceAppWidgetManager
import com.dayforge.R
import com.dayforge.data.local.HabitDatabaseProvider
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.model.HabitType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Configuration activity for 2x2 counting widget.
 * Allows user to select which COUNTING habit to track in the widget.
 * Filters to show only COUNTING habits - this is a dedicated COUNTING widget.
 */
class CountingWidgetConfigActivity : ComponentActivity() {

    companion object {
        private const val TAG = "CountingWidgetConfig"
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
        val prefs = applicationContext.getSharedPreferences(CountingWidget.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit(commit = true) {
            putLong(CountingWidget.PREF_HABIT_ID_PREFIX + appWidgetId, habit.id)
        }
        Log.d(TAG, "Saved habitId=${habit.id} for widget $appWidgetId to SharedPreferences")

        // Load habit data into Glance state and trigger update
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val manager = GlanceAppWidgetManager(applicationContext)
                val glanceId = manager.getGlanceIdBy(appWidgetId)

                // Load data from Room and write into Glance DataStore
                CountingWidget.refreshWidgetData(applicationContext, glanceId, habit.id)
                Log.d(TAG, "Refreshed Glance widget data for widget $appWidgetId")

                // Trigger recomposition
                CountingWidget().update(applicationContext, glanceId)
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
        val database = HabitDatabaseProvider.getInstance(context)
        val allHabits = database.habitDao().getAllHabits().first()

        val appWidgetManager = AppWidgetManager.getInstance(context)
        val activeWidgetIds = appWidgetManager.getAppWidgetIds(
            android.content.ComponentName(context, CountingWidgetReceiver::class.java)
        )

        val prefs = context.getSharedPreferences(CountingWidget.PREFS_NAME, Context.MODE_PRIVATE)
        val configuredHabitIds = activeWidgetIds.map {
            prefs.getLong(CountingWidget.PREF_HABIT_ID_PREFIX + it, -1L)
        }.filter { it != -1L }

        // Filter to COUNTING habits only - this is a COUNTING widget
        habits = allHabits.filter {
            it.habitType == HabitType.COUNTING && !configuredHabitIds.contains(it.id)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = stringResource(R.string.widget_counting_select_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        if (habits.isEmpty()) {
            val activity = LocalContext.current as? ComponentActivity
            AlertDialog(
                onDismissRequest = { activity?.finish() },
                title = { Text(stringResource(R.string.widget_empty_habits)) },
                text = { Text(stringResource(R.string.widget_empty_counting_hint)) },
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
                    Text(stringResource(R.string.widget_empty_counting))
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.widget_empty_counting_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn {
                items(habits) { habit ->
                    HabitSelectionItem(habit = habit, onClick = { onHabitSelected(habit) })
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
                Color(habit.colorHex.toColorInt())
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
                text = stringResource(R.string.widget_target_count, habit.targetValue),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimary
            )
        }
    }
}
