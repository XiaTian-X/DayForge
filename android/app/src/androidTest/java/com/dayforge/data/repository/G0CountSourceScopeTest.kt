package com.dayforge.data.repository

import android.content.Context
import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.api.SYNC_REQUEST_LIMIT
import com.dayforge.data.local.entity.NextRequestOriginEntity
import com.dayforge.data.local.entity.SyncEntityStateEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real SQLite expression-index/negative-membership tests. Synthetic source rows exercise
 * only routing and raw equality, never issue a credential or claim a business-valid source.
 * JSON capability is required for this G0 profile; absence fails, never skips a test.
 */
@RunWith(AndroidJUnit4::class)
class G0CountSourceScopeTest {
    private val uuid="00000000-0000-4000-8000-000000000001"
    private val other="00000000-0000-4000-8000-000000000002"
    private val date="2026-10-22"
    private fun payload(owner:String=uuid,day:String=date)="{\"activity_uuid\":\"$owner\",\"local_date\":\"$day\"}"
    private fun envelope(owner:String=uuid,day:String=date)="{\"payload\":${payload(owner,day)}}"
    private fun source(id:String="one",day:String=date,shadow:Boolean=true):G0CountBusinessBlockCodec.Source {
        val origin=NextRequestOriginEntity(NEXT_OPERATION,id,1,5,"account","server","epoch","a".repeat(64),envelope(day=day))
        return G0CountBusinessBlockCodec.Source(origin,"b".repeat(64),shadow=if(shadow)
            SyncEntityStateEntity("activity_event","fact-$id",1,payloadJson=payload(day=day),payloadHash="c".repeat(64),updatedAt=1) else null)
    }

    @Test fun exactOriginAndShadowMembershipMatchesCompleteAuthenticatedSources()=store {db->db.transaction {
        val expected=source();db.put(expected)
        val before=db.image()
        val session=G0CountNativeRows.Session(db.raw)
        session.requireDaySources(uuid,date,listOf(expected))
        session.requireDaySources(uuid,"2026-10-23",emptyList())
        session.freeze().verify(db.raw)
        assertEquals(before,db.image())
    }}

    @Test fun anOrphanOriginOnAnAbsentDayIsNeverHiddenByTheNewRandomRequestIdentity()=store {db->db.transaction {
        val orphan=source(shadow=false);db.put(orphan)
        val before=db.image()
        failure("G0_COUNT_NATIVE_MISMATCH:next_request_origins") {
            G0CountNativeRows.Session(db.raw).requireDaySources(uuid,date,emptyList())
        }
        assertEquals(before,db.image())
    }}

    @Test fun anOrphanShadowOnAnAbsentDayIsNotSilentlyDiscarded()=store {db->db.transaction {
        val orphan=requireNotNull(source().shadow);db.insert("sync_entity_state",G0CountNativeRows.shadowValues(orphan))
        failure("G0_COUNT_NATIVE_MISMATCH:sync_entity_state") {
            G0CountNativeRows.Session(db.raw).requireDaySources(uuid,date,emptyList())
        }
    }}

    @Test fun anExistingDayAlsoRejectsAdditionalOriginOrShadowMembers() {
        for(addOrigin in listOf(true,false))store {db->db.transaction {
            val valid=source();db.put(valid);val extra=source("extra")
            if(addOrigin)db.insert("next_request_origins",G0CountNativeRows.originValues(extra.origin))
            else db.insert("sync_entity_state",G0CountNativeRows.shadowValues(requireNotNull(extra.shadow)))
            failure("G0_COUNT_NATIVE_MISMATCH") {G0CountNativeRows.Session(db.raw).requireDaySources(uuid,date,listOf(valid))}
        }}
    }

    @Test fun validWhitespaceAndPropertyOrderDoNotRequireRewritingImmutablePayloadBytes()=store {db->db.transaction {
        val old=source(shadow=false)
        val formatted=" { \n \"payload\" : { \"local_date\" : \"$date\", \"activity_uuid\" : \"$uuid\" } } "
        val expected=G0CountBusinessBlockCodec.Source(old.origin.copy(intentJson=formatted),old.originHash)
        db.put(expected)
        val before=db.image();G0CountNativeRows.Session(db.raw).requireDaySources(uuid,date,listOf(expected))
        assertEquals(before,db.image())
    }}

    @Test fun escapedKeysValuesAndPayloadAncestorsUseTheIndexedUnsupportedBucket() {
        val examples=listOf(
            "{\"payload\":{\"activity_\\u0075uid\":\"$uuid\",\"local_date\":\"$date\"}}",
            "{\"pay\\u006coad\":${payload()}}",
            "{\"payload\":{\"activity_uuid\":\"\\u0030${uuid.drop(1)}\",\"local_date\":\"$date\"}}",
            "{\"payload\":${payload()},\"unused\":\"escaped\\ntext\"}")
        for(value in examples)store {db->db.transaction {
            Json.parseToJsonElement(value) // It can be valid JSON and still unsupported by G0 routing.
            db.put(source(shadow=false).let {G0CountBusinessBlockCodec.Source(it.origin.copy(intentJson=value),it.originHash)})
            assertEquals(0L,db.flag(G0CountSourceScopeSql.origins))
            failure("G0_COUNT_SOURCE_SCOPE_UNSUPPORTED") {G0CountNativeRows.Session(db.raw).requireDaySources(uuid,date,emptyList())}
        }}
    }

