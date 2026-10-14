package com.dayforge.ui.screens.settings

import com.dayforge.data.api.dto.ChallengeMetadata
import com.dayforge.data.repository.*
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Presentation/coordinator fixtures only; real account/SQL proof paths use NextRecoveryAuditStoreTest. */
internal fun recoveryUiId(n: Int) = "bb160000-0000-4000-8000-${n.toString().padStart(12, '0')}"
internal fun recoveryUiGraph(size: Int = 2, protected: Boolean = false) = runBlocking {
    val nodes = List(size) { n -> NextRecoveryNode(NextRecoveryKey(NEXT_OPERATION, recoveryUiId(n + 1)),
        NextRecoveryCategory.STRUCTURE,
        if (n == 0) NextRecoveryRequestState.PERMANENT_REJECTION else NextRecoveryRequestState.NEVER_SENT,
        "metric", recoveryUiId(9000), "a".repeat(64),
        if (protected && n == size - 1) setOf(NextRecoveryProtection.REAL_FACT) else emptySet()) }
    NextRecoveryDependencyGraph(nodes, nodes.zipWithNext { a, b -> NextRecoveryDependency(a.key, b.key,
        NextRecoveryDependencyReason.STRUCTURAL_PREDECESSOR, "b".repeat(64)) }).preview(nodes.first().key)
}
internal fun recoveryUiReview(graph: NextRecoveryDependencyPreview = recoveryUiGraph(), failed: Boolean = false) =
    RecoveryReview(graph, listOf(RecoveryObjectChange("metric", recoveryUiId(9000), "Current name", false,
        listOf(RecoveryFieldChange("name", "Current name", "Accepted name")))), 1, "123456789abc", failed,
        recoveryDependencyReasons(graph))

internal fun recoveryUiTicket(): NextRecoveryConfirmation = mockk<NextRecoveryConfirmation>().also { ticket ->
    val before = NextRecoveryProjectionSnapshot("metric", recoveryUiId(9000), 1,
        buildJsonObject { put("name", "Current name") }, buildJsonObject { put("name", "Current name") })
    val after = before.copy(body = buildJsonObject { put("name", "Accepted name") })
    coEvery { ticket.drafts() } returns listOf(NextRecoveryProjectionDraft(before, after, mockk()))
    coEvery { ticket.checkpoint() } returns NextRecoveryAuditCheckpoint(recoveryUiId(8000), recoveryUiId(8001),
        recoveryUiId(8002), recoveryUiId(8003), "a".repeat(64), "b".repeat(64), ChallengeMetadata(1, emptyList(), emptyList()))
}
internal fun recoveryUiProof(): NextRecoveryTerminalProof = mockk<NextRecoveryTerminalProof>().also {
    every { it.recoveryId } returns recoveryUiId(9100)
    every { it.manifest.requests } returns listOf(mockk(), mockk())
}
