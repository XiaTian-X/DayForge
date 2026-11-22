package com.dayforge.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/** Preserves the formal-v5 qualification regression AFTER the real end-to-end functional
 * oracle and JSON have completed. Neither case is a latency gate.
 */
@RunWith(AndroidJUnit4::class)
class CountTrustFormalProfileApplicabilityTest : CountTrustFormalProfileFixture() {
    @Test fun formalInitialDailyCountMustReusePersistentProofAfterRealAckAndSharedConsumers() = runBlocking<Unit> {
        requireFormalPersistentReuse()
    }

    @Test fun warmFormalSessionRevocationRejectsOldActionAndActualPreparedAckWithoutMutation() = runBlocking<Unit> {
        warmOldSessionCannotWriteOrAccept()
    }
}
