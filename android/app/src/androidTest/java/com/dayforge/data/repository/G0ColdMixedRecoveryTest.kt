package com.dayforge.data.repository

/** The complete explicit page recovery/cancellation matrix with cold result reuse enabled. */
class G0ColdMixedRecoveryTest:G0MixedRecoveryTest() {
    override val retainRecurringResults=true
}
