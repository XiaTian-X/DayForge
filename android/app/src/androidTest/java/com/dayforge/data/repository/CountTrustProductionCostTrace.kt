package com.dayforge.data.repository

import android.os.SystemClock
import androidx.room.RoomDatabase
import java.util.Locale

/** Synchronous production Room QueryCallback observations, not SQLite lock measurements.
 * Private native preflight/journal SQL bypasses this callback and is included only in wall time.
 * No SQL text or bind values leave this recorder. Each test has one measured foreground stage.
 */
internal class CountTrustProductionCostTrace : RoomDatabase.QueryCallback {
    private data class Tx(var depth: Int, val begin: Long, var ended: Long? = null)
    data class Sample(val queries: Long, val writes: Long, val fullProofSentinels: Long,
        val allDetailScans: Long, val scopedLifetimeDetailScans: Long, val beginCount: Long,
        val endCount: Long, val callbackSpanMax: Long, val completionUpperBoundMax: Long,
        val unmatched: Int, val decryptions: Long, val decryptNanos: Long)
    private var active = false
    private var queries = 0L; private var writes = 0L; private var fullProofs = 0L
    private var allDetails = 0L; private var scopedDetails = 0L
    private var begins = 0L; private var ends = 0L; private var spanMax = 0L; private var upperMax = 0L
    private var decryptions = 0L; private var decryptNanos = 0L
    private val transactions = linkedMapOf<Long, Tx>()

    @Synchronized fun begin() {
        check(!active); active = true
        queries = 0; writes = 0; fullProofs = 0; allDetails = 0; scopedDetails = 0
        begins = 0; ends = 0; spanMax = 0; upperMax = 0; decryptions = 0; decryptNanos = 0
        transactions.clear()
    }
    @Synchronized fun end(ended: Long): Sample {
        check(active); active = false
        // Returning from the whole awaited operation is a confirmed upper bound for its END.
        // A query observed on the same native callback thread after END gives a tighter bound.
        for (tx in transactions.values) if (tx.ended != null) upperMax = maxOf(upperMax, ended - tx.begin)
        return Sample(queries, writes, fullProofs, allDetails, scopedDetails, begins, ends,
            spanMax, upperMax, transactions.values.count { it.ended == null }, decryptions, decryptNanos)
    }
    @Synchronized fun decrypted(nanos: Long) {
        if (active) { decryptions++; decryptNanos += nanos }
    }
    @Synchronized override fun onQuery(sqlQuery: String, bindArgs: List<Any?>) {
        if (!active) return
        val now = SystemClock.elapsedRealtimeNanos()
        // Android API 26 uses getId; the newer JDK method is not an Android baseline API.
        @Suppress("DEPRECATION")
        val thread = Thread.currentThread().id
        transactions[thread]?.takeIf { it.ended != null }?.let {
            upperMax = maxOf(upperMax, now - it.begin); transactions.remove(thread)
        }
        val text = sqlQuery.trim().replace('`', ' ').replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)
        if (text.startsWith("begin")) {
            val tx = transactions[thread]
            if (tx == null) { transactions[thread] = Tx(1, now); begins++ } else tx.depth++
        } else if (text == "end transaction" || text == "end transaction;" || text.startsWith("commit")) {
            val tx = transactions[thread]
            if (tx == null) { ends++; transactions[thread] = Tx(0, now) }
            else if (--tx.depth == 0) {
                ends++; tx.ended = now; spanMax = maxOf(spanMax, now - tx.begin)
            }
        } else if (text.startsWith("select") || text.startsWith("pragma") || text.startsWith("with ")) {
            queries++
            if (text.startsWith("select value,habituuid,onetimeaction,recordedlocaldate,") ||
                text.startsWith("select value, habituuid, onetimeaction, recordedlocaldate,")) fullProofs++
            if (text.startsWith("select * from completions ")) {
                if (!text.contains(" where ") || text.contains(" where 1 ")) allDetails++
                else if (text.contains("habitid") && !text.contains("recordedlocaldate >=") &&
                    !text.contains("recordedlocaldate>=") && !text.contains("recordedlocaldate =") &&
                    !text.contains("recordedlocaldate=") && !text.contains("id is")) scopedDetails++
            }
        } else if (text.startsWith("insert") || text.startsWith("update") || text.startsWith("delete")) writes++
    }
}
