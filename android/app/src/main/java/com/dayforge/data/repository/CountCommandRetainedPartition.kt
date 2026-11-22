package com.dayforge.data.repository

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import com.dayforge.data.history.AuthenticatedHistoryDirectory
import com.dayforge.data.history.HistoryBlockCodec
import com.dayforge.data.history.HistoryBlockPlanner.OrderKey
import com.dayforge.data.local.CountCommandLegacyNative
import com.dayforge.domain.model.isContractUuid
import java.time.LocalDate
import kotlinx.coroutines.CancellationException

/** Complete P = enrolled Outcomes + the immutable original raw stream. No residual row is
 * considered retained until the entire stream equals the old signed count/hash. This fixed,
 * SELECT-only verifier never returns a reference or a cross-transaction permission. */
internal object CountCommandRetainedPartition {
    fun verify(sql: SupportSQLiteDatabase, authenticated: CountCommandSeal.Authenticated,
        lifetime: () -> Unit) {
        val header = authenticated.header
        require(header.mode == CountCommandRootCodec.Mode.ACCOUNT_BOUND_RETAINED)
        val root = requireNotNull(header.catalog)
        val original = authenticated.native()
        val before = CountCommandRootAnchor.read(sql)
        CountCommandRootAnchor.requireMatches(header, original.mac, before.hash)
        require(before.storage == header.storageId)
        fun poll() {
            lifetime()
            if (Thread.currentThread().isInterrupted) throw CancellationException("Command partition interrupted")
        }
        val native = CountCommandNativeRows(sql, ::poll)
        val tree = AuthenticatedHistoryDirectory()
        val loader = AuthenticatedHistoryDirectory.Loader { digest, limit ->
            poll()
            require(limit in 1..HistoryBlockCodec.MAX_NODE_BYTES)
            sql.query("SELECT typeof(body),length(body),CASE WHEN typeof(body)='blob' AND length(body)<=? THEN body END " +
                "FROM count_command_nodes WHERE digest=? LIMIT 2", arrayOf<Any>(limit, digest)).use { c ->
                if (!c.moveToFirst()) null else {
                    require(c.getType(0) == Cursor.FIELD_TYPE_STRING && c.getString(0) == "blob" &&
                        c.getType(1) == Cursor.FIELD_TYPE_INTEGER && c.getLong(1) in 1..limit.toLong() &&
                        c.getType(2) == Cursor.FIELD_TYPE_BLOB) { "COMMAND_RETAINED_NODE_TYPE_OR_SIZE" }
                    c.getBlob(2).also { require(!c.moveToNext() && HistoryBlockCodec.hash(it) == digest) }
                }
            }
        }
        val date = LocalDate.of(1970, 1, 1)
        fun member(record: HistoryBlockCodec.Record): CountCommandRecords.CatalogMember {
            poll()
            require(isContractUuid(record.sourceId) && record.key == OrderKey(date, record.sourceId))
            return CountCommandRecords.CatalogMember.decode(record.payload.toByteArray(), ::poll).also {
                require(it.commandId == record.sourceId)
            }
        }
        fun lookup(id: String): CountCommandRecords.CatalogMember? {
            poll(); require(isContractUuid(id))
            val records = tree.readPoint(root, OrderKey(date, id), loader,
                AuthenticatedHistoryDirectory.Budget(maxNodes = 32, maxRecords = 1), ::poll).records
            return records.singleOrNull()?.let(::member)
        }
        fun aggregate(value: CountCommandRecords.CatalogMember): CountCommandRowPlan.Aggregate =
            native.aggregate(value, ::poll).also {
                require(it.intent.namespace.catalogBinding == header.accountBinding &&
                    CountCommandRootCodec.admits(header, it.intent.format)) { "COMMAND_FORMAT_NOT_ADMITTED" }
            }
        var members = 0L; var outcomes = 0L; var pending = 0L
        fun visit(ref: HistoryBlockCodec.Ref) {
            poll()
            val node = HistoryBlockCodec.decode(ref, requireNotNull(loader.load(ref.digest, HistoryBlockCodec.MAX_NODE_BYTES)), ::poll)
            when (node) {
                is HistoryBlockCodec.Leaf -> for (record in node.records) {
                    val row = aggregate(member(record))
                    members = Math.addExact(members, 1L)
                    if (row.outcome != null) outcomes = Math.addExact(outcomes, 1L)
                    if (row.status.pending) pending = Math.addExact(pending, 1L)
                }
                is HistoryBlockCodec.Branch -> node.children.forEach(::visit)
            }
        }
        // Always decode the actual empty root. A fabricated absent UUID is not an empty proof.
        visit(root)
        require(members == root.count && pending == header.pending.count)
        fun count(table: String): Long = sql.query("SELECT COUNT(*) FROM `$table`").use { c ->
            require(c.moveToFirst() && c.getType(0) == Cursor.FIELD_TYPE_INTEGER)
            c.getLong(0).also { require(it >= 0L && !c.moveToNext()) }
        }
        require(count("count_command_intents") == members && count("count_command_status") == members)
        val identity = CountCommandRecords.hash("pending-root", CountCommandRootCodec.body(header).toString(Charsets.UTF_8))
        val pager = CountCommandPendingRules.Pager(identity, header.pending)
        while (!pager.page(identity, 32, { id -> aggregate(requireNotNull(lookup(id))).status }, ::poll).complete) poll()
        var actualEnrolled = 0L
        val legacy = CountCommandLegacyNative.stream(sql, { rowid, kind, bytes ->
            // Native UTF16 canonical UUIDs occupy 72 bytes. Nontext/large/invalid keys remain
            // uninterpreted stream candidates and are never loaded as unbounded Strings.
            val id = if (kind == "text" && bytes != null && bytes in 1..72L)
                sql.query("SELECT CASE WHEN length(commandId)=36 THEN commandId END FROM count_command_results WHERE rowid=? LIMIT 2",
                    arrayOf(rowid)).use { c ->
                    require(c.moveToFirst())
                    (if (c.getType(0) == Cursor.FIELD_TYPE_STRING) c.getString(0) else null).also { require(!c.moveToNext()) }
                } else null
            val enrolled = id?.takeIf(::isContractUuid)?.let(::lookup)
            if (enrolled == null) false else {
                // A PREPARED member with any physical Outcome is corruption, never old L.
                require(enrolled.outcomeRowHash != null) { "COMMAND_RETAINED_ENROLLED_OUTCOME_CHANGED" }
                aggregate(enrolled)
                actualEnrolled = Math.addExact(actualEnrolled, 1L)
                true
            }
        }, ::poll)
        require(actualEnrolled == outcomes && legacy.first == header.legacyCount && legacy.second == header.legacyHash &&
            count("count_command_results") == Math.addExact(outcomes, legacy.first)) { "COMMAND_RETAINED_PARTITION_CHANGED" }
        val after = native.root()
        require(CountCommandRootCodec.fromEntity(after) == header && after.mac.contentEquals(original.mac) &&
            CountCommandRootAnchor.read(sql) == before) { "COMMAND_RETAINED_ROOT_CHANGED" }
        poll()
    }
}
