package com.dayforge.widget.checkin

import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.dayforge.R
import com.dayforge.data.repository.WidgetFactClaim
import com.dayforge.data.repository.WidgetFactReader
import com.dayforge.domain.service.CheckInService
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Direct foreground entry; no Glance callback-to-Activity trampoline or restored opaque ticket. */
@AndroidEntryPoint
class WidgetFactActionActivity : ComponentActivity() {
    @Inject lateinit var checkIns: CheckInService
    @Inject lateinit var reader: WidgetFactReader

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            try {
                val claim = requireNotNull(WidgetFactClaim.read(intent))
                val action = requireNotNull(intent.action)
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
