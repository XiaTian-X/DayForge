package com.dayforge.widget.checkin

import android.content.Context
import android.content.Intent
import androidx.glance.action.Action
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import com.dayforge.data.repository.WidgetFactClaim

internal fun WidgetFactClaim.actionIntent(context: Context, action: String): Intent {
    require(action in setOf("toggle", "increment", "undo"))
    return Intent(context, WidgetFactActionActivity::class.java).also {
        it.action = action
        it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
        attach(it)
    }
}

/** v4 callbacks remain until coordinated activation; typed widgets use direct Activity actions. */
internal fun widgetFactAction(context: Context, habitId: Long, action: String, proof: String?): Action =
    if (proof != null) {
        val claim = WidgetFactClaim.decode(proof)
        require(claim.habitId == habitId)
        actionStartActivity(claim.actionIntent(context, if (action == "decrement") "increment" else action))
    } else actionRunCallback<CheckInActionCallback>(actionParametersOf(
        ActionParameters.Key<Long>("habitId") to habitId,
        ActionParameters.Key<String>("action") to action))
