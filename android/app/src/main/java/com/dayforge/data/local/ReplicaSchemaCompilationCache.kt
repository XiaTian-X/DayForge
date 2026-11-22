package com.dayforge.data.local

import java.util.Collections
import java.util.LinkedHashMap

/** Pure compilation of the exact complete parser input only, never a schema approval.
 * Key strings are compared in full without hashes-as-identity, whitespace/case rewriting,
 * source/version/authority assumptions or stored native handles. Actual sqlite_master and
 * every original index PRAGMA remain fresh at each read. This process's parser is fixed.
 *
 * Capacity is bounded by entries AND an explicit retained charge: UTF16 input/token bytes
 * plus conservative per-string/list/entry charges. This is accounting, not a claim about
 * exact ART heap size. Large valid definitions take the same uncached parser path, never a
 * new rejection. Failed parsing/cancellation before publication cannot install an entry.
 */
internal class ReplicaSchemaCompilationCache(
    private val maxEntries: Int = 512,
    private val maxRetainedCharge: Long = 4L * 1024 * 1024,
    private val maxEntryCharge: Long = 128L * 1024
) {
    init {
        require(maxEntries in 1..512 && maxRetainedCharge in 1..4L * 1024 * 1024 &&
            maxEntryCharge in 1..minOf(maxRetainedCharge, 128L * 1024))
    }
    private data class Key(val type: String, val name: String, val sql: String)
    private data class Entry(val definition: List<List<String>>, val charge: Long)
    internal data class Usage(val entries: Int, val retainedCharge: Long)
    private val monitor = Any()
    private val entries = LinkedHashMap<Key, Entry>(16, 0.75f, true)
    private var retainedCharge = 0L

    internal fun usage(): Usage = synchronized(monitor) { Usage(entries.size, retainedCharge) }

    fun compile(type: String, name: String, sql: String, checkpoint: () -> Unit = {}): List<List<String>> {
        checkpoint()
        val key = Key(type, name, sql)
        val found = synchronized(monitor) { entries[key] }
        if (found != null) {
            checkpoint()
            return found.definition
        }
        // Exactly the old lexer/table normalizer and rejection behavior. Index definitions
        // are lexical evidence only; their native uniqueness/order/collation is NOT cached.
        val tokens = ReplicaSchemaSql.tokens(sql)
        checkpoint()
        val parsed = if (type == "table") ReplicaSchemaSql.table(tokens, name) else listOf(tokens)
        val clauses = ArrayList<List<String>>(parsed.size)
        var charge = 128L + stringCharge(type) + stringCharge(name) + stringCharge(sql)
        for (clause in parsed) {
            checkpoint()
            val owned = ArrayList<String>(clause.size)
            charge = Math.addExact(charge, 32L + 8L * clause.size)
            for (token in clause) {
                owned.add(token)
                charge = Math.addExact(charge, stringCharge(token))
            }
            clauses.add(Collections.unmodifiableList(owned))
        }
        val frozen = Collections.unmodifiableList(clauses)
        checkpoint()
        if (charge > maxEntryCharge || charge > maxRetainedCharge) return frozen
        return synchronized(monitor) {
            // No SQL, user callback or coroutine suspension is performed under this lock.
            // The checkpoint is the original caller's cancellation-only memory check.
            checkpoint()
            entries[key]?.definition ?: run {
                while (entries.size >= maxEntries || retainedCharge + charge > maxRetainedCharge) {
                    val oldest = entries.entries.iterator()
                    check(oldest.hasNext())
                    retainedCharge -= oldest.next().value.charge
                    oldest.remove()
                }
                entries[key] = Entry(frozen, charge)
                retainedCharge = Math.addExact(retainedCharge, charge)
                frozen
            }
        }
    }
    private fun stringCharge(value: String): Long = 40L + 2L * value.length
}
