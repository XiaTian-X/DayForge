package com.dayforge.data.repository

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import com.dayforge.data.history.AuthenticatedHistoryDirectory
import com.dayforge.data.history.HistoryBlockCodec
import com.dayforge.data.history.HistoryBlockPlanner.*
import com.dayforge.data.history.HistoryBlockUpdater
import com.dayforge.data.history.HistoryNodeSession
import com.dayforge.domain.model.isContractUuid
import java.time.LocalDate
import java.time.YearMonth

/** Conditional content/member/absence evidence in dedicated CommandNodes. A supplied root
 * is NOT authenticated here. The primary store must verify its separate MAC/namespace/schema
 * and bind lifetime to actual SQL transaction/connection independently of COUNT fast eligibility.
 * No business or account callback is accepted, and no SQL/root mutation occurs in this reader.
 */
internal class CountCommandDirectory(private val sql: SupportSQLiteDatabase, private val namespaceHash: String,
    val root: HistoryBlockCodec.Ref, private val requirePrimaryLifetime: () -> Unit,
    private val poll: () -> Unit,
    nodeOverlay: Map<String, HistoryBlockCodec.Bytes> = emptyMap()) {
    // At most the original first patch; immutable bytes, not decoded nodes or authority.
    private val nodeOverlay: Map<String, HistoryBlockCodec.Bytes> = if (nodeOverlay.isEmpty()) emptyMap()
        else java.util.Collections.unmodifiableMap(LinkedHashMap(nodeOverlay))
    private val directory = AuthenticatedHistoryDirectory()
    private val loader = AuthenticatedHistoryDirectory.Loader(::load)
    init {
        require(root.range == LeafRange(unit(namespaceHash), null, null))
        if (this.nodeOverlay.isNotEmpty()) {
            val budget = HistoryBlockUpdater.Budget()
            require(this.nodeOverlay.size <= budget.maxNewNodes &&
                this.nodeOverlay.values.sumOf { it.size.toLong() } <= budget.maxNewBytes)
        }
    }

    private fun load(digest: String, limit: Int): ByteArray? {
        requirePrimaryLifetime(); poll(); require(hashValid(digest) && limit in 1..HistoryBlockCodec.MAX_NODE_BYTES)
        nodeOverlay[digest]?.let { bytes ->
            require(bytes.size in 1..limit) { "COMMAND_NODE_TYPE_OR_SIZE" }
            return bytes.toByteArray().also { require(HistoryBlockCodec.hash(it) == digest) { "COMMAND_NODE_CHANGED" } }
        }
        return sql.query("SELECT typeof(body),length(body),CASE WHEN typeof(body)='blob' AND length(body)<=? THEN body ELSE NULL END " +
            "FROM count_command_nodes WHERE digest=?", arrayOf<Any>(limit, digest)).use { c ->
            if (!c.moveToFirst()) return@use null
            require(c.getType(0) == Cursor.FIELD_TYPE_STRING && c.getString(0) == "blob" &&
                c.getType(1) == Cursor.FIELD_TYPE_INTEGER && c.getLong(1) in 1..limit.toLong() &&
                c.getType(2) == Cursor.FIELD_TYPE_BLOB) { "COMMAND_NODE_TYPE_OR_SIZE" }
            c.getBlob(2).also { require(!c.moveToNext()); require(HistoryBlockCodec.hash(it) == digest) { "COMMAND_NODE_CHANGED" } }
        }
    }

    suspend fun <T> read(block: suspend (Reader) -> T): T {
        requirePrimaryLifetime(); poll()
        return HistoryNodeSession.use(this, root, HistoryNodeSession.Lookup { digest, limit ->
            load(digest, limit)?.let(HistoryBlockCodec.Bytes::copyOf)
        }) { session -> block(Reader(session)).also { requirePrimaryLifetime(); poll() } }
    }

    inner class Reader internal constructor(private val session: HistoryNodeSession.Session) {
        private var tree: HistoryNodeSession.Session.Tree? = session.tree(this@CountCommandDirectory, root)
        private fun reusableTree(): HistoryNodeSession.Session.Tree? {
            val stats = session.stats() // Also rejects an escaped Reader after lexical close.
            val budget = HistoryNodeSession.Budget()
            val candidate = tree ?: return null
            val path = root.height + 1
            val bytes = path.toLong() * HistoryBlockCodec.MAX_NODE_BYTES
            if (stats.visits + path > budget.maxVisits || stats.decodedNodes + path > budget.maxDecodedNodes ||
                stats.processedBytes + bytes > budget.maxProcessedBytes || stats.decodedBytes + bytes > budget.maxDecodedBytes) {
                tree = null; return null // Stop optional reuse, never truncate valid command discovery.
            }
            return candidate
        }
        fun member(commandId: String): CountCommandRecords.CatalogMember? {
            requirePrimaryLifetime(); poll(); require(isContractUuid(commandId))
            val budget = AuthenticatedHistoryDirectory.Budget(maxNodes = 32, maxRecords = 1)
            val cached = reusableTree()
            val found = (if (cached == null) directory.readPoint(root, key(commandId), loader, budget, poll)
                else directory.readPoint(root, key(commandId), cached, budget, poll)).records.singleOrNull() ?: return null
            require(found.sourceId == commandId && found.key == key(commandId))
            return CountCommandRecords.CatalogMember.decode(found.payload.toByteArray(), poll).also {
                require(it.commandId == commandId) { "COMMAND_CATALOG_ID_CHANGED" }
            }
        }

        /** Return immutable path-copy blocks only. The private commit owner must independently
         * validate expected rows/effects, persist bytes and root together, then confirm real END.
         * No deletes: full catalog members remain even after pending delivery is consumed.
         */
        fun replace(changes: List<Pair<CountCommandRecords.CatalogMember?, CountCommandRecords.CatalogMember>>): HistoryBlockUpdater.Patch {
            requirePrimaryLifetime(); poll(); require(changes.isNotEmpty())
            val captured = changes.toList()
            require(captured.map { it.second.commandId }.distinct().size == captured.size)
            val edits = captured.map { (old, fresh) ->
                require(old == null || old.commandId == fresh.commandId)
                require(member(fresh.commandId) == old) { "COMMAND_CATALOG_CAS_CHANGED" }
                HistoryBlockUpdater.Edit(key(fresh.commandId), old?.let(::record), record(fresh))
            }
            // The original Loader checks and independent update budgets still run on every
            // node. Optional reuse only avoids decoding matching bytes again; a capacity miss
            // falls back to the original codec without rejecting an otherwise valid update.
            val cached = tree
            return if (cached == null) HistoryBlockUpdater.apply(root, edits, loader, checkCancelled = poll)
                else HistoryBlockUpdater.apply(root, edits, loader, cached, checkCancelled = poll)
        }
    }

    companion object {
        private val date = LocalDate.of(1970, 1, 1)
        fun unit(namespace: String): UnitKey {
            require(hashValid(namespace))
            return UnitKey(Partition(Scope(namespace), "count-command-v1", "catalog", null), YearMonth.of(1970, 1))
        }
        fun empty(namespace: String): AuthenticatedHistoryDirectory.Built = AuthenticatedHistoryDirectory().build(unit(namespace), emptyList())
        private fun key(id: String) = OrderKey(date, id)
        private fun record(value: CountCommandRecords.CatalogMember) = HistoryBlockCodec.Record(key(value.commandId), value.commandId,
            HistoryBlockCodec.Bytes.copyOf(CountCommandRecords.canonical(value.body()).toByteArray(Charsets.UTF_8)))
        private fun hashValid(value: String) = value.length == 64 && value.all { it in "0123456789abcdef" }
    }
}
