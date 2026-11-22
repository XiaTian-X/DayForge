package com.dayforge.data.repository

/** Existing 80 metric/80 count, 20 ready background ACK competitions with cold result reuse. */
class G0ColdRecurringMixedScreenTest:G0RecurringMixedScreenTest() {
    override val retainRecurringResults=true
}