    @Test fun duplicateRoutingLabelsAndDuplicatePayloadParentsCannotChooseDifferentParserValues() {
        val duplicate="{\"payload\":{\"activity_uuid\":\"$other\",\"activity_uuid\":\"$uuid\",\"local_date\":\"$date\"}}"
        assertEquals(uuid,Json.parseToJsonElement(duplicate).jsonObject.getValue("payload").jsonObject.getValue("activity_uuid").jsonPrimitive.content)
        val examples=listOf(duplicate,
            "{\"payload\":{\"activity_uuid\":\"$uuid\",\"local_date\":\"2026-01-01\",\"local_date\":\"$date\"}}",
            "{\"payload\":${payload()},\"payload\":{}}",
            "{\"payload\":{},\"payload\":${payload()}}")
        for(value in examples)store {db->db.transaction {
            db.put(source(shadow=false).let {G0CountBusinessBlockCodec.Source(it.origin.copy(intentJson=value),it.originHash)})
            assertEquals(0L,db.flag(G0CountSourceScopeSql.origins))
            failure("G0_COUNT_SOURCE_SCOPE_UNSUPPORTED") {G0CountNativeRows.Session(db.raw).requireDaySources(uuid,date,emptyList())}
        }}
    }

    @Test fun malformedNullBlobMissingAndWrongTypedPayloadsAreIndexedRejectionsNotJsonExceptions() {
        val examples:List<Any?> = listOf("{",null,envelope().toByteArray(),"[]","{}",
            "{\"payload\":{\"activity_uuid\":null,\"local_date\":\"$date\"}}",
            "{\"payload\":{\"activity_uuid\":\"$uuid\",\"local_date\":22}}")
        for(value in examples)store {db->db.transaction {
            val values=G0CountNativeRows.originValues(source(shadow=false).origin).toMutableList().apply {set(8,value)}
            db.insert("next_request_origins",values) // CASE must tolerate malformed index input.
            assertEquals(0L,db.flag(G0CountSourceScopeSql.origins))
            failure("G0_COUNT_SOURCE_SCOPE_UNSUPPORTED") {G0CountNativeRows.Session(db.raw).requireDaySources(uuid,date,emptyList())}
        }}
    }

    @Test fun nonAsciiNulOversizeAndWrongKindStayVisibleInTheUnsupportedBucket() {
        val examples=listOf(envelope().dropLast(1)+",\"unused\":\"中文\"}",envelope()+"\u0000",
            envelope().dropLast(1)+",\"unused\":\""+"a".repeat(SYNC_REQUEST_LIMIT)+"\"}")
        for(value in examples)store {db->db.transaction {
            db.put(source(shadow=false).let {G0CountBusinessBlockCodec.Source(it.origin.copy(intentJson=value),it.originHash)})
            assertEquals(0L,db.flag(G0CountSourceScopeSql.origins))
            failure("G0_COUNT_SOURCE_SCOPE_UNSUPPORTED") {G0CountNativeRows.Session(db.raw).requireDaySources(uuid,date,emptyList())}
        }}
        store {db->db.transaction {
            db.put(source(shadow=false).let {G0CountBusinessBlockCodec.Source(it.origin.copy(kind="other_kind"),it.originHash)})
            failure("G0_COUNT_SOURCE_SCOPE_UNSUPPORTED") {G0CountNativeRows.Session(db.raw).requireDaySources(uuid,date,emptyList())}
        }}
    }

    @Test fun shadowsUseTheSameEscapeDuplicateAndInvalidProtection() {
        val examples:List<Any?> = listOf("{",null,payload().toByteArray(),
            "{\"activity_uuid\":\"$other\",\"activity_uuid\":\"$uuid\",\"local_date\":\"$date\"}",
            "{\"activity_uuid\":\"$uuid\",\"local_\\u0064ate\":\"$date\"}")
        for(value in examples)store {db->db.transaction {
            val values=G0CountNativeRows.shadowValues(requireNotNull(source().shadow)).toMutableList().apply {set(4,value)}
            db.insert("sync_entity_state",values)
            assertEquals(0L,db.flag(G0CountSourceScopeSql.shadows))
            failure("G0_COUNT_SOURCE_SCOPE_UNSUPPORTED") {G0CountNativeRows.Session(db.raw).requireDaySources(uuid,date,emptyList())}
        }}
    }

