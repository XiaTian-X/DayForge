package com.dayforge.data.repository

import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.room.withTransaction
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import com.dayforge.data.api.NextSyncHttp
import com.dayforge.data.api.dto.NextSyncPushResponse
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule

/** Same closed image, real original producer/HTTP, original editor/adapter/store. No draft cache.
 * History counts accepted definitions; all conditions additionally start with one frozen pending
 * predecessor. Its ACK timing changes actual successor lineage and is NOT an equal-work A/B. */
abstract class G0EditPreparationFixture : G0SealedDomainFixture() {
    @get:Rule val activity = ActivityScenarioRule(ComponentActivity::class.java)
    override fun domainOpeningCallback() = G0SchemaOpeningCallback(true)
    private val candidateBuilder = requireNotNull(storage.isolatedBuilder)
    private val harnesses = mutableListOf<Harness>()
    internal fun now() = SystemClock.elapsedRealtimeNanos()
    internal val editedName = "Legally edited definition"
    internal val baseName = "Shared pending definition"
    internal val baseDescription = "Shared editing description"

    internal data class Seed(val history:Int,val http:NextSyncHttp,val image:ByteArray,val imageHash:String,
        val acceptedIds:List<String>,val pending:NextCoreDelivery<NextSyncPushResponse>)
    internal suspend fun seedEdits(history:Int):Seed {
        register()
        val (http,_)=channel { input -> successReply(input,revision=(if(input.path.endsWith("/identity")) 0L else
            wireOperation(input)["base_revision"]?.jsonPrimitive?.longOrNull ?: 0L)+1) }
        val ids=mutableListOf<String>()
        repeat(history) { index ->
            val row=editMetric("Accepted definition $index",baseDescription)
            val delivery=requireNotNull(sender(http).sendOperation(access(),row.operationId))
            assertEquals(NextOperationAcceptance.COMMITTED,sender(http).acceptOperation(delivery));ids+=row.operationId
        }
        val pending=editMetric(baseName,baseDescription)
        val delivery=requireNotNull(sender(http).sendOperation(access(),pending.operationId))
        assertEquals(history,count("next_acceptances"));assertEquals(history+1,count("next_request_origins"))
        assertEquals(1,count("sync_outbox"))
        // Helper checkpoints/closes the DB before reading; its finally creates a fresh original Room.
        val image=storage.snapshotClosedDatabase()
        return Seed(history,http,image,nextRequestHash(image),ids,delivery)
    }

