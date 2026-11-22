package com.dayforge.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith

/** Exact inherited 32-case original-validator matrix, only the candidate open callback differs. */
@RunWith(AndroidJUnit4::class)
class G0BatchedColdRejectionMatrixTest : G0ColdRejectionMatrixTest() {
    override fun domainOpeningCallback() = G0SchemaOpeningCallback(true)
}
