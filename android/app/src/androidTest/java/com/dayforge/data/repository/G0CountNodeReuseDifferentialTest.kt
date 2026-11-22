package com.dayforge.data.repository

import com.dayforge.data.history.HistoryBlockCodec
import com.dayforge.data.history.AuthenticatedHistoryDirectory

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.api.NextSyncHttp
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.NextReplicaFenceSql
import com.dayforge.data.local.SyncSchemaCallback
import com.dayforge.data.local.entity.CountDayEntity
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.history.HistoryBlockCodec.Bytes
import com.dayforge.data.history.HistoryBlockCodec.Record
import com.dayforge.data.history.HistoryBlockCodec.Ref
import com.dayforge.data.history.HistoryBlockPlanner.LeafRange
import com.dayforge.data.history.HistoryBlockPlanner.OrderKey
import com.dayforge.data.history.HistoryBlockPlanner.Partition
import com.dayforge.data.history.HistoryBlockPlanner.Scope
import com.dayforge.data.history.HistoryBlockPlanner.UnitKey
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** One real count/send/ACK chain supplies immutable old/new persisted Q snapshots.
 * Full disk scans and independent format reads below are test oracles only. They never grant
 * authority, replace the original Store validator, or enter a measured user path. */
@RunWith(AndroidJUnit4::class)
class G0CountNodeReuseDifferentialTest:G0HouseholdCountFixture() {
    override fun domainOpeningCallback():RoomDatabase.Callback=object:RoomDatabase.Callback() {
        override fun onCreate(db:SupportSQLiteDatabase) {
            SyncSchemaCallback.onCreate(db)
            db.execSQL(G0CountBlockSql.createNodes);db.execSQL(G0CountBlockSql.createRoot)
            NextReplicaFenceSql.install(db)
        }
        override fun onOpen(db:SupportSQLiteDatabase)=SyncSchemaCallback.onOpen(db)
    }
    private suspend fun openIncremental(http:NextSyncHttp):G0HouseholdCountStore {
        val reference=Room.inMemoryDatabaseBuilder(app,HabitDatabase::class.java).addCallback(domainOpeningCallback()).build()
        val schema=try {
            reference.openHelper.writableDatabase
            reference.withTransaction {G0DomainJournal.trustedSchema(reference.openHelper.writableDatabase)}
        } finally {reference.close()}
        freshGateForColdOpen(recurringReads=true,countWrites=true,householdCountWrites=true,countBlockScope=true)
        storage.reopen()
        val expected=currentHousehold.map {requireNotNull(db.habitDao().getHabitById(it.id))}
        return G0HouseholdCountStore(db,tokens,sessions,authority,domainGate,sender(http),
            jointAuthority=true,incrementalBlockScope=true).also {
            it.initialize(expected,schema);it.installIncrementalCountBlocks()
        }
    }
    private fun disk():G0CountIncrementalBlocks.State = SQLiteDatabase.openDatabase(
        app.getDatabasePath("habit_database").path,null,SQLiteDatabase.OPEN_READONLY).use {raw->
        val nodes=linkedMapOf<String,Bytes>()
        raw.rawQuery("SELECT digest,body FROM g0_count_block_nodes ORDER BY digest",null).use {c->
            while(c.moveToNext()) {
                assertEquals(Cursor.FIELD_TYPE_STRING,c.getType(0));assertEquals(Cursor.FIELD_TYPE_BLOB,c.getType(1))
                val digest=c.getString(0);val bytes=Bytes.copyOf(c.getBlob(1))
                assertEquals(digest,HistoryBlockCodec.hash(bytes));assertNull(nodes.put(digest,bytes))
            }
        }
        raw.rawQuery("SELECT slot,binding,height,members,digest,revision FROM g0_count_block_root",null).use {c->
            assertTrue(c.moveToFirst());assertEquals(1L,c.getLong(0));assertTrue(c.getLong(5)>0)
            for(column in listOf(0,2,3,5))assertEquals(Cursor.FIELD_TYPE_INTEGER,c.getType(column))
            for(column in listOf(1,4))assertEquals(Cursor.FIELD_TYPE_STRING,c.getType(column))
            val binding=c.getString(1)
            val unit=UnitKey(Partition(Scope(binding),"count-incremental-index-v1","scope",null),YearMonth.of(1970,1))
            val root=Ref(LeafRange(unit,null,null),c.getInt(2),c.getLong(3),c.getString(4))
            assertFalse(c.moveToNext());assertTrue(root.digest in nodes)
            G0CountIncrementalBlocks.State(binding,root,nodes)
        }
    }
    private val blockDirectory=AuthenticatedHistoryDirectory()
    private fun loader(state:G0CountIncrementalBlocks.State)=AuthenticatedHistoryDirectory.Loader {digest,limit->
        state.blocks[digest]?.also {assertTrue(it.size<=limit)}?.toByteArray()
    }
    private fun DataInputStream.text():String {
        val count=readInt();require(count in 1..128 && count<=available()/2)
        return CharArray(count){readChar()}.concatToString()
    }
    private fun <T> metadata(record:Record,type:Int,body:DataInputStream.()->T):T =
        DataInputStream(ByteArrayInputStream(record.payload.toByteArray())).use {input->
            assertEquals(0x43494d31,input.readInt());assertEquals(1,input.readInt());assertEquals(type,input.readInt())
            input.body().also {assertEquals(0,input.available())}
        }
    /** Parse the fixed U encoding independently, then authenticate only the selected typed record. */
    private fun record(state:G0CountIncrementalBlocks.State,kind:String,uuid:String,date:LocalDate,id:String):Record? {
        val month=YearMonth.from(date)
        val index=blockDirectory.readPoint(state.root,OrderKey(LocalDate.of(1970,1,1),"U|$kind|$uuid|$month"),loader(state)).records.singleOrNull()
            ?: return null
        val root=metadata(index,1) {
            assertEquals(kind,text());assertEquals(uuid,text());assertEquals(month.toString(),text())
            val unit=UnitKey(Partition(Scope(state.binding),"count-incremental-$kind-v1",uuid,null),month)
            Ref(LeafRange(unit,null,null),readInt(),readLong(),text())
        }
        return blockDirectory.readPoint(root,OrderKey(date,id),loader(state)).records.singleOrNull()
    }
    private fun day(state:G0CountIncrementalBlocks.State,uuid:String,date:LocalDate):CountDayEntity? {
        val found=record(state,"D",uuid,date,"D|$uuid|$date") ?: return null
        return metadata(found,4) {
            assertTrue(readLong()>0);assertTrue(readInt()>0)
            val size=readInt();require(size in 1..HistoryBlockCodec.MAX_NODE_BYTES && size<=available())
            G0CountBusinessBlockCodec.day(Bytes.copyOf(ByteArray(size).also(::readFully)))
        }
    }
    private fun delta(before:G0CountIncrementalBlocks.State,after:G0CountIncrementalBlocks.State,
        habit:HabitEntity,date:LocalDate,factUuid:String):G0CountIncrementalBlocks.Delta {
        val oldFact=record(before,"F",habit.uuid,date,factUuid)?.let {G0CountBusinessBlockCodec.fact(it.payload)}
        val newFact=G0CountBusinessBlockCodec.fact(requireNotNull(record(after,"F",habit.uuid,date,factUuid)).payload)
        val oldSource=record(before,"S",habit.uuid,date,factUuid)?.let {G0CountBusinessBlockCodec.source(it.payload)}
        val newSource=G0CountBusinessBlockCodec.source(requireNotNull(record(after,"S",habit.uuid,date,factUuid)).payload)
        return G0CountIncrementalBlocks.Delta(oldFact,newFact,oldSource,newSource,day(before,habit.uuid,date),
            requireNotNull(day(after,habit.uuid,date)),habit)
    }
    private suspend fun sameRead(state:G0CountIncrementalBlocks.State,uuid:String,date:LocalDate,today:LocalDate) {
        val baseline=G0PhaseTrace(driverTiming=false);val reuse=G0PhaseTrace(driverTiming=false)
        val old=baseline.run {G0CountIncrementalBlocks.readDay(state,uuid,date,today,reuseDecodedNodes=false)}
        val current=reuse.run {G0CountIncrementalBlocks.readDay(state,uuid,date,today,reuseDecodedNodes=true)}
        assertEquals(old,current)
        assertTrue(reuse.count("count_block_node_decode")>0)
        assertTrue(reuse.count("count_block_node_decode")<baseline.count("count_block_node_decode"))
        val next=G0PhaseTrace(driverTiming=false)
        val again=next.run {G0CountIncrementalBlocks.readDay(state,uuid,date,today,reuseDecodedNodes=true)}
        assertEquals(current,again)
        assertEquals("A new operation must perform its own byte authentication",reuse.count("count_block_node_decode"),next.count("count_block_node_decode"))
    }
    private suspend fun sameAdvance(before:G0CountIncrementalBlocks.State,actual:G0CountIncrementalBlocks.State,
        delta:G0CountIncrementalBlocks.Delta) {
        val originalBytes=before.blocks.toMap();val originalRoot=before.root
        val baseline=G0PhaseTrace(driverTiming=false);val reuse=G0PhaseTrace(driverTiming=false)
        val old=baseline.run {G0CountIncrementalBlocks.advance(before,delta,reuseDecodedNodes=false)}
        val current=reuse.run {G0CountIncrementalBlocks.advance(before,delta,reuseDecodedNodes=true)}
        assertEquals(old.state.root,current.state.root);assertEquals(actual.root,current.state.root)
        assertEquals(old.state.blocks,current.state.blocks);assertEquals(actual.blocks,current.state.blocks)
        assertEquals(old.newBlocks,current.newBlocks)
        assertEquals(actual.blocks.filterKeys {it !in before.blocks},current.newBlocks)
        assertEquals(old.stats,current.stats)
        assertTrue(reuse.count("count_block_node_decode")>0)
        assertTrue(reuse.count("count_block_node_decode")<baseline.count("count_block_node_decode"))
        assertEquals(originalRoot,before.root);assertEquals(originalBytes,before.blocks)
    }
    private suspend fun bothReject(state:G0CountIncrementalBlocks.State,delta:G0CountIncrementalBlocks.Delta) {
        val root=state.root;val bytes=state.blocks.toMap()
        val baseline=runCatching {G0CountIncrementalBlocks.advance(state,delta,reuseDecodedNodes=false)}.exceptionOrNull()
        val reuse=runCatching {G0CountIncrementalBlocks.advance(state,delta,reuseDecodedNodes=true)}.exceptionOrNull()
        assertNotNull(baseline);assertNotNull(reuse)
        assertEquals(requireNotNull(baseline).javaClass,requireNotNull(reuse).javaClass);assertEquals(baseline.message,reuse.message)
        assertEquals(root,state.root);assertEquals(bytes,state.blocks)
    }
    private fun stalePending(value:G0CountBusinessBlockCodec.Source):G0CountBusinessBlockCodec.Source {
        val pending=requireNotNull(value.pending)
        return G0CountBusinessBlockCodec.Source(value.origin,value.originHash,value.transmission,value.transmissionHash,
            value.receipt,pending.copy(attemptCount=pending.attemptCount+1),value.shadow)
    }

