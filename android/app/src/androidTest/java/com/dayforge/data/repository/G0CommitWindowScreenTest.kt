package com.dayforge.data.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Twenty adjacent pairs alternate order. All forty journeys, including the first, remain measured. */
class G0CommitWindowScreenTest : G0MixedConsumerScreenTest() {
    internal override val deferReads=true
    internal override val resumeAfterCommit=true
    override val sampleCount=40
    override fun sampleMode(sample:Int)=if(sample%4 in setOf(1,2)) "commit" else "control"
    override suspend fun beforeSample(sample:Int) {
        withContext(Dispatchers.Main) {
            summary.deferBackgroundReadsForTest=sampleMode(sample)=="commit"
        }
    }
}
