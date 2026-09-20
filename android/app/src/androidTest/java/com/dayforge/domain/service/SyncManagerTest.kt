package com.dayforge.domain.service

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith
import com.dayforge.data.model.SyncProgress
import com.dayforge.data.repository.IncrementalSyncRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@RunWith(AndroidJUnit4::class)
class SyncManagerTest {

    private lateinit var repository: IncrementalSyncRepository
    private lateinit var manager: SyncManager

    @Before
    fun setUp() {
        repository = mockk(relaxed = true)
        manager = SyncManager(repository)
    }

    @Test
    fun sync_delegates_exclusively_to_incremental_repository() = runTest {
        coEvery { repository.sync(any()) } answers {
            firstArg<(SyncProgress) -> Unit>()(SyncProgress.UploadingChanges(1, 1))
        }

        val result = manager.sync()

        assertTrue(result.isSuccess)
        assertEquals(SyncProgress.Success, manager.syncProgress.first())
        coVerify(exactly = 1) { repository.sync(any()) }
    }

    @Test
    fun sync_failure_is_exposed_as_result_and_progress() = runTest {
        coEvery { repository.sync(any()) } throws IllegalStateException("offline")

        val result = manager.sync()

        assertTrue(result.isFailure)
        assertEquals(SyncProgress.Error("offline"), manager.syncProgress.first())
    }

    @Test
    fun network_failure_is_classified_without_discarding_its_diagnostic_message() = runTest {
        coEvery { repository.sync(any()) } throws IOException("unexpected end of stream")

        val result = manager.sync()

        assertTrue(result.isFailure)
        assertEquals(
            SyncProgress.Error("unexpected end of stream", isNetworkFailure = true),
            manager.syncProgress.first()
        )
    }

    @Test
    fun queue_status_and_last_sync_time_come_from_incremental_repository() = runTest {
        coEvery { repository.hasPendingChanges() } returns true
        every { repository.getLastSyncTime() } returns flowOf(123L)

        assertTrue(manager.hasLocalData())
        assertEquals(123L, manager.getLastSyncTime().first())
    }
}
