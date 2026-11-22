package com.dayforge.data.repository

import androidx.room.withTransaction
import androidx.sqlite.db.*
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.database.Cursor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class G0ColdBootstrapTest : G0SealedDomainFixture() {
    override fun domainOpeningCallback()=G0SchemaOpeningCallback(true)
    @Test fun freshMemoryReferenceExactlyMatchesPrivateFileIncludingTemp()=runBlocking<Unit> {
        for(batch in listOf(false,true)) {
            val file=trustedDomainSchema(batch,inMemory=false)
            val trace=G0PhaseTrace(false)
            val memory=trace.run { trustedDomainSchema(batch,inMemory=true) }
            assertEquals(file,memory);assertTrue(memory.any { it[0]=="temp" })
            assertEquals(1,trace.count("reference_open"));assertEquals(1,trace.count("reference_close"));assertEquals(0,trace.count("reference_delete"))
        }
    }
    @Test fun memoryReferenceDoesNotLearnAlteredMainOrTempSchema()=runBlocking<Unit> {
        for(temp in listOf(false,true)) {
            freshGateForColdOpen();storage.reopen()
            val expected=trustedDomainSchema(true,inMemory=true)
            val sql=db.openHelper.writableDatabase;sql.execSQL("PRAGMA recursive_triggers=ON")
            val name=if(temp) "g0_bad_temp" else "g0_bad_main"
            sql.execSQL("CREATE ${if(temp) "TEMP " else ""}TRIGGER $name AFTER UPDATE ON metrics BEGIN SELECT 1; END")
            val error=rejected { db.withTransaction { domainGate.installJournal(expected) } }
            assertEquals("G0_DOMAIN_SCHEMA_NOT_TRUSTED",error.message)
            sql.execSQL("DROP TRIGGER $name")
        }
    }
    // Real Android SQLite, deliberately constraint-free synthetic main tables. This tests the
    // journal's native-cell encoding for every catalog column; actual Room/security is above
    // and in the unchanged domain boundary, differential and chain tests.
    private fun scratch():SupportSQLiteOpenHelper = FrameworkSQLiteOpenHelperFactory().create(
        SupportSQLiteOpenHelper.Configuration.builder(app).name(null).callback(object:SupportSQLiteOpenHelper.Callback(1) {
            override fun onCreate(db:SupportSQLiteDatabase) {
                val sql=db
                for((table,fields) in G0DomainSqlCatalog.columns) {
                    val columns=fields.mapIndexed { i,name -> "`$name`"+if(table=="sync_outbox" && i==0) " INTEGER PRIMARY KEY AUTOINCREMENT" else "" }
                    sql.execSQL("CREATE TABLE `$table`(${columns.joinToString(",")})")
                }
                sql.execSQL("CREATE TABLE IF NOT EXISTS android_metadata(locale TEXT)")
                sql.execSQL("CREATE TABLE room_master_table(id INTEGER PRIMARY KEY,identity_hash TEXT)")
            }
            override fun onUpgrade(db:SupportSQLiteDatabase,oldVersion:Int,newVersion:Int) = error("No upgrade")
        }).build())
    private fun values(size:Int,second:Boolean):List<Any?> = (0 until size).map { i ->
        if(i==0) if(second) 2L else 1L else when(i%6) {
            0 -> if(second) Long.MAX_VALUE else Long.MIN_VALUE
            1 -> null
            2 -> if(second) 123L else -42L
            3 -> if(second) -0.25 else 1.125
            4 -> if(second) "changed\u0000值" else "quote'\"值"
            else -> if(second) byteArrayOf(3,0,-1) else byteArrayOf(0,-1,2)
        }
    }
    private fun cells(values:List<Any?>)=values.map { value -> when(value) {
        null -> G0DomainJournal.Cell(Cursor.FIELD_TYPE_NULL,null)
        is Long -> G0DomainJournal.Cell(Cursor.FIELD_TYPE_INTEGER,value)
        is Double -> G0DomainJournal.Cell(Cursor.FIELD_TYPE_FLOAT,java.lang.Double.doubleToRawLongBits(value))
        is String -> G0DomainJournal.Cell(Cursor.FIELD_TYPE_STRING,value)
        is ByteArray -> G0DomainJournal.Cell(Cursor.FIELD_TYPE_BLOB,value.toList())
        else -> error("Bad fixture")
    } }
    @Test fun compactAndLegacyCaptureEveryCatalogCellAndEveryOldNewSideExactly()=runBlocking<Unit> {
        var original:List<G0DomainJournal.Change>?=null
        for(compact in listOf(false,true)) scratch().use { helper ->
            val sql=helper.writableDatabase;sql.execSQL("PRAGMA recursive_triggers=ON")
            val expected=G0DomainJournal.trustedSchema(sql);val journal=G0DomainJournal(sql,compact)
            sql.beginTransaction()
            try { journal.install(expected);sql.setTransactionSuccessful() } finally { sql.endTransaction() }
            sql.beginTransaction()
            try {
                val changes=mutableListOf<G0DomainJournal.Change>()
                for((table,fields) in G0DomainSqlCatalog.columns) {
                    val first=values(fields.size,false);val second=values(fields.size,true)
                    sql.execSQL("INSERT INTO `$table` VALUES(${fields.joinToString(",") { "?" }})",first.toTypedArray())
                    changes+=G0DomainJournal.Change(changes.size+1L,table,"INSERT",null,cells(first))
                    sql.execSQL("UPDATE `$table` SET ${fields.joinToString(",") { "`$it`=?" }}",second.toTypedArray())
                    changes+=G0DomainJournal.Change(changes.size+1L,table,"UPDATE",cells(first),cells(second))
                    sql.execSQL("DELETE FROM `$table`")
                    changes+=G0DomainJournal.Change(changes.size+1L,table,"DELETE",cells(second),null)
                }
                val actual=journal.pending();assertEquals(changes,actual)
                assertEquals(G0DomainSqlCatalog.columns.size*3,actual.size)
                if(compact) assertEquals(original,actual) else original=actual
                sql.setTransactionSuccessful()
            } finally { sql.endTransaction() }
        }
    }
    @Test fun headerOnlySourceRewriteIsRejectedInBothLayouts()=runBlocking<Unit> {
        for(compact in listOf(false,true)) scratch().use { helper ->
            val sql=helper.writableDatabase;sql.execSQL("PRAGMA recursive_triggers=ON")
            val journal=G0DomainJournal(sql,compact);val expected=G0DomainJournal.trustedSchema(sql)
            sql.beginTransaction();try { journal.install(expected);sql.setTransactionSuccessful() } finally { sql.endTransaction() }
            sql.beginTransaction();try {
                sql.execSQL("INSERT INTO sync_control VALUES(1,0)")
                assertEquals("sync_control",journal.pending().single().table)
                sql.execSQL("UPDATE temp.g0_domain_events SET tableName='metrics'")
                assertEquals(if(compact) "G0_DOMAIN_PAYLOAD_SOURCE" else "G0_DOMAIN_JOURNAL_INCOMPLETE",rejected { journal.pending() }.message)
            } finally { sql.endTransaction() }
            sql.beginTransaction();try { assertTrue(journal.pending().isEmpty());sql.setTransactionSuccessful() } finally { sql.endTransaction() }
        }
    }
    @Test fun compactPaddingCorruptionIsRejectedAndRollbackLeavesNoEvents()=runBlocking<Unit> {
        scratch().use { helper ->
            val sql=helper.writableDatabase;sql.execSQL("PRAGMA recursive_triggers=ON")
            val journal=G0DomainJournal(sql,true);val expected=G0DomainJournal.trustedSchema(sql)
            sql.beginTransaction();try { journal.install(expected);sql.setTransactionSuccessful() } finally { sql.endTransaction() }
            sql.beginTransaction();try {
                sql.execSQL("INSERT INTO sync_control VALUES(1,0)")
                assertEquals(1,journal.pending().size)
                sql.execSQL("UPDATE temp.g0_domain_cells SET c25=1")
                assertEquals("G0_DOMAIN_PAYLOAD_PADDING",rejected { journal.pending() }.message)
            } finally { sql.endTransaction() }
            sql.beginTransaction();try { assertTrue(journal.pending().isEmpty());sql.setTransactionSuccessful() } finally { sql.endTransaction() }
        }
    }
}