    internal data class Decision(val accepted:Boolean,val at:Long,val version:Long,val phase:G0InteractionSession.Phase)
    internal data class InputState(val session:Long,val version:Long,val inputVersion:Long,val phase:String,val renderable:Boolean)
    internal data class InputDriverEvent(val history:Int,val lookupStarted:Long,val qualifiedAt:Long?,
        val dispatchStarted:Long?,val dispatchReturned:Long?,val callbackObservedAt:Long?,val lookupPasses:Int,
        val rejectedCandidates:Int,val refreshChecked:Boolean,val refreshed:Boolean,val setTextAdvertised:Boolean,
        val enabled:Boolean,val editable:Boolean,val visible:Boolean,val accepted:Boolean?,val before:InputState,val after:InputState)
    internal class SaveCall(val name:String,val description:String,val command:String,val started:Long) {
        @Volatile var ended:Long?=null
        @Volatile var completed=false
    }
    internal class RecordingPort(private val delegate:G0MetricInteractionAdapter):G0InteractionPort by delegate {
        val calls=CopyOnWriteArrayList<SaveCall>()
        override suspend fun save(name:String,description:String,command:String):G0InteractionPort.Saved {
            val call=SaveCall(name,description,command,SystemClock.elapsedRealtimeNanos());calls+=call
            return try { delegate.save(name,description,command).also { call.completed=true } }
            finally { call.ended=SystemClock.elapsedRealtimeNanos() }
        }
    }
    internal inner class Harness(val seed:Seed) {
        val owner=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        val coldTrace=G0PhaseTrace(driverTiming=false)
        val trace=G0PhaseTrace(driverTiming=false)
        val ackTrace=G0PhaseTrace(driverTiming=false)
        val builds=AtomicInteger()
        val observation=G0ScreenObservation()
        val decisions=CopyOnWriteArrayList<Decision>()
        val inputDriverEvents=CopyOnWriteArrayList<InputDriverEvent>()
        var beforeInitialization:suspend ()->Unit={}
        lateinit var store:G0ShortMetricStore
        lateinit var adapter:G0MetricInteractionAdapter
        lateinit var session:G0InteractionSession
        lateinit var port:RecordingPort
        private var stopped=false
        suspend fun initialize() {
            val captured=access()
            val runtime=G0DeviceBoundMetricRuntime(captured,owner) {
                // owner.async does not inherit adapter's caller trace. Measure actual cold work here.
                beforeInitialization();builds.incrementAndGet()
                coldTrace.run {
                    G0ShortMetricStore(db,tokens,sessions,authority,domainGate,metric.uuid,prepareColdInputs=false).also {
                        it.initialize(captured,trustedDomainSchema(true));store=it
                    }
                }
            }
            adapter=G0MetricInteractionAdapter(tokens,captured,authority,domainGate,seed.http,owner,runtime)
            adapter.traceForTest=trace
            port=RecordingPort(adapter);session=G0InteractionSession(port,owner)
            activity.scenario.onActivity { it.setContent {
                G0InteractionScreen(session,observation,onConfirmDecision={ accepted ->
                    val state=session.state.value;decisions+=Decision(accepted,now(),state.version,state.phase)
                })
            } }
            observation.await(session.state.value.version)
        }
        suspend fun phase(expected:G0InteractionSession.Phase)=withTimeout(20_000) { session.state.first { it.phase==expected } }
        suspend fun pixels(expected:G0InteractionSession.Phase):G0ScreenObservation.Seen {
            phase(expected)
            return G0ScreenObservation.awaitTogether {
                val state=session.state.value
                if(state.phase==expected && state.renderable()) listOf(G0ScreenObservation.Target(observation,state.version) {
                    session.state.value===state && state.renderable()
                }) else null
            }
        }
        suspend fun openEditable():Pair<Long,G0ScreenObservation.Seen> {
            val started=observation.tap("open")
            val seen=pixels(G0InteractionSession.Phase.EDITING)
            assertEquals(baseName,session.state.value.name);assertEquals(baseDescription,session.state.value.description)
            assertEquals(1,session.state.value.publication!!.value.pending)
            awaitText(baseName)
            return started to seen
        }
        /** Return immediately after the real text callback is reflected in State. No draft-frame wait. */
        suspend fun typeName(value:String=editedName):Long {
            val lookupStarted=now()
            val old=session.state.value
            fun state(s:G0InteractionSession.State)=InputState(s.session,s.version,s.inputVersion,s.phase.name,s.renderable())
            val before=state(old)
            var qualifiedAt:Long?=null;var dispatchStarted:Long?=null;var dispatchReturned:Long?=null
            var callbackObservedAt:Long?=null;var accepted:Boolean?=null
            var lookupPasses=0;var rejectedCandidates=0;var refreshChecked=false;var refreshed=false
            var advertised=false;var enabled=false;var editable=false;var visible=false
            fun currentEditing():Boolean {
                val current=session.state.value
                return current.session==old.session && current.phase==G0InteractionSession.Phase.EDITING &&
                    current.renderable() && current.inputVersion==old.inputVersion && current.name==old.name &&
                    current.description==old.description
            }
            fun qualified(node:AccessibilityNodeInfo)=node.text?.toString()==old.name && node.isVisibleToUser &&
                node.isEnabled && node.isEditable && node.actionList.any { it.id==AccessibilityNodeInfo.ACTION_SET_TEXT }
            fun find(node:AccessibilityNodeInfo):AccessibilityNodeInfo? {
                if(qualified(node)) {
                    refreshChecked=true;refreshed=node.refresh()
                    advertised=node.actionList.any { it.id==AccessibilityNodeInfo.ACTION_SET_TEXT }
                    enabled=node.isEnabled;editable=node.isEditable;visible=node.isVisibleToUser
                    if(refreshed && qualified(node)) return node
                    rejectedCandidates++
                }
                for(index in 0 until node.childCount) node.getChild(index)?.let { find(it)?.let { found->return found } }
                return null
            }
            try {
                check(currentEditing()) { "G0_EDIT_INPUT_NOT_CURRENT" }
                val node=withTimeout(5_000) {
                    var found:AccessibilityNodeInfo?=null
                    while(found==null) {
                        check(currentEditing()) { "G0_EDIT_INPUT_NOT_CURRENT" };lookupPasses++
                        found=InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow?.let(::find)
                        if(found==null) delay(8)
                    }
                    requireNotNull(found)
                }
                qualifiedAt=now();check(currentEditing()) { "G0_EDIT_INPUT_NOT_CURRENT" }
                dispatchStarted=now()
                // Qualifying an obsolete node may repeat the lookup, never the input action.
                accepted=node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,value)
                })
                dispatchReturned=now()
                check(accepted==true) { "G0_EDIT_SET_TEXT_REJECTED" }
                withTimeout(5_000) { session.state.first {
                    it.session==old.session && it.name==value && it.inputVersion>old.inputVersion
                } }
                callbackObservedAt=now()
                return callbackObservedAt
            } finally {
                val event=InputDriverEvent(seed.history,lookupStarted,qualifiedAt,dispatchStarted,dispatchReturned,
                    callbackObservedAt,lookupPasses,rejectedCandidates,refreshChecked,refreshed,advertised,enabled,editable,
                    visible,accepted,before,state(session.state.value))
                inputDriverEvents+=event
                // Successful paths retain diagnostics in memory. Logging must not insert a wait
                // between the observed real draft callback and the earliest explicit confirmation.
                if(callbackObservedAt==null) Log.e("G0EditPreparationInputDriver",buildJsonObject {
                    put("history",event.history);put("lookup_started_ns",event.lookupStarted)
                    event.qualifiedAt?.let { put("lookup_ns",it-event.lookupStarted) }
                    event.dispatchStarted?.let { put("dispatch_started_ns",it) }
                    event.dispatchReturned?.let { put("dispatch_returned_ns",it) }
                    put("lookup_passes",event.lookupPasses);put("rejected_candidates",event.rejectedCandidates)
                    put("refresh_checked",event.refreshChecked);put("refreshed",event.refreshed)
                    put("set_text_advertised",event.setTextAdvertised);put("enabled",event.enabled)
                    put("editable",event.editable);put("visible",event.visible)
                    event.accepted?.let { put("accepted",it) }
                    fun snapshot(s:InputState)=buildJsonObject {
                        put("session",s.session);put("version",s.version);put("input_version",s.inputVersion)
                        put("phase",s.phase);put("renderable",s.renderable)
                    }
                    put("before",snapshot(event.before));put("after",snapshot(event.after))
                }.toString())
            }
        }
        suspend fun acceptOld():Pair<Long,Long> {
            val start=now()
            val accepted=ackTrace.run { withContext(Dispatchers.IO) { store.acknowledge(seed.pending) } }
            assertEquals(NextOperationAcceptance.COMMITTED,accepted)
            return start to now()
        }
        suspend fun stop() {
            if(stopped) return
            stopped=true
            withContext(Dispatchers.Main) { session.detach() }
            withTimeout(20_000) { session.awaitSettled() }
            owner.coroutineContext.job.cancelAndJoin()
            db.close()
        }
    }

    internal suspend fun restoredHarness(seed:Seed):Harness {
        harnesses.forEach { it.stop() }
        // restore closes the prior Room before replacing the image, and reopens an original builder.
        storage.restoreClosedDatabase(seed.image)
        assertEquals(seed.imageHash,nextRequestHash(seed.image))
        // restore's finally constructs an original Room object, but do not open that connection
        // just to measure byte identity: onOpen/checkpoint work would change the thing measured.
        db.close()
        check(app.packageName=="com.dayforge.testbed")
        val file=app.getDatabasePath("habit_database")
        val wal=java.io.File(file.path+"-wal")
        check(!wal.exists() || wal.length()==0L)
        assertEquals("Closed restore must preserve exact image",seed.imageHash,nextRequestHash(file.readBytes()))
        freshGateForColdOpen();storage.isolatedBuilder=candidateBuilder;storage.reopen()
        return Harness(seed).also { harnesses+=it;it.initialize() }
    }
    internal suspend fun original(h:Harness) {
        h.stop();storage.isolatedBuilder=null;storage.reopen()
    }
    internal data class Terminal(val command:String,val actualId:String,val origins:Int,val receipts:Int,val supersessions:Int)
    internal suspend fun verifyTerminal(h:Harness,ackBeforeConfirm:Boolean):Terminal {
        val call=h.port.calls.single();assertTrue(call.completed)
        assertEquals(editedName,call.name);assertEquals(baseDescription,call.description)
        assertEquals(1,h.builds.get())
        assertEquals(1,h.session.events.count { it.kind=="confirm" })
        assertEquals(1,h.session.events.count { it.kind=="committed" })
        original(h)
        val replacement=db.nextStructuralCausalDao().supersession(call.command)
        if(ackBeforeConfirm) assertNull(replacement) else {
            assertNotNull(replacement)
            assertEquals(h.seed.pending.requestId,replacement!!.predecessorAcceptedRequestId)
            assertEquals(call.command,replacement.originalId)
            assertNull(db.nextRequestDao().transmission(NEXT_OPERATION,call.command))
            assertNull(db.nextRequestDao().acceptance(NEXT_OPERATION,call.command))
        }
        val actual=replacement?.replacementId ?: call.command
        assertNotNull(db.nextRequestDao().origin(NEXT_OPERATION,call.command))
        assertNotNull(db.nextRequestDao().origin(NEXT_OPERATION,actual))
        assertNotNull(db.nextRequestDao().transmission(NEXT_OPERATION,actual))
        assertNotNull(db.nextRequestDao().acceptance(NEXT_OPERATION,actual))
        val expectedOrigins=h.seed.history+if(ackBeforeConfirm) 2 else 3
        assertEquals(expectedOrigins,count("next_request_origins"));assertEquals(h.seed.history+2,count("next_acceptances"))
        assertEquals(if(ackBeforeConfirm) 0 else 1,count("next_structural_supersessions"));assertEquals(0,count("sync_outbox"))
        val row=requireNotNull(db.metricDao().getMetricById(metric.id))
        assertEquals(editedName,row.name);assertEquals(baseDescription,row.description)
        val native=pageNativeState();val current=access()
        db.withTransaction {
            NextRecoveryAuditStore(db).validatedMembers(current)
            NextEvidenceReadSession.read(db) {
                val verifier=NextStructuralCausalStore(db)
                for(id in h.seed.acceptedIds+listOf(h.seed.pending.requestId,actual)) verifier.acceptedOperationProof(id,current)
            }
        }
        for(id in listOf(h.seed.pending.requestId,call.command,actual).distinct())
            assertEquals(NextOperationAcceptance.REPLAYED,sender(h.seed.http).sendAndAcceptOperation(current,id))
        assertEquals("Original full proof and original-ID replay must be read-only",native,pageNativeState())
        return Terminal(call.command,actual,expectedOrigins,h.seed.history+2,if(ackBeforeConfirm) 0 else 1)
    }
    internal suspend fun syncAndVerify(h:Harness,ackBeforeConfirm:Boolean):Terminal {
        h.pixels(G0InteractionSession.Phase.SAVED)
        h.observation.tap("sync");h.pixels(G0InteractionSession.Phase.SYNCED);h.session.awaitSettled()
        assertEquals(0,h.session.state.value.publication!!.value.pending)
        return verifyTerminal(h,ackBeforeConfirm)
    }
    private fun textNode(text:String):AccessibilityNodeInfo? {
        fun find(node:AccessibilityNodeInfo):AccessibilityNodeInfo? {
            if(node.text?.toString()==text && node.isVisibleToUser && node.isEditable) return node
            for(index in 0 until node.childCount) node.getChild(index)?.let { find(it)?.let { found->return found } }
            return null
        }
        return InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow?.let(::find)
    }
    internal suspend fun awaitText(text:String):AccessibilityNodeInfo=withTimeout(5_000) {
        var found=textNode(text);while(found==null) { delay(8);found=textNode(text) };found
    }
    @After fun closePreparationHarnesses()=runBlocking<Unit> { harnesses.forEach { it.stop() } }
}
