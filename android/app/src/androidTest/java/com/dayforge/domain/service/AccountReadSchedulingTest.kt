package com.dayforge.domain.service

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Deterministic ordering on a physical Android runner; no sleep/timing threshold or fake mutex. */
@RunWith(AndroidJUnit4::class)
class AccountReadSchedulingTest {
    @Test fun queuedRefreshYieldsToCommittedReadAndPromptButNotAccountWork() = runBlocking {
        withTimeout(5_000) {
            val sessions = AccountSessionCoordinator()
            val events = mutableListOf<String>()
            val writing = CompletableDeferred<Unit>()
            val commit = CompletableDeferred<Unit>()
            val action = launch(start = CoroutineStart.UNDISPATCHED) {
                sessions.withInteractiveReadPriority {
                    sessions.exclusive(AccountSessionCoordinator.Work.NEXT_WRITE) {
                        writing.complete(Unit); commit.await(); events += "commit"
                    }
                    sessions.exclusive(AccountSessionCoordinator.Work.FACT_HISTORY_READ) { events += "after" }
                    sessions.exclusive(AccountSessionCoordinator.Work.COMPLETION_PROMPT_READ) { events += "prompt" }
                }
            }
            writing.await()
            val refresh = launch(start = CoroutineStart.UNDISPATCHED) {
                sessions.exclusive(AccountSessionCoordinator.Work.DASHBOARD_READ) { events += "refresh" }
            }
            val account = launch(start = CoroutineStart.UNDISPATCHED) {
                sessions.exclusive { events += "account" }
            }
            commit.complete(Unit)
            joinAll(action, refresh, account)
            assertEquals("commit", events.first())
            assertTrue(events.indexOf("account") < events.indexOf("after"))
            assertTrue(events.indexOf("after") < events.indexOf("prompt"))
            assertTrue(events.indexOf("prompt") < events.indexOf("refresh"))
        }
    }

    @Test fun cancellationAndFailureReleaseHintWithoutCancellingOtherWork() = runBlocking {
        withTimeout(5_000) {
            val sessions = AccountSessionCoordinator()
            for (fail in listOf(false, true)) {
                val release = CompletableDeferred<Unit>()
                val action = launch(start = CoroutineStart.UNDISPATCHED) {
                    try { sessions.withInteractiveReadPriority { release.await(); error("fixture") } }
                    catch (error: CancellationException) { throw error }
                    catch (error: IllegalStateException) { assertEquals("fixture", error.message) }
                }
                val refresh = async(start = CoroutineStart.UNDISPATCHED) {
                    sessions.exclusive(AccountSessionCoordinator.Work.DASHBOARD_READ) { "fresh" }
                }
                assertFalse(refresh.isCompleted)
                assertEquals("account", sessions.exclusive { "account" })
                if (fail) { release.complete(Unit); action.join() } else action.cancelAndJoin()
                assertEquals("fresh", refresh.await())
            }
            val finish = CompletableDeferred<Unit>()
            val action = launch(start = CoroutineStart.UNDISPATCHED) { sessions.withInteractiveReadPriority { finish.await() } }
            val cancelled = launch(start = CoroutineStart.UNDISPATCHED) {
                sessions.exclusive(AccountSessionCoordinator.Work.DASHBOARD_READ) { fail("Cancelled refresh cannot run") }
            }
            cancelled.cancelAndJoin()
            assertEquals(1, sessions.exclusive { 1 })
            finish.complete(Unit); action.join()
            assertEquals(2, sessions.exclusive(AccountSessionCoordinator.Work.DASHBOARD_READ) { 2 })
        }
    }

    @Test fun laterInteractionsCannotKeepDeferringAnAlreadyWaitingRefresh() = runBlocking {
        withTimeout(5_000) {
            val sessions = AccountSessionCoordinator()
            val firstDone = CompletableDeferred<Unit>()
            val laterDone = CompletableDeferred<Unit>()
            val first = launch(start = CoroutineStart.UNDISPATCHED) { sessions.withInteractiveReadPriority { firstDone.await() } }
            val refresh = async(start = CoroutineStart.UNDISPATCHED) {
                sessions.exclusive(AccountSessionCoordinator.Work.DASHBOARD_READ) { true }
            }
            assertFalse(refresh.isCompleted)
            val later = launch(start = CoroutineStart.UNDISPATCHED) { sessions.withInteractiveReadPriority { laterDone.await() } }
            firstDone.complete(Unit); first.join()
            assertTrue(refresh.await())
            assertFalse(later.isCompleted)
            laterDone.complete(Unit); later.join()
        }
    }

    @Test fun sameInteractionNestedReadsAndDifferentCoordinatorCannotDeadlock() = runBlocking {
        withTimeout(5_000) {
            val sessions = AccountSessionCoordinator()
            val other = AccountSessionCoordinator()
            sessions.withInteractiveReadPriority {
                sessions.withInteractiveReadPriority {
                    assertEquals(1, sessions.exclusive(AccountSessionCoordinator.Work.DASHBOARD_READ) { 1 })
                }
                assertEquals(2, other.exclusive(AccountSessionCoordinator.Work.DASHBOARD_READ) { 2 })
                assertEquals(3, sessions.exclusive(AccountSessionCoordinator.Work.DASHBOARD_PUBLISH) { 3 })
            }
            assertEquals(4, sessions.exclusive(AccountSessionCoordinator.Work.DASHBOARD_READ) { 4 })
        }
    }

    @Test fun alreadyRunningReadFinishesBeforeInteractionWithoutPreemption() = runBlocking {
        withTimeout(5_000) {
            val sessions = AccountSessionCoordinator()
            val finishRead = CompletableDeferred<Unit>()
            var readDone = false
            val refresh = launch(start = CoroutineStart.UNDISPATCHED) {
                sessions.exclusive(AccountSessionCoordinator.Work.DASHBOARD_READ) { finishRead.await(); readDone = true }
            }
            val action = launch(start = CoroutineStart.UNDISPATCHED) {
                sessions.withInteractiveReadPriority {
                    sessions.exclusive(AccountSessionCoordinator.Work.NEXT_WRITE) { assertTrue(readDone) }
                }
            }
            assertFalse(refresh.isCancelled); assertFalse(action.isCompleted)
            finishRead.complete(Unit); joinAll(refresh, action)
            assertTrue(readDone)
        }
    }
}
