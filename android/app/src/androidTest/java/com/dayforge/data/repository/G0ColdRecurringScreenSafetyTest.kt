package com.dayforge.data.repository

/** Shared interaction fixture: original screen revocation and cancellation with cold reuse. */
class G0ColdRecurringScreenSafetyTest:G0RecurringScreenSafetyTest() {
    override val retainRecurringResults=true
}
