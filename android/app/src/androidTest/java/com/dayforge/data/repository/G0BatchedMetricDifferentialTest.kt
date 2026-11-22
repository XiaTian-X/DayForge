package com.dayforge.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith

/** Reuses the full 39-table differential contract, with batched G0 initialization. */
@RunWith(AndroidJUnit4::class)
class G0BatchedMetricDifferentialTest : G0ShortMetricDifferentialTest() {
    override fun domainOpeningCallback() = G0SchemaOpeningCallback(true)
}
