package com.dayforge.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CountTrustFormalRoundSafetyTest : CountTrustFormalProfileFixture() {
    @Test fun sidecarEffectsMatchIndependentOriginalAndRollBackExactly() = runBlocking<Unit> {
        sidecarMatchesOriginalAndOrderedNativeEffects()
    }
    @Test fun legitimateExtraMetadataFallsBackWhileWrongBirthCannotCommit() = runBlocking<Unit> {
        extraMetadataFallsBackAndWrongBirthRejects()
    }
    @Test fun knownLegacyCoverageRebuildsAndInvalidMacNeverRepairs() = runBlocking<Unit> {
        knownLegacyRootRebuildsButBadMacDoesNotRepair()
    }
    @Test fun sameDayRoundsKeepSharedPolicyAndRejectHiddenOldFactDamage() = runBlocking<Unit> {
        acceptedRoundsShareDayRuleWithoutMixingFacts()
    }
}
