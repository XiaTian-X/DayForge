package com.dayforge.ui

import android.content.Context
import com.dayforge.R
import com.dayforge.domain.service.CommittedActionRefreshFailed

/** A failed follow-up must never invite the user to repeat an already committed write. */
internal fun actionFailureMessage(context: Context, error: Exception): String =
    if (error is CommittedActionRefreshFailed) context.getString(R.string.record_saved_refresh_failed)
    else if (error is com.dayforge.data.local.DatabaseRuntimeHost.SessionPreparationUnavailable)
        context.getString(R.string.session_preparation_failed)
    else if (error.message in setOf("DATABASE_EPOCH_INPUT_REQUIRED", "DATABASE_EPOCH_INPUT_EXPIRED", "DATABASE_EPOCH_SELECTION_CHANGED"))
        context.getString(R.string.database_view_expired)
    else context.getString(R.string.metric_error_record_failed, error.message.orEmpty())
