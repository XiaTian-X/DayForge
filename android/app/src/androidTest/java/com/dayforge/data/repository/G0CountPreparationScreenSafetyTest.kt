package com.dayforge.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class G0CountPreparationScreenSafetyTest:G0CountJourneyScreenSafetyTest() {
    override val reuseCountPreparation=true
    override fun domainOpeningCallback()=G0SchemaOpeningCallback(true)
}