    @Test fun realAppendSendAndAckHaveIdenticalFrozenResultsAndRejectIdenticalStaleInputsAcrossBothModes()=runBlocking<Unit> {
        val seed=seedHouseholdAccepted(2);val (http,_)=channel {successReply(it)};val store=openIncremental(http)
        val initial=disk();val first=seed.habits[0]
        sameRead(initial,first.uuid,seed.today,seed.today)
        val saved=store.increase(first.uuid);val appended=disk()
        val habit=householdView(store).cards.getValue(first.uuid).habit
        val appendDelta=delta(initial,appended,habit,seed.today,saved.factUuid)
        sameAdvance(initial,appended,appendDelta);sameRead(appended,first.uuid,seed.today,seed.today)
        val delivery=requireNotNull(store.send(http,saved.requestId));val sent=disk()
        val sendDelta=delta(appended,sent,habit,seed.today,saved.factUuid)
        sameAdvance(appended,sent,sendDelta);sameRead(sent,first.uuid,seed.today,seed.today)
        assertEquals(NextOperationAcceptance.COMMITTED,store.acknowledge(delivery));val accepted=disk()
        val ackDelta=delta(sent,accepted,habit,seed.today,saved.factUuid)
        sameAdvance(sent,accepted,ackDelta);sameRead(accepted,first.uuid,seed.today,seed.today)
        sameRead(accepted,seed.habits[1].uuid,seed.today.minusDays(1),seed.today)
        sameRead(accepted,first.uuid,seed.today.minusMonths(8),seed.today)
        sameRead(accepted,first.uuid,seed.today,seed.today.plusDays(1))

        // A valid-looking wrong createdAt reaches the exact OLD fact comparison;
        // source lifecycle and date/quantity stay unchanged in both sides of this input.
        val priorFact=requireNotNull(sendDelta.oldFact)
        val wrongFact=priorFact.copy(createdAt=priorFact.createdAt+1)
        bothReject(appended,sendDelta.copy(oldFact=wrongFact,newFact=wrongFact))
        bothReject(appended,sendDelta.copy(oldSource=stalePending(requireNotNull(sendDelta.oldSource)),
            newSource=stalePending(sendDelta.newSource)))
        // Reapplying the append candidate against the committed new root must fail its absent-CAS.
        bothReject(appended,appendDelta)
        bothReject(initial,appendDelta.copy(newFact=appendDelta.newFact.copy(habitUuid=seed.habits[1].uuid)))

        assertEquals(1,store.incrementalFullBuilds);assertEquals(1,store.incrementalImageCaptures);assertEquals(3,store.incrementalUpdates)
        assertEquals(3,pageRowCount("completions"));assertEquals(3,pageRowCount("next_acceptances"));assertEquals(0,pageRowCount("sync_outbox"))
        val native=pageNativeState()
        assertEquals(NextOperationAcceptance.REPLAYED,store.acknowledge(delivery));assertEquals(native,pageNativeState())
        assertEquals(accepted.root,disk().root);assertEquals(accepted.blocks,disk().blocks)
        val full=householdView(store);reopenReference();sameHouseholdAsOriginal(full)
        assertEquals(native,pageNativeState())
    }
}
