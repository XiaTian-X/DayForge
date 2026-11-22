package com.dayforge.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/** Original production widget +1 fallback. The real claim excludes the typed executor,
 * while the original +1 source plan still requires its metadata-first bounded fallback.
 */
@RunWith(AndroidJUnit4::class)
class CountTrustSourceResourceFallbackChainTest : CountTrustFormalProfileFixture() {
    private val nativeSpy = SourceWindowNativeSpy()
    internal override fun sourceWindowNativeSpy(): SourceWindowNativeSpy = nativeSpy

    @Test fun oversizedLastErrorAfterRealWidgetCountRateKeepsOriginalMetadataGateAndRollsBack() = runBlocking<Unit> {
        countSourceLateOversizedWidgetSourceRejects(SourceWindowNativeSpy.LateSourceField.LAST_ERROR)
    }
    @Test fun oversizedPayloadAfterRealWidgetCountRateKeepsOriginalMetadataGateAndRollsBack() = runBlocking<Unit> {
        countSourceLateOversizedWidgetSourceRejects(SourceWindowNativeSpy.LateSourceField.PAYLOAD_JSON)
    }
}
