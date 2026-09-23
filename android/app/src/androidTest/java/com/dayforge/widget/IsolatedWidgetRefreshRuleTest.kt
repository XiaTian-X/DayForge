package com.dayforge.widget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.Operation
import androidx.work.WorkManager
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.PhysicalDatabaseRule
import com.dayforge.data.model.HabitSchedule
import com.dayforge.data.model.HabitType
import com.dayforge.data.repository.HabitRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement

@RunWith(AndroidJUnit4::class)
class IsolatedWidgetRefreshRuleTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager = mockk<WorkManager>(relaxed = true)
    private val operation = mockk<Operation>(relaxed = true)
    private val description = Description.createTestDescription(javaClass, "owned database scope")

    @Before fun setup() {
        mockkStatic(WorkManager::class)
        every { WorkManager.getInstance(context) } returns manager
        every { manager.enqueueUniqueWork(any(), any(), any<OneTimeWorkRequest>()) } returns operation
    }

    @After fun cleanup() { unmockkStatic(WorkManager::class) }

    @Test fun scope_covers_setup_body_teardown_and_restores_real_scheduler() = assertScope(false)

    @Test fun failing_body_still_cleans_up_and_restores_real_scheduler() = assertScope(true)

    private fun assertScope(fail: Boolean) {
        val refresh = IsolatedWidgetRefreshRule()
        val fixture = object : ExternalResource() {
            override fun before() { assertNull(WidgetRefreshScheduler.request(context)) }
            override fun after() { assertNull(WidgetRefreshScheduler.request(context)) }
        }
        val expected = IllegalStateException("test body failure")
        val body = object : Statement() {
            override fun evaluate() {
                assertNull(WidgetRefreshScheduler.request(context))
                if (fail) throw expected
            }
        }
        val error = runCatching {
            RuleChain.outerRule(refresh).around(fixture).apply(body, description).evaluate()
        }.exceptionOrNull()
        if (fail) assertSame(expected, error) else assertNull(error)
        assertEquals(3, refresh.requestCount)
        verify(exactly = 0) { manager.enqueueUniqueWork(any(), any(), any<OneTimeWorkRequest>()) }
        // The global object must be restored for subsequent real scheduler/worker suites.
        assertSame(operation, WidgetRefreshScheduler.request(context))
        verify(exactly = 1) {
            manager.enqueueUniqueWork(WidgetRefreshScheduler.WORK_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE, any<OneTimeWorkRequest>())
        }
    }

    @Test fun repeated_owned_database_scopes_commit_outbox_and_close_without_enqueuing_workers() {
        val refresh = IsolatedWidgetRefreshRule()
        repeat(3) {
            val storage = PhysicalDatabaseRule()
            lateinit var owned: HabitDatabase
            val body = object : Statement() {
                override fun evaluate() = runBlocking {
                    owned = storage.database
                    val repository = HabitRepository(owned.habitDao(), owned.completionDao(), owned.timeLogDao(), owned)
                    val id = repository.createHabit(name = "Owned", description = "",
                        habitType = HabitType.CHECK_IN, iconResId = 1, colorHex = "#123456",
                        schedule = HabitSchedule.Daily)
                    repository.logCompletion(context, id, 1)
                    assertEquals(1, owned.completionDao().getAllCompletionsOnce().size)
                    assertTrue(owned.syncOutboxDao().getAll().any { it.recordType == "completion" })
                    assertEquals(1, refresh.requestCount)
                }
            }
            RuleChain.outerRule(refresh).around(storage).apply(body, description).evaluate()
            assertFalse(owned.isOpen)
            assertFalse(context.getDatabasePath("habit_database").exists())
        }
        verify(exactly = 0) { manager.enqueueUniqueWork(any(), any(), any<OneTimeWorkRequest>()) }
    }
}
