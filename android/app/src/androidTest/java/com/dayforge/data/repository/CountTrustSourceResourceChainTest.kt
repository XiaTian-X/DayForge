package com.dayforge.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/** Real foreground COUNT writer, with a fixed fault after its actual activity-rate UPDATE. */
@RunWith(AndroidJUnit4::class)
class CountTrustSourceResourceChainTest : CountTrustFormalProfileFixture() {
    private val nativeSpy = SourceWindowNativeSpy()
    internal override fun sourceWindowNativeSpy(): SourceWindowNativeSpy = nativeSpy

    @Test fun oversizedLastErrorAfterRealCountRateUsesOriginalBoundedRefusalAndRollsBack() = runBlocking<Unit> {
        countSourceLateOversizedSourceRejects(SourceWindowNativeSpy.LateSourceField.LAST_ERROR)
    }
    @Test fun oversizedPayloadAfterRealCountRateUsesOriginalBoundedRefusalAndRollsBack() = runBlocking<Unit> {
        countSourceLateOversizedSourceRejects(SourceWindowNativeSpy.LateSourceField.PAYLOAD_JSON)
    }
}
