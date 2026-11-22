package com.dayforge.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/** Pinned Room compatibility assertion is only a pre-T0 negative boundary. The original
 * CountAddExecutorTest ambient participant method and all its assertions remain unchanged.
 * This new complete class has not been compiled or run; no performance/process claim.
 */
@RunWith(AndroidJUnit4::class)
class CountCommandEntryBoundaryTest : CountTrustFormalProfileFixture() {
    @Test(timeout = 150_000) fun sameThreadAmbientCommandRejectsBeforeT0() = runBlocking<Unit> {
        commandTopEntryRejectsAmbientBeforeRegistration(0)
    }
    @Test(timeout = 150_000) fun inheritedRoomContextOnDefaultRejectsBeforeT0() = runBlocking<Unit> {
        commandTopEntryRejectsAmbientBeforeRegistration(1)
    }
    @Test(timeout = 150_000) fun inheritedRoomWithHeldAccountLockRejectsWithoutReentrantWait() = runBlocking<Unit> {
        commandTopEntryRejectsAmbientBeforeRegistration(2)
    }
    @Test(timeout = 150_000) fun normalTopEntryOwnsOneDurableCommand() = runBlocking<Unit> {
        commandNormalTopEntryOwnsT0AndOneActualT1()
    }
    @Test(timeout = 150_000) fun cancelledTopEntryDoesNotRegister() = runBlocking<Unit> {
        commandAlreadyCancelledEntryDoesNotRegister()
    }
}
