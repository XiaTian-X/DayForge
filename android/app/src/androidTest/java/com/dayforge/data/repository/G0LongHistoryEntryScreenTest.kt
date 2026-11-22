package com.dayforge.data.repository

/** Selected combination, eighty real accepted requests, then the first visible save and twenty ACK collisions. */
class G0LongHistoryEntryScreenTest : G0MixedConsumerScreenTest() {
    internal override val deferReads=true
    internal override val resumeAfterCommit=true
    override val seedHistory=80
    override val immediateFirstEdit=true
    override fun sampleMode(sample:Int)="commit"
}
