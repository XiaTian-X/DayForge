package com.dayforge.widget.timer

import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.dayforge.R
import com.dayforge.data.repository.NextTimerWriter
import com.dayforge.domain.service.TimerManager
import com.dayforge.domain.service.TimerServiceController
import com.dayforge.widget.checkin.MetricPromptActivity
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Direct Glance Activity entry: no callback/service trampoline and no recaptured action authority. */
@AndroidEntryPoint
class WidgetTimerActionActivity : ComponentActivity() {
    @Inject lateinit var writer: NextTimerWriter
    @Inject lateinit var timers: TimerManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            try {
                val claim = requireNotNull(WidgetTimerAction.read(intent))
                val ticket = claim.authority
                when (intent.action) {
                    "start" -> {
                        val guard = requireNotNull(claim.startGuard)
                        writer.requireWidgetStart(claim.habitId, ticket, guard)
                        if (guard.incumbent != null) startActivity(claim.intent(this@WidgetTimerActionActivity, "start", confirmation = true))
                        else TimerServiceController.startTimer(this@WidgetTimerActionActivity, claim.habitId, 0,
                            authority = ticket, startGuard = guard)
                    }
                    "pause", "resume" -> {
                        require(ticket.sessionUuid != null)
                        writer.requireAction(claim.habitId, ticket)
                        if (intent.action == "pause") TimerServiceController.pauseTimer(this@WidgetTimerActionActivity, claim.habitId, 0, ticket)
                        else TimerServiceController.resumeTimer(this@WidgetTimerActionActivity, claim.habitId, 0, ticket)
                    }
                    "stop" -> {
                        require(ticket.sessionUuid != null)
                        if (timers.stopTimer(claim.habitId, 0, ticket) != null) {
                            startActivity(MetricPromptActivity.createIntent(this@WidgetTimerActionActivity, claim.habitId, "", ticket))
                        }
                    }
                    else -> error("TIMER_WIDGET_UNKNOWN_ACTION")
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                Log.w("WidgetTimerAction", "Widget action rejected; refresh before retry", error)
                Toast.makeText(this@WidgetTimerActionActivity, getString(R.string.data_read_failed), Toast.LENGTH_LONG).show()
            } finally { finish() }
        }
    }
}