    @Test fun witnessesRecheckBothEmptyRangesAndTheGlobalUnindexableBucket() {
        for(unsupported in listOf(false,true))store {db->db.transaction {
            val session=G0CountNativeRows.Session(db.raw);session.requireDaySources(uuid,date,emptyList());val witness=session.freeze()
            val value=source(shadow=false)
            db.put(if(unsupported)G0CountBusinessBlockCodec.Source(value.origin.copy(intentJson="{}"),value.originHash) else value)
            failure {witness.verify(db.raw)}
        }}
    }

    @Test fun pointDayAndUnsupportedQueriesUseExpressionSearchesAcrossUnrelatedHistory()=store {db->db.transaction {
        repeat(800) {i->db.put(source("old-$i",day="2025-01-01",shadow=false))}
        for((query,index) in listOf(G0CountNativeRows.originsDay to G0CountSourceScopeSql.ORIGIN_INDEX,
            G0CountNativeRows.shadowsDay to G0CountSourceScopeSql.SHADOW_INDEX)) {
            val plan=db.plan(query.metadata,arrayOf<Any?>(uuid,date))
            assertTrue(plan,plan.contains("SEARCH") && plan.contains(index.uppercase()));assertFalse(plan,plan.contains("SCAN"))
        }
        for(shape in G0CountSourceScopeSql.shapes) {
            val plan=db.plan(shape.unsupported,emptyArray())
            assertTrue(plan,plan.contains("SEARCH") && plan.contains(shape.index.uppercase()));assertFalse(plan,plan.contains("SCAN"))
        }
        G0CountNativeRows.Session(db.raw).requireDaySources(uuid,date,emptyList())
        assertTrue(db.version.isNotBlank())
    }}

    @Test fun missingExpressionIndexFailsClosedWithoutFallbackScan()=store {db->db.transaction {
        db.raw.execSQL("DROP INDEX ${G0CountSourceScopeSql.ORIGIN_INDEX}")
        failure {G0CountNativeRows.Session(db.raw).requireDaySources(uuid,date,emptyList())}
    }}

    private fun failure(message:String?=null,body:()->Unit):Throwable {
        val error=try {body();null} catch(error:Throwable) {error}
        assertNotNull("expected source-scope rejection",error)
        if(message!=null)assertTrue("expected $message, got $error",requireNotNull(error).message.orEmpty().contains(message))
        return requireNotNull(error)
    }
    private fun store(body:(Store)->Unit) {Store().use(body)}
    private class Store:AutoCloseable {
        private val helper=FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration
            .builder(ApplicationProvider.getApplicationContext<Context>()).name(null)
            .callback(object:SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db:SupportSQLiteDatabase) {
                    for(table in listOf("next_request_origins","sync_entity_state")) {
                        db.execSQL("CREATE TABLE `$table` ("+G0DomainSqlCatalog.columns.getValue(table).joinToString(",") {"`$it`"}+")")
                    }
                    G0CountSourceScopeSql.requireCapability(db)
                    G0CountSourceScopeSql.createIndexes.forEach(db::execSQL)
                }
                override fun onUpgrade(db:SupportSQLiteDatabase,oldVersion:Int,newVersion:Int)=error("no migration")
            }).build())
        val raw=helper.writableDatabase
        val version=G0CountSourceScopeSql.requireCapability(raw)
        fun transaction(body:()->Unit) {raw.beginTransaction();try {body()} finally {raw.endTransaction()}}
        fun insert(table:String,values:List<Any?>) {
            val columns=G0DomainSqlCatalog.columns.getValue(table)
            raw.execSQL("INSERT INTO `$table` ("+columns.joinToString(",") {"`$it`"}+") VALUES ("+values.joinToString(",") {"?"}+")",values.toTypedArray())
        }
        fun put(source:G0CountBusinessBlockCodec.Source) {
            insert("next_request_origins",G0CountNativeRows.originValues(source.origin))
            source.shadow?.let {insert("sync_entity_state",G0CountNativeRows.shadowValues(it))}
        }
        fun flag(shape:G0CountSourceScopeSql.Shape):Long=raw.query("SELECT (${shape.eligible}) FROM `${shape.table}`").use {
            check(it.moveToFirst());it.getLong(0).also {_ ->check(!it.moveToNext())}
        }
        fun plan(sql:String,args:Array<out Any?>):String=raw.query("EXPLAIN QUERY PLAN $sql",args).use {c->
            buildList {while(c.moveToNext())add(c.getString(3))}.joinToString(" ").uppercase()
        }
        fun image():List<String> = listOf("next_request_origins","sync_entity_state").flatMap {table->
            raw.query("SELECT * FROM `$table` ORDER BY rowid").use {c->buildList {
                while(c.moveToNext())add(table+":"+(0 until c.columnCount).joinToString("|") {i->"${c.getType(i)}:"+
                    when(c.getType(i)) {Cursor.FIELD_TYPE_NULL->"null";Cursor.FIELD_TYPE_BLOB->c.getBlob(i).joinToString(",");else->c.getString(i)}})
            }}
        }
        override fun close()=helper.close()
    }
}
