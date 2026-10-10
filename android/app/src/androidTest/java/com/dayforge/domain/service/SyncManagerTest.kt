package com.dayforge.domain.service

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith
import com.dayforge.data.model.SyncProgress
import com.dayforge.data.repository.IncrementalSyncRepository
import com.dayforge.data.repository.BusinessSyncRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@RunWith(AndroidJUnit4::class)
class SyncManagerTest {
    @Test fun received_v5_http_and_invalid_reply_are_not_network_loss_even_inside_io_wrapper() = runTest {
        for (error in listOf(com.dayforge.data.api.NextSyncHttpFailure(403, "DENIED"),
            com.dayforge.data.api.NextSyncReplyInvalid())) {
            val wrapped = IOException("retained diagnostic", error)
            coEvery { business.sync(any(), any()) } throws wrapped
            assertTrue(manager.sync().isFailure)
            assertEquals(SyncProgress.Error("retained diagnostic", false), manager.syncProgress.first())
            coEvery { business.sync(any(), any()) } throws error
            assertTrue(manager.syncAndThen {}.isFailure)
            assertEquals(SyncProgress.Error(error.message!!, false), manager.syncProgress.first())
        }
    }

    @Test fun nested_real_transport_loss_remains_network_failure_without_a_protocol_receipt() = runTest {
        coEvery { business.sync(any(), any()) } throws IllegalStateException("transport detail", IOException("disconnected"))
        assertTrue(manager.sync().isFailure)
        assertEquals(SyncProgress.Error("transport detail", true), manager.syncProgress.first())
    }

    private lateinit var repository: IncrementalSyncRepository
    private lateinit var manager: SyncManager
    private lateinit var business: BusinessSyncRepository

    @Before
    fun setUp() {
        repository = mockk(relaxed = true)
        business = mockk(relaxed = true)
        manager = SyncManager(repository, business)
    }

    @Test
    fun sync_delegates_exclusively_to_shared_business_repository() = runTest {
        coEvery { business.sync(any(), any()) } answers {
            firstArg<(SyncProgress) -> Unit>()(SyncProgress.UploadingChanges(1, 1))
        }

        val result = manager.sync()

        assertTrue(result.isSuccess)
        assertEquals(SyncProgress.Success, manager.syncProgress.first())
        coVerify(exactly = 1) { business.sync(any(), any()) }
        coVerify(exactly = 0) { repository.sync(any()) }
    }

    @Test
    fun sync_failure_is_exposed_as_result_and_progress() = runTest {
        coEvery { business.sync(any(), any()) } throws IllegalStateException("offline")

        val result = manager.sync()

        assertTrue(result.isFailure)
        assertEquals(SyncProgress.Error("offline"), manager.syncProgress.first())
    }

    @Test
    fun network_failure_is_classified_without_discarding_its_diagnostic_message() = runTest {
        coEvery { business.sync(any(), any()) } throws IOException("unexpected end of stream")

        val result = manager.sync()

        assertTrue(result.isFailure)
        assertEquals(
            SyncProgress.Error("unexpected end of stream", isNetworkFailure = true),
            manager.syncProgress.first()
        )
    }

    @Test
    fun sync_cancellation_is_rethrown_without_publishing_a_stale_error() = runTest {
        coEvery { business.sync(any(), any()) } throws CancellationException("screen left")

        var cancelled = false
        try {
            manager.sync()
        } catch (_: CancellationException) {
            cancelled = true
        }

        assertTrue(cancelled)
        assertEquals(SyncProgress.Idle, manager.syncProgress.first())
    }

    @Test
    fun sync_and_then_cancellation_is_rethrown_without_publishing_a_stale_error() = runTest {
        coEvery { business.sync(any(), any()) } throws
            CancellationException("account action cancelled")

        var cancelled = false
        try {
            manager.syncAndThen {}
        } catch (_: CancellationException) {
            cancelled = true
        }

        assertTrue(cancelled)
        assertEquals(SyncProgress.Idle, manager.syncProgress.first())
    }

    @Test
    fun queue_status_and_last_sync_time_come_from_incremental_repository() = runTest {
        coEvery { repository.hasPendingChanges() } returns true
        every { repository.getLastSyncTime() } returns flowOf(123L)

        assertTrue(manager.hasLocalData())
        assertEquals(123L, manager.getLastSyncTime().first())
    }
}
