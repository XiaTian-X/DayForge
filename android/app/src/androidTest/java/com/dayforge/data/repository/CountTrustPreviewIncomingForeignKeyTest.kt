package com.dayforge.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual recovery repository/transport/business coordinator composition; no later archive
 * validation is treated as authority for the earlier preview cleanup. */
@RunWith(AndroidJUnit4::class)
class CountTrustPreviewIncomingForeignKeyTest : CountTrustCleanupIncomingForeignKeyFixture() {
    @Test fun actualPreviewRejectsIncomingCascadeBeforeCleanupOrRecoveryPreviewRequest() = runBlocking<Unit> {
        initialize(); materialReply = { materialResponse(it, emptyList()) }
        val evidence = installAndProveCascade(); val repository = actualRecoveryRepository()
        val callsBefore = peer.requests.count { it.path == "/api/v2/sync/rounds/recovery-preview" }
        try { requireEntryRejectsWithoutEffects("actual_preview", evidence) { repository.preview() } }
        finally { android.util.Log.i("CountCleanupFk", "phase=preview_transport recovery_preview_requests=" +
            (peer.requests.count { it.path == "/api/v2/sync/rounds/recovery-preview" } - callsBefore)) }
        assertEquals(callsBefore, peer.requests.count { it.path == "/api/v2/sync/rounds/recovery-preview" })
        assertEquals(0, runtimeInitializations.get()); assertNull(repository.pending())
    }
}
