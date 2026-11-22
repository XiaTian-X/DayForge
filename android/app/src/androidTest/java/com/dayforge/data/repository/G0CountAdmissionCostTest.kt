package com.dayforge.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith

/** Same two complete cost methods, now both sides use initial pure reuse and original
 * interaction priority; only the pre-read admission/coalescing differs. */
@RunWith(AndroidJUnit4::class)
class G0CountAdmissionCostTest:G0CountDashboardCostTest() {
    override val compareAdmission=true
}
