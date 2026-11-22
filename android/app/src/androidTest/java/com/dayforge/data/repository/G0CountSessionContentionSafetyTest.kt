package com.dayforge.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith

/** Runs every predecessor-ACK, cancellation and unknown-result case on the combined policy. */
@RunWith(AndroidJUnit4::class)
class G0CountSessionContentionSafetyTest:G0CountWarmContentionSafetyTest() {
    override val reuseCountPreparation=false
    override val reuseCountPreparationAfterResult=true
}
