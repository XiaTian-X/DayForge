package com.dayforge.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/** Real formal writer and fixed caller-known ID. Not a claim that OS handoff or real PID kill
 * has passed; those remain independent required platform gates in the complete candidate.
 */
@RunWith(AndroidJUnit4::class)
class CountCommandWidgetEntryTest : CountTrustFormalProfileFixture() {
    @Test(timeout = 150_000) fun committedSourceIsNotReplacedByAnUnrelatedLaterCurrentFact() = runBlocking<Unit> {
        widgetCommandFollowUpKeepsCommittedFactWhenLatestHasAdvanced()
    }

    @Test(timeout = 150_000) fun sameKnownIdResolvesAfterNativeReopenWithoutAnotherFact() = runBlocking<Unit> {
        widgetCommandKnownIdSurvivesReopenWithoutAnotherBusinessWrite()
    }
    @Test(timeout = 150_000) fun missingKnownIdAndDifferentCapturedPayloadDoNotExecute() = runBlocking<Unit> {
        widgetCommandMissingOrDifferentCaptureNeverRegistersOrReexecutes()
    }
}
