package com.dayforge.data.repository

import com.dayforge.data.history.HistoryBlockCodec
import com.dayforge.data.history.HistoryNodeSession

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import com.dayforge.data.history.HistoryBlockCodec.Bytes
import com.dayforge.data.history.HistoryBlockCodec.Code
import com.dayforge.data.history.HistoryBlockCodec.Rejected
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Unauthenticated bytes from one already-owned SQL transaction. This does not authenticate a
 * root, validate business state, publish a result, or hold a ready/write permission. Its caller
 * must subsequently use the unchanged full-Ref codec/Directory/Session verification.
 *
 * The trusted SQL owner supplies requireTransaction, bound to its exact private transaction
 * identity, connection and generation. inTransaction alone cannot distinguish end→new begin
 * on the same handle. Neither this scope nor that callback may escape into another operation.
 */
internal object G0CountBlockNodeSource {
    data class Budget(val maxQueries: Int = 256, val maxLoadedBytes: Long = 8L * 1024 * 1024)
    data class Stats(val queries: Int, val blobReads: Int, val loadedBytes: Long)
    data class Result<T>(val value: T, val stats: Stats)

    class Source private constructor(
        private val db: SupportSQLiteDatabase,
        private val requireTransaction: () -> Unit,
        private val budget: Budget,
        private val checkCancelled: () -> Unit
    ) : HistoryNodeSession.Lookup {
        private val thread = Thread.currentThread()
        private val active = AtomicBoolean(true)
        private val busy = AtomicBoolean()
        private val firstFailure = AtomicReference<Throwable?>()
        private var queries = 0
        private var blobReads = 0
        private var loadedBytes = 0L

        private fun invalidate(failure: Throwable): Throwable {
            firstFailure.compareAndSet(null, failure)
            return requireNotNull(firstFailure.get())
        }
        private fun fail(code: Code): Nothing = throw invalidate(Rejected(code))
        private fun boundary() {
            firstFailure.get()?.let { throw it }
            if (!active.get() || thread !== Thread.currentThread()) fail(Code.CONTEXT)
            try { requireTransaction() } catch (failure: Throwable) { throw invalidate(failure) }
            if (!db.isOpen || !db.inTransaction()) fail(Code.CONTEXT)
        }
        private fun poll() {
            boundary(); checkCancelled(); boundary()
        }
        fun stats(): Stats {
            boundary()
            if (busy.get()) fail(Code.CONTEXT)
            return Stats(queries, blobReads, loadedBytes)
        }

        /** No node or cross-operation cache. maxBytes and remaining source budget constrain the
         * SQL projection itself, before getBlob. Returned Bytes owns its exact immutable copy. */
        override fun load(digest: String, maxBytes: Int): Bytes? {
            try {
                boundary()
                if (!busy.compareAndSet(false, true)) fail(Code.CONTEXT)
                try {
                    poll()
                    if (digest.length != 64 || digest.any { it !in "0123456789abcdef" }) fail(Code.MALFORMED)
                    if (maxBytes <= 0 || queries >= budget.maxQueries) fail(Code.BUDGET)
                    val allowance = minOf(maxBytes.toLong(), HistoryBlockCodec.MAX_NODE_BYTES.toLong(),
                        budget.maxLoadedBytes - loadedBytes)
                    if (allowance <= 0) fail(Code.BUDGET)
                    queries++
                    val result = db.query(G0CountBlockSql.readNode, arrayOf<Any?>(allowance, digest)).use { cursor ->
                        poll()
                        if (cursor.columnCount != 3) fail(Code.MALFORMED)
                        if (!cursor.moveToFirst()) null else {
                            if (cursor.getType(0) != Cursor.FIELD_TYPE_STRING || cursor.getString(0) != "blob")
                                fail(Code.MALFORMED)
                            if (cursor.getType(1) != Cursor.FIELD_TYPE_INTEGER) fail(Code.MALFORMED)
                            val declared = cursor.getLong(1)
                            if (declared < 0) fail(Code.MALFORMED)
                            if (declared > allowance || cursor.isNull(2)) fail(Code.BUDGET)
                            if (cursor.getType(2) != Cursor.FIELD_TYPE_BLOB) fail(Code.MALFORMED)
                            poll()
                            blobReads++
                            val raw = cursor.getBlob(2)
                            if (raw.size.toLong() != declared) fail(Code.MALFORMED)
                            if (raw.size.toLong() > allowance) fail(Code.BUDGET)
                            val owned = Bytes.copyOf(raw)
                            if (cursor.moveToNext()) fail(Code.MALFORMED)
                            loadedBytes += owned.size.toLong()
                            owned
                        }
                    }
                    poll()
                    return result
                } finally { busy.set(false) }
            } catch (failure: Throwable) { throw invalidate(failure) }
        }
        private fun close() { active.set(false) }

        companion object {
            internal suspend fun <T> scoped(db: SupportSQLiteDatabase, requireTransaction: () -> Unit,
                budget: Budget, checkCancelled: () -> Unit, block: suspend (Source) -> T): Result<T> {
                if (budget.maxQueries !in 1..4096 || budget.maxLoadedBytes !in 0..32L * 1024 * 1024)
                    throw Rejected(Code.BUDGET)
                val source = Source(db, requireTransaction, budget, checkCancelled)
                return try {
                    source.poll()
                    val value = block(source)
                    source.poll()
                    Result(value, source.stats())
                } catch (failure: Throwable) { throw source.invalidate(failure) }
                finally { source.close() }
            }
        }
    }

    /** Suspension supports pure business decoders; each SQL load and scope return must still
     * run on the captured SQL thread and exact private owner token. The owner, not this helper,
     * begins/ends the transaction and grants later publication. An empty callback is not a permit. */
    suspend fun <T> withSource(db: SupportSQLiteDatabase, requireTransaction: () -> Unit,
        budget: Budget = Budget(), checkCancelled: () -> Unit = {}, block: suspend (Source) -> T): Result<T> =
        Source.scoped(db, requireTransaction, budget, checkCancelled, block)
}
