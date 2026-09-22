package com.dayforge.widget.checkin

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
import com.dayforge.R
import androidx.core.content.edit
import androidx.core.graphics.toColorInt
import androidx.glance.appwidget.GlanceAppWidgetManager
import com.dayforge.data.local.HabitDatabaseProvider
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.model.HabitType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Configuration activity for 1x1 check-in widget.
 * Allows user to select which habit to track in the widget.
 */
class CheckInWidgetConfigActivity : ComponentActivity() {

    companion object {
        private const val TAG = "CheckInWidgetConfig"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Set RESULT_CANCELED initially - if user backs out, widget won't be added
        setResult(RESULT_CANCELED)

        val appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        Log.d(TAG, "onCreate: appWidgetId=$appWidgetId")

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

        // Save to SharedPreferences (for backward compat / other readers)
        val prefs = applicationContext.getSharedPreferences(CheckInWidget.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit(commit = true) {
            putLong(CheckInWidget.PREF_HABIT_ID_PREFIX + appWidgetId, habit.id)
        }
        Log.d(TAG, "Saved habitId=${habit.id} for widget $appWidgetId to SharedPreferences")

        // Load habit data into Glance state and trigger update
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val manager = GlanceAppWidgetManager(applicationContext)
                val glanceId = manager.getGlanceIdBy(appWidgetId)

                // Load data from Room and write into Glance DataStore
                CheckInWidget.refreshWidgetData(applicationContext, glanceId, habit.id)
                Log.d(TAG, "Refreshed Glance widget data for widget $appWidgetId")

                // Trigger recomposition – provideContent reads from Glance state
                CheckInWidget().update(applicationContext, glanceId)
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
            android.content.ComponentName(context, CheckInWidgetReceiver::class.java)
        )

        val prefs = context.getSharedPreferences(CheckInWidget.PREFS_NAME, Context.MODE_PRIVATE)
        val configuredHabitIds = activeWidgetIds.map {
            prefs.getLong(CheckInWidget.PREF_HABIT_ID_PREFIX + it, -1L)
        }.filter { it != -1L }

        // Filter to CHECK_IN habits only - COUNTING requires 2x2 widget
        habits = allHabits.filter {
            it.habitType == HabitType.CHECK_IN && !configuredHabitIds.contains(it.id)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = stringResource(R.string.widget_checkin_select_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        if (habits.isEmpty()) {
            val activity = LocalContext.current as? ComponentActivity
            AlertDialog(
                onDismissRequest = { activity?.finish() },
                title = { Text(stringResource(R.string.widget_empty_habits)) },
                text = { Text(stringResource(R.string.widget_empty_checkin_hint)) },
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
                    Text(stringResource(R.string.widget_empty_checkin))
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.widget_empty_checkin_hint),
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
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = habit.name,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimary
            )
        }
    }
}
