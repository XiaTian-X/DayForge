package com.dayforge.domain.service

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

class SyncManagerTest {

    private lateinit var repository: IncrementalSyncRepository
    private lateinit var manager: SyncManager

    @Before
    fun setUp() {
        repository = mockk(relaxed = true)
        manager = SyncManager(repository)
    }

    @Test
    fun `sync delegates exclusively to incremental repository`() = runTest {
        coEvery { repository.sync(any()) } answers {
            firstArg<(SyncProgress) -> Unit>()(SyncProgress.UploadingChanges(1, 1))
        }

        val result = manager.sync()

        assertTrue(result.isSuccess)
        assertEquals(SyncProgress.Success, manager.syncProgress.first())
        coVerify(exactly = 1) { repository.sync(any()) }
    }

    @Test
    fun `sync failure is exposed as result and progress`() = runTest {
        coEvery { repository.sync(any()) } throws IllegalStateException("offline")

        val result = manager.sync()

        assertTrue(result.isFailure)
        assertEquals(SyncProgress.Error("offline"), manager.syncProgress.first())
    }

    @Test
    fun `network failure is classified without discarding its diagnostic message`() = runTest {
        coEvery { repository.sync(any()) } throws IOException("unexpected end of stream")

        val result = manager.sync()

        assertTrue(result.isFailure)
        assertEquals(
            SyncProgress.Error("unexpected end of stream", isNetworkFailure = true),
            manager.syncProgress.first()
        )
    }

    @Test
    fun `queue status and last sync time come from incremental repository`() = runTest {
        coEvery { repository.hasPendingChanges() } returns true
        every { repository.getLastSyncTime() } returns flowOf(123L)

        assertTrue(manager.hasLocalData())
        assertEquals(123L, manager.getLastSyncTime().first())
    }
}
