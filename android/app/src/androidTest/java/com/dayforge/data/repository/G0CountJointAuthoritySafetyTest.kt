package com.dayforge.data.repository

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.local.TokenCipher
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Run the original COMPLETE classes with the explicit candidate, without copying or
 * weakening their assertions. Default classes continue to select the old G0 boundary. */
@RunWith(AndroidJUnit4::class)
class G0CountJointStoreSafetyTest:G0ShortCountSafetyTest() {
    override val jointCountAuthority=true
}
@RunWith(AndroidJUnit4::class)
class G0CountJointBacklogSafetyTest:G0ShortCountBacklogSafetyTest() {
    override val jointCountAuthority=true
}
@RunWith(AndroidJUnit4::class)
class G0CountJointInputSafetyTest:G0ShortCountInputSafetyTest() {
    override val jointCountAuthority=true
}
@RunWith(AndroidJUnit4::class)
class G0CountJointScreenSafetyTest:G0ShortCountScreenSafetyTest() {
    override val jointCountAuthority=true
}

@RunWith(AndroidJUnit4::class)
class G0CountJointAuthoritySafetyTest:G0ShortCountFixture() {
    private class Pause {
        val entered=CountDownLatch(1)
        val release=CountDownLatch(1)
    }
    private val pause=AtomicReference<Pause?>()
    override fun tokenCipher():TokenCipher {
        val real=super.tokenCipher()
        return object:TokenCipher {
            override fun encrypt(value:String)=real.encrypt(value)
            override fun decrypt(value:String):String? {
                val result=real.decrypt(value)
                pause.getAndSet(null)?.let {
                    it.entered.countDown()
                    check(it.release.await(10,TimeUnit.SECONDS)) {"G0_JOINT_TEST_RELEASE_TIMEOUT"}
                }
                return result
            }
        }
    }
    private suspend fun changeAndRestore() {
        val before=dataStore.data.first()
        dataStore.edit {it[stringPreferencesKey("sync_device_capability_revision")]="2"}
        dataStore.updateData {before}
        assertTrue(before==dataStore.data.first())
    }
    private suspend fun <T> changeDuringRealDecrypt(body:suspend ()->T):Result<T> = coroutineScope {
        val barrier=Pause();pause.set(barrier)
        val operation=async(Dispatchers.Default) {runCatching {body()}}
        try {
            assertTrue(withContext(Dispatchers.IO) {barrier.entered.await(10,TimeUnit.SECONDS)})
            changeAndRestore()
        } finally {barrier.release.countDown();pause.set(null)}
        withTimeout(10_000) {operation.await()}
    }
    @Test fun oldReadThenLeaseCounterexampleRemainsReproducible()=runBlocking<Unit> {
        seedAccepted(1);val store=openCount(jointAuthority=false)
        // Old code reads A, misses the complete A->B->A transition, then leases
        // the NEW revision. Keep this counterexample; passing means it reproduced.
        val result=changeDuringRealDecrypt {store.increase()}
        assertTrue(result.isSuccess);assertEquals(2,pageRowCount("completions"))
        assertEquals(1,pageRowCount("sync_outbox"))
    }
    @Test fun candidateBindsWriteReadRecoveryAndAckToThePreReadLease()=runBlocking<Unit> {
        seedAccepted(1);val image=storage.snapshotClosedDatabase()
        for(kind in listOf("write","read","recovery","ack")) {
            freshGateForColdOpen();restoreForJourney(image)
            val store=openCount(jointAuthority=true);val command=store.newCommand()
            val delivery=if(kind=="ack") {
                val saved=store.increase();val (http,_)=channel {successReply(it)}
                requireNotNull(store.send(http,saved.requestId))
            } else null
            val before=pageNativeState();var published=false
            val result=changeDuringRealDecrypt {
                when(kind) {
                    "write"->store.increase(command)
                    "read"->store.readAndPublish {published=true}
                    "recovery"->store.recover(command)
                    else->store.acknowledge(requireNotNull(delivery))
                }
            }
            assertTrue("$kind must retain its pre-read revision: ${result.exceptionOrNull()}",
                result.exceptionOrNull() is G0AuthorityOwner.Denied)
            assertFalse(published);assertEquals(before,pageNativeState())
        }
    }
    @Test fun sendPreparationCannotRebaseItsLeaseAfterAnAuthorityRoundTrip()=runBlocking<Unit> {
        seedAccepted(1);val store=openCount(jointAuthority=true);val saved=store.increase()
        val (http,server)=channel {successReply(it)};val before=pageNativeState()
        store.afterEntryAuthenticationForTest={changeAndRestore()}
        assertTrue(runCatching {store.send(http,saved.requestId)}.exceptionOrNull() is G0AuthorityOwner.Denied)
        assertEquals(before,pageNativeState())
        assertTrue(server.requests.none {it.path.endsWith("/push")})
    }
    @Test fun transitionBeforeFinalPermitCannotBeRetriedAsADataConflict()=runBlocking<Unit> {
        seedAccepted(1);val store=openCount(jointAuthority=true);val before=pageNativeState();var prepares=0
        store.afterPrepareForTest={prepares++;changeAndRestore()}
        assertTrue(runCatching {store.increase()}.exceptionOrNull() is G0AuthorityOwner.Denied)
        assertEquals(1,prepares);assertEquals(before,pageNativeState())
    }
    @Test fun responseAfterAuthorityRoundTripCannotBorrowANewLease()=runBlocking<Unit> {
        seedAccepted(1);val store=openCount(jointAuthority=true);val saved=store.increase()
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val (http,server)=channel {input ->
            if(input.path.endsWith("/push")) {entered.countDown();check(release.await(15,TimeUnit.SECONDS))}
            successReply(input)
        }
        val operation=async(Dispatchers.IO) {runCatching {store.send(http,saved.requestId)}}
        try {
            assertTrue(withContext(Dispatchers.IO) {entered.await(10,TimeUnit.SECONDS)})
            val sentState=pageNativeState();changeAndRestore();release.countDown()
            assertTrue(withTimeout(10_000) {operation.await()}.exceptionOrNull() is G0AuthorityOwner.Denied)
            assertEquals(sentState,pageNativeState());assertEquals(1,pageRowCount("sync_outbox"))
            assertEquals(1,server.requests.count {it.path.endsWith("/push")})
        } finally {release.countDown();operation.cancelAndJoin()}
    }
    @Test fun alreadyPermittedCommitPrecedesLaterCredentialPersistenceInBothArms()=runBlocking<Unit> {
        seedAccepted(1);val image=storage.snapshotClosedDatabase()
        val credentials=dataStore.data.first()
        for(joint in listOf(false,true)) {
            dataStore.updateData {credentials};freshGateForColdOpen();restoreForJourney(image)
            val store=openCount(jointAuthority=joint);val reserved=CompletableDeferred<Unit>()
            authority.onTransitionForTest={reserved.complete(Unit)}
            val mutation=async(Dispatchers.IO,start=CoroutineStart.LAZY) {
                tokens.saveLoginSession("synthetic-other","synthetic-refresh","member",id(3991),false)
            }
            var finalDecrypts=0
            store.beforeFinalForTest={
                mutation.start();withTimeout(10_000) {reserved.await()}
                assertFalse(mutation.isCompleted);finalDecrypts=decryptCount.get()
            }
            try {
                store.increase()
                assertEquals(if(joint)1 else 2,decryptCount.get()-finalDecrypts)
                withTimeout(10_000) {mutation.await()}
                assertEquals(2,pageRowCount("completions"));assertEquals(1,pageRowCount("sync_outbox"))
                var published=false
                assertTrue(runCatching {store.readAndPublish {published=true}}.isFailure);assertFalse(published)
            } finally {authority.onTransitionForTest={};mutation.cancelAndJoin()}
        }
    }
}
