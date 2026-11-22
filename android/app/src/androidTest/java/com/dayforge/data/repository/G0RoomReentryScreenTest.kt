package com.dayforge.data.repository

import android.os.SystemClock
import android.util.Log
import org.junit.Assert.*

/** Actual Room connection replacement in the measured setup; process/DataStore/OS caches remain warm. */
class G0RoomReentryScreenTest : G0MixedConsumerScreenTest() {
    internal override val deferReads=true
    internal override val resumeAfterCommit=true
    override val seedHistory=80
    override val immediateFirstEdit=true
    override fun sampleMode(sample:Int)="commit"
    override suspend fun beforeMeasuredSetup() {
        val at=SystemClock.elapsedRealtimeNanos();val previous=db
        freshGateForColdOpen();storage.reopen()
        assertFalse(previous.isOpen);assertNotSame(previous,db);assertFalse(db.isOpen)
        Log.i("G0RoomReentry","{\"close_and_builder_ns\":${SystemClock.elapsedRealtimeNanos()-at},\"old_closed\":true,\"new_unopened\":true,\"process_restarted\":false}")
    }
    override suspend fun afterSyncedScreen(sample:Int) {
        super.afterSyncedScreen(sample)
        val plan=adapter.spans.last { it.stage=="sync_plan" }
        assertTrue(plan.completed)
        Log.i("G0RoomSyncPlan","{\"sample\":$sample,\"wall_ns\":${plan.ended-plan.started}}")
    }

}
