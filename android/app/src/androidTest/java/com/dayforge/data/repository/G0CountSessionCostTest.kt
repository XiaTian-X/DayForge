package com.dayforge.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith

/** Same counterbalanced seed images, physical taps, real overlapping ACK and original budgets. */
@RunWith(AndroidJUnit4::class)
class G0CountSessionCostTest:G0CountWarmContentionCostTest() {
    override fun candidateJourney()=journey(prepareBlocks=false,afterResult=true)
    override val candidateSharesFirstPreparation=false
}
