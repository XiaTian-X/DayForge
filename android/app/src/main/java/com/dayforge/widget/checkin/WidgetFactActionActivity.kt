package com.dayforge.widget.checkin

import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.dayforge.R
import com.dayforge.data.repository.WidgetFactClaim
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Direct foreground entry; no Glance callback-to-Activity trampoline or restored opaque ticket. */
@AndroidEntryPoint
class WidgetFactActionActivity : ComponentActivity() {
    @Inject internal lateinit var databaseHost: com.dayforge.data.local.DatabaseRuntimeHost
    private val checkIns get() = databaseHost.boundGraph().checkInService
    private val reader get() = databaseHost.boundGraph().widgetFactReader

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Enter shared admission before the first dispatch/suspension.
        lifecycleScope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            try {
                val claim = requireNotNull(WidgetFactClaim.read(intent))
                val action = requireNotNull(intent.action)
                if (claim.countPolicy != null || action in setOf("increment", "undo")) {
                    // A retained pre-upgrade Activity PendingIntent has no stable tap ID.
                    // COUNT-only actions also reject if its mutable type flag was omitted;
                    // the ordinary CHECK workflow and internal participant API are unchanged.
                    Toast.makeText(this@WidgetFactActionActivity,
                        getString(R.string.widget_count_action_refresh_required), Toast.LENGTH_LONG).show()
                    return@launch
                }
                databaseHost.withGraph {
                val result = checkIns.widgetAction(this@WidgetFactActionActivity, claim, action)
                val added = action == "increment" || (action == "toggle" && claim.completionUuid == null)
                if (added) {
                    val committed = reader.afterWrite(claim)
                    if (result.goalReached && committed.habit.targetCycles?.let { committed.targetProgress >= it } == true)
                        startActivity(GoalCompletionActivity.createIntent(this@WidgetFactActionActivity,
                        committed.habit.id, committed.habit.name, committed.targetProgress, requireNotNull(committed.habit.targetCycles),
                        committed.claim))
                    else startActivity(MetricPromptActivity.createIntent(this@WidgetFactActionActivity,
                        committed.habit.id, committed.habit.name, factClaim = committed.claim))
                }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                Log.w("WidgetFactAction", "Widget action rejected; refresh before retry", error)
                Toast.makeText(this@WidgetFactActionActivity, getString(R.string.data_read_failed), Toast.LENGTH_LONG).show()
            } finally {
                com.dayforge.widget.WidgetRefreshScheduler.request(this@WidgetFactActionActivity)
                finish()
            }
        }
    }
}
