package com.dayforge.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith

/** Same actual-screen assertions, including timezone boundary and unknown COMMIT. */
@RunWith(AndroidJUnit4::class)
class G0CountEntryScreenSafetyTest:G0CountJourneyScreenSafetyTest() {
    override fun domainOpeningCallback()=G0SchemaOpeningCallback(true)
}
