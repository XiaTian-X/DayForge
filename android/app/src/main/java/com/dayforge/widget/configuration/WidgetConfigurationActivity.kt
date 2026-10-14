package com.dayforge.widget.configuration

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.dayforge.R
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.di.WidgetEntryPoint
import com.dayforge.domain.service.WidgetConfigurationKind
import com.dayforge.domain.service.CardColorResolver
import com.dayforge.ui.theme.LocalDeviceCardStyle
import com.dayforge.widget.base.WidgetActivityTheme
import kotlinx.coroutines.launch

/** Shared lifecycle, not a new layout. Each existing entrypoint keeps its own type/title/target. */
abstract class WidgetConfigurationActivity : ComponentActivity() {
    internal abstract val kind: WidgetConfigurationKind

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }
        val model = ViewModelProvider(this, WidgetConfigurationViewModel.Factory(
            WidgetEntryPoint.from(this).configurationWorkflow(), kind, widgetId))[WidgetConfigurationViewModel::class.java]
        lifecycleScope.launch {
            var wasSaving = false
            model.state.collect { value ->
                if (value.saving && !wasSaving) Toast.makeText(this@WidgetConfigurationActivity,
                    getString(R.string.widget_configuring, value.selectedName.orEmpty()), Toast.LENGTH_SHORT).show()
                wasSaving = value.saving
                if (value.expired) finish()
                else if (value.finished) {
                    setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
                    finish()
                }
            }
        }
        setContent {
            WidgetActivityTheme(this, translucent = false) {
                val state by model.state.collectAsState()
                ConfigurationScreen(kind, state, model::choose, model::retry, ::finish)
            }
        }
    }
}

@Composable
private fun ConfigurationScreen(kind: WidgetConfigurationKind, state: WidgetConfigurationState,
    choose: (HabitEntity) -> Unit, retry: () -> Unit, close: () -> Unit) {
    val (title, empty, hint) = when (kind) {
        WidgetConfigurationKind.CHECK_IN -> Triple(R.string.widget_checkin_select_title, R.string.widget_empty_checkin, R.string.widget_empty_checkin_hint)
        WidgetConfigurationKind.COUNTING -> Triple(R.string.widget_counting_select_title, R.string.widget_empty_counting, R.string.widget_empty_counting_hint)
        WidgetConfigurationKind.TIMER -> Triple(R.string.widget_timer_select_title, R.string.widget_empty_timer, R.string.widget_empty_timer_hint)
    }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text(stringResource(title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 16.dp))
        when {
            state.loading -> Text(stringResource(R.string.common_loading))
            state.error != null -> {
                Text(stringResource(R.string.widget_configuration_error))
                TextButton(onClick = retry) { Text(stringResource(R.string.action_retry)) }
                TextButton(onClick = close) { Text(stringResource(R.string.action_cancel)) }
            }
            state.habits.isEmpty() && !state.expired -> {
                AlertDialog(onDismissRequest = close, title = { Text(stringResource(R.string.widget_empty_habits)) },
                    text = { Text(stringResource(hint)) }, confirmButton = {
                        TextButton(onClick = close) { Text(stringResource(R.string.common_ok)) }
                    })
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(empty)); Spacer(Modifier.height(8.dp))
                        Text(stringResource(hint), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            else -> LazyColumn {
                items(state.habits, key = { it.id }) { habit ->
                    val colors = CardColorResolver.resolveCardColors(LocalDeviceCardStyle.current, habit.colorHex,
                        MaterialTheme.colorScheme.primaryContainer.toArgb(), MaterialTheme.colorScheme.onPrimaryContainer.toArgb(),
                        MaterialTheme.colorScheme.onPrimary.toArgb())
                    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("widget-configuration-card-${habit.id}")
                        .clickable(enabled = !state.saving && !state.finished && !state.expired) { choose(habit) },
                        colors = CardDefaults.cardColors(containerColor = colors.backgroundColor)) {
                        if (kind == WidgetConfigurationKind.CHECK_IN) {
                            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(habit.name, style = MaterialTheme.typography.titleMedium, color = colors.textColor)
                            }
                        } else Column(Modifier.padding(16.dp)) {
                            Text(habit.name, style = MaterialTheme.typography.titleMedium, color = colors.textColor)
                            Text(stringResource(if (kind == WidgetConfigurationKind.TIMER) R.string.widget_target_minutes
                                else R.string.widget_target_count, habit.targetValue),
                                style = MaterialTheme.typography.bodySmall, color = colors.secondaryTextColor)
                        }
                    }
                }
            }
        }
    }
}
