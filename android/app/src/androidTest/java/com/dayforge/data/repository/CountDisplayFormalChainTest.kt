package com.dayforge.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/** Actual public service, compact production home/prompt, exact formal outbox/SEND/ACK,
 * real dual metric submission/replay and independent original terminal evidence. No cost pass. */
@RunWith(AndroidJUnit4::class)
class CountDisplayFormalChainTest : CountTrustFormalProfileFixture() {
    @Test fun newCountThroughBothMetricAcksAndNextCountUsesTheCompactConsumerContract() = runBlocking<Unit> {
        actualCompactFormalMetricPromptChain(0)
    }
    @Test fun existingFormalHistoryKeepsCompactResultsThroughTheCompleteProducerAndAckChain() = runBlocking<Unit> {
        actualCompactFormalMetricPromptChain(8)
    }
}
