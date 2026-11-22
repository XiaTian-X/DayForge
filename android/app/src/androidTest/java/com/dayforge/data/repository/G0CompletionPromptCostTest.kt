package com.dayforge.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/** Original two complete cost methods retain their names, RECREATE default and budgets. */
@RunWith(AndroidJUnit4::class)
class G0CompletionPromptCostTest:G0CompletionPromptCostFixture() {
    @Test(timeout=1_800_000) fun eightHistoryThreeCountPromptJourneys()=runBlocking<Unit> {measureOriginal(8)}
    @Test(timeout=1_800_000) fun annualHistoryThreeCountPromptJourneys()=runBlocking<Unit> {measureOriginal(730)}
}
