package com.dayforge.data.repository

import com.dayforge.data.api.dto.SyncV2Operation
import com.dayforge.data.api.dto.TimerCommandRequest
import com.dayforge.data.api.dto.TimerSessionResponse
import com.dayforge.data.local.entity.NextRequestOriginEntity

/** Private SELECT-only once/restart classification; never a disposal or acceptance ticket. */
internal data class NextRecoverySpecialProof(val origin: NextRequestOriginEntity,
    val state: NextRecoveryRequestState, val evidenceHash: String,
    val operation: SyncV2Operation? = null, val proposal: NextRestartProposal? = null)

/** Actual validated timer receipt; retained only within one read snapshot. */
internal data class NextRecoveryTimerAcceptedProof(val command: TimerCommandRequest,
    val session: TimerSessionResponse, val evidenceHash: String)
