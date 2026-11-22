package com.dayforge.data.repository

import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.dayforge.data.local.SyncSchemaCallback

/** G0 only: run every original initialization statement, with one optional onOpen transaction.
 * Does not supply a trusted schema, repair changed trigger bodies, or publish a certificate.
 * One instance belongs to one Room owner; any opening failure is terminal for that instance. */
internal class G0SchemaOpeningCallback(
    private val batched: Boolean,
    private val beforeSuccess: () -> Unit = {},
    private val afterEnd: () -> Unit = {}
) : RoomDatabase.Callback() {
    private var failure: Throwable? = null
    var bodies = 0
        private set
    override fun onCreate(db: SupportSQLiteDatabase) = SyncSchemaCallback.onCreate(db)
    override fun onOpen(db: SupportSQLiteDatabase) {
        failure?.let { throw it }
        try {
            timed("schema_callback_total") {
                // The facade never leaves this callback. No SQL text, bindings or data are logged.
                val observed = object : SupportSQLiteDatabase by db {
                    override fun execSQL(sql: String) = timed(
                        if (sql.trimStart().startsWith("CREATE TRIGGER")) "schema_callback_trigger" else "schema_callback_control"
                    ) { db.execSQL(sql) }
                }
                if (batched) timed("schema_callback_begin") { db.beginTransactionNonExclusive() }
                var bodyFailure: Throwable? = null
                try {
                    bodies++
                    timed("schema_callback_body") { SyncSchemaCallback.onOpen(observed) }
                    beforeSuccess()
                    if (batched) db.setTransactionSuccessful()
                } catch (error: Throwable) {
                    bodyFailure = error
                    throw error
                } finally {
                    if (batched) try { timed("schema_callback_end") { db.endTransaction() } }
                    catch (cleanup: Throwable) {
                        // Preserve the primary failure, but never treat a failed end as rollback proof.
                        if (bodyFailure != null) bodyFailure.addSuppressed(cleanup) else throw cleanup
                    }
                }
                afterEnd()
            }
        } catch (error: Throwable) { failure = error; throw error }
    }
    private fun <T> timed(name: String, body: () -> T): T {
        val trace=G0PhaseTrace.current.get()
        return if(trace==null) body() else trace.call(name,body)
    }
}
