package com.dayforge.widget.checkin

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.lifecycleScope
import com.dayforge.R
import com.dayforge.data.repository.HabitReactivationRequest
import com.dayforge.data.repository.HabitRepository
import com.dayforge.data.repository.LegacyWidgetRestartClaim
import com.dayforge.data.repository.WidgetFactClaim
import com.dayforge.widget.base.WidgetActivityTheme
import com.dayforge.widget.timer.WidgetTimerAction
import dagger.hilt.android.AndroidEntryPoint
import java.security.MessageDigest
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Confirm a rendered identity, never an ID-only lookup at the later button click. */
@AndroidEntryPoint
class ReactivationActivity : ComponentActivity() {
    @Inject lateinit var habitRepository: HabitRepository
    private var request by mutableStateOf<HabitReactivationRequest?>(null)
    private var submitting by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val habitId = intent.getLongExtra(EXTRA_HABIT_ID, -1L)
        val isFailed = intent.getBooleanExtra(EXTRA_IS_FAILED, false)
        if (habitId <= 0) { finish(); return }

        lifecycleScope.launch {
            try {
                val originalIntent = intent
                request = withContext(Dispatchers.IO) {
                    val fact = originalIntent.getStringExtra(EXTRA_FACT)?.let(WidgetFactClaim::decode)
                    val timer = originalIntent.getStringExtra(EXTRA_TIMER)?.let(WidgetTimerAction::decode)
                    require(timer == null || timer.habitId == habitId) { "WIDGET_RESTART_STALE_DISPLAY" }
                    val legacy = originalIntent.getStringExtra(EXTRA_LEGACY)?.let(LegacyWidgetRestartClaim::decode)
                    habitRepository.prepareWidgetReactivation(habitId, fact, timer?.authority, legacy)
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                showErrorAndFinish()
            }
        }

        setContent {
            WidgetActivityTheme(this) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    val prepared = request
                    if (prepared == null) CircularProgressIndicator()
                    else Dialog(onDismissRequest = { finish() }) {
                        Card(modifier = Modifier.padding(16.dp),
                            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)) {
                            Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(text = if (isFailed) stringResource(R.string.dialog_reactivate_failed_title)
                                    else stringResource(R.string.dialog_reactivate_completed_title), style = MaterialTheme.typography.titleLarge)
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(text = when {
                                    prepared.preservesHistory -> stringResource(R.string.restart_message, prepared.habit.name)
                                    isFailed -> stringResource(R.string.dialog_reactivate_failed_message, prepared.habit.name)
                                    else -> stringResource(R.string.dialog_reactivate_completed_message, prepared.habit.name)
                                }, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                                Spacer(modifier = Modifier.height(24.dp))
                                Row(modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)) {
                                    TextButton(onClick = { finish() }) { Text(stringResource(R.string.action_cancel)) }
                                    Button(enabled = !submitting, onClick = click@ {
                                        if (submitting) return@click
                                        submitting = true
                                        lifecycleScope.launch {
                                            try {
                                                withContext(Dispatchers.IO) { habitRepository.reactivate(prepared, this@ReactivationActivity) }
                                                Toast.makeText(applicationContext, getString(if (prepared.preservesHistory)
                                                    R.string.restart_completed else R.string.toast_reactivate_success), Toast.LENGTH_SHORT).show()
                                                finish()
                                            } catch (error: Exception) {
                                                if (error is CancellationException) throw error
                                                showErrorAndFinish()
                                            } finally { submitting = false }
                                        }
                                    }) { Text(stringResource(R.string.dialog_reactivate_confirm)) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun showErrorAndFinish() {
        Toast.makeText(applicationContext, getString(R.string.restart_failed), Toast.LENGTH_SHORT).show()
        finish()
    }

    companion object {
        const val EXTRA_HABIT_ID = "habitId"
        const val EXTRA_HABIT_NAME = "habitName"
        const val EXTRA_IS_FAILED = "isFailed"
        private const val EXTRA_FACT = "com.dayforge.widget.restart.fact"
        private const val EXTRA_TIMER = "com.dayforge.widget.restart.timer"
        private const val EXTRA_LEGACY = "com.dayforge.widget.restart.legacy"

        fun createIntent(context: Context, habitId: Long, habitName: String, isFailed: Boolean,
            factProof: String? = null, timerProof: String? = null, legacyProof: String? = null): Intent {
            require(listOfNotNull(factProof, timerProof, legacyProof).size <= 1)
            val identity = listOf(habitId.toString(), isFailed.toString(), factProof, timerProof, legacyProof).joinToString("\u0000")
            val hash = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray()).joinToString("") { "%02x".format(it) }
            return Intent(context, ReactivationActivity::class.java).apply {
                data = Uri.Builder().scheme("dayforge-widget-restart").authority(hash).build()
                putExtra(EXTRA_HABIT_ID, habitId)
                putExtra(EXTRA_HABIT_NAME, habitName)
                putExtra(EXTRA_IS_FAILED, isFailed)
                factProof?.let { putExtra(EXTRA_FACT, it) }
                timerProof?.let { putExtra(EXTRA_TIMER, it) }
                legacyProof?.let { putExtra(EXTRA_LEGACY, it) }
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
            }
        }
    }
}
