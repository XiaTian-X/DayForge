package com.dayforge.data.repository

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class G0IndexAttributionTest:G0IndexFixture() {
    @Test(timeout=900_000) fun originalEightyHistoryReadWriteSendAckAndActualContention()=runBlocking<Unit> {
        seedAccepted(80);plans("original_80")
        val before=pageNativeState()
        val a=measure("home_original_80",false) {readHome()}
        val b=measure("home_profile_80") {readHome()}
        assertEquals(a.value.counts,b.value.counts);assertEquals(a.value.habits,b.value.habits);assertEquals(before,pageNativeState())
        val command=measure("command_profile_80") {seedFact()}
        val (http,_)=channel {successReply(it)};val requests=sender(http)
        val row=db.syncOutboxDao().getEntityIntents("completion",command.value.uuid).single()
        val sent=measure("send_profile_80") {requireNotNull(requests.sendOperation(access(),row.operationId))}
        // Both real operations are ready together. No synthetic account lock or separate DB.
        coroutineScope {
            val start=CompletableDeferred<Unit>()
            val read=async(Dispatchers.IO) {start.await();measure("home_competing_80") {readHome()}}
            val ack=async(Dispatchers.IO) {start.await();measure("ack_competing_80") {requests.acceptOperation(sent.value)}}
            start.complete(Unit);read.await();assertEquals(NextOperationAcceptance.COMMITTED,ack.await().value)
        }
        val native=pageNativeState();assertEquals(NextOperationAcceptance.REPLAYED,requests.acceptOperation(sent.value));assertEquals(native,pageNativeState())
        assertEquals(81,count("completions"));assertEquals(81,count("next_acceptances"));assertEquals(0,count("sync_outbox"))
        measure("home_after_ack_81") {readHome()}
    }
    @Test(timeout=120_000) fun controlledAccountAndRoomQueueAttributionKeepsOriginalReadGuards()=runBlocking<Unit> {
        seedAccepted(1)
        for(account in listOf(true,false)) {
            val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();val requested=CompletableDeferred<Unit>()
            val holder=launch(Dispatchers.IO) {
                if(account) sessions.exclusive {entered.complete(Unit);release.await()}
                else db.withTransaction {entered.complete(Unit);release.await()}
            }
            entered.await()
            val read=async(Dispatchers.IO) {requested.complete(Unit);measure(if(account) "controlled_account_wait" else "controlled_room_wait") {readHome()}}
            try {requested.await();delay(250);assertFalse(read.isCompleted)} finally {release.complete(Unit)}
            holder.join();val result=read.await();assertTrue(result.elapsed>=200_000_000)
            assertEquals(1L,result.value.counts.getValue(habit.id).todayQuantity)
        }
        assertEquals(1,count("completions"));assertEquals(1,count("next_acceptances"));assertEquals(0,count("sync_outbox"))
    }
}
