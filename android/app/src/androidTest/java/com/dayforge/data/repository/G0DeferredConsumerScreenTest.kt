package com.dayforge.data.repository

/** Same 20 real commands/ACKs, with a bounded, interaction-scoped summary refresh window. */
class G0DeferredConsumerScreenTest : G0MixedConsumerScreenTest() {
    internal override val deferReads=true
}
