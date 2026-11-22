package com.dayforge.data.history

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import com.dayforge.data.history.HistoryBlockCodec.Record
import com.dayforge.data.history.HistoryBlockCodec.Ref
import com.dayforge.data.history.HistoryBlockCodec.Encoded
import com.dayforge.data.history.HistoryBlockCodec.Branch
import com.dayforge.data.history.HistoryBlockCodec.Leaf
import com.dayforge.data.history.HistoryBlockCodec.Bytes
import com.dayforge.data.history.HistoryBlockPlanner.*
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.serialization.json.*

/** Authenticated exact-key directory. This proves bytes and membership, not business validity.
 * Values are content-addressed chunks; large source payloads never become oversized tree leaves.
 * A tombstone retains its exact identity so deletion does not require replaying other leaves.
 * The caller owns the approved schema, root authentication, SQL capability and transaction.
 */
internal class CountTrustIndex(private val sql: SupportSQLiteDatabase, val binding: String,
    root: Ref, private val poll: () -> Unit, private val requireLifetime: () -> Unit) {
    constructor(sql: SupportSQLiteDatabase, binding: String, root: Ref, poll: () -> Unit) :
        this(sql, binding, root, poll, {})
    var root: Ref = root; private set
    private val nodes = HashMap<String, ByteArray>()
    var loadedNodes = 0; private set
    var loadedBytes = 0L; private set
    var writtenNodes = 0; private set
    private val directory = AuthenticatedHistoryDirectory()
    private val loader = AuthenticatedHistoryDirectory.Loader { digest, limit -> load(digest, limit) }
    private class ProofScope(val root: Ref, val session: HistoryNodeSession.Session,
        var tree: HistoryNodeSession.Session.Tree?, val budget: HistoryNodeSession.Budget)
    private var readScope: ProofScope? = null
    private var operationScope: ProofScope? = null
    private var borrowedOperationScope = false

    init { require(root.range == LeafRange(unit(binding), null, null)) }

    /** One lexical kernel read/capture, not a permanent Context or permission cache. The
     * requireLifetime binds logical lookups to the actual transaction/owner; poll remains the
     * lightweight cancellation check used inside codec byte loops.
     * The exact authenticated root never changes in a session; putAll disables its reuse first.
     */
    suspend fun <T> withOperationReadScope(block: suspend () -> T): T {
        requireLifetime()
        check(operationScope == null && readScope == null && !borrowedOperationScope)
        return scoped(HistoryNodeSession.Budget(), true, block)
    }

    /** Existing local scopes retain their budgets and reject recursive entry. Within one
     * complete kernel operation, a matching default scope borrows that exact root's proof.
     * A caller requesting a distinct budget retains an independent, lexically closed session.
     */
    suspend fun <T> withReadScope(budget: HistoryNodeSession.Budget = HistoryNodeSession.Budget(), block: suspend () -> T): T {
        requireLifetime()
        check(readScope == null && !borrowedOperationScope)
        val operation = operationScope
        if (operation != null && operation.root == root && operation.budget == budget) {
            poll(); borrowedOperationScope = true
            try { return block().also { poll() } } finally { borrowedOperationScope = false }
        }
        return scoped(budget, false, block)
    }

    private suspend fun <T> scoped(budget: HistoryNodeSession.Budget, operation: Boolean, block: suspend () -> T): T {
        val initial = root
        return HistoryNodeSession.use(this, initial, HistoryNodeSession.Lookup { digest, limit ->
            load(digest, limit)?.let(Bytes::copyOf)
        }, budget) { session ->
            val scope = ProofScope(initial, session, session.tree(this, initial), budget)
            if (operation) operationScope = scope else readScope = scope
            try { block().also { poll() } }
            finally { if (operation) operationScope = null else readScope = null }
        }
    }

    private fun reusablePointTree(): HistoryNodeSession.Session.Tree? {
        val scope = readScope ?: operationScope ?: return null
        val tree = scope.tree ?: return null
        if (scope.root != root) { scope.tree = null; return null }
        val stats = scope.session.stats()
        val budget = scope.budget
        val path = root.height + 1
        val bytes = path.toLong() * HistoryBlockCodec.MAX_NODE_BYTES
        if (stats.visits + path > budget.maxVisits || stats.decodedNodes + path > budget.maxDecodedNodes ||
            stats.processedBytes + bytes > budget.maxProcessedBytes || stats.decodedBytes + bytes > budget.maxDecodedBytes) {
            scope.tree = null
            return null
        }
        return tree
    }

    fun get(name: String): JsonElement? {
        requireLifetime(); poll()
        val id = key(name)
        val tree = reusablePointTree()
        val budget = AuthenticatedHistoryDirectory.Budget(maxNodes = 32, maxRecords = 1)
        val found = (if (tree == null) directory.readPoint(root, order(id), loader, budget, poll)
            else directory.readPoint(root, order(id), tree, budget, poll)).records.singleOrNull()
            ?: return null
        return value(found, name)
    }

    /** Known-version retirement only, never a normal read. Stream every reachable node and value
     * before replacing an authenticated older format; a bad old cache is not a version miss.
     * Depth/leaf/value limits remain local, with no account-size truncation or retained full image.
     */
    fun verifyContents() {
        requireLifetime()
        val pending = ArrayDeque<Ref>()
        pending.addLast(root)
        var records = 0L
        while (pending.isNotEmpty()) {
            poll()
            val expected = pending.removeLast()
            val bytes = requireNotNull(load(expected.digest, HistoryBlockCodec.MAX_NODE_BYTES)) { "COUNT_TRUST_VALUE_MISSING" }
            when (val node = HistoryBlockCodec.decode(expected, bytes, poll)) {
                is Branch -> node.children.asReversed().forEach(pending::addLast)
                is Leaf -> node.records.forEach { record -> value(record); records = Math.addExact(records, 1L) }
            }
        }
        require(records == root.count)
    }

    private fun value(found: Record, expectedName: String? = null): JsonElement? {
        val pointer = CountTrustValueCodec.pointer(found, expectedName)
        val bytes = ByteArrayOutputStream(pointer.length)
        pointer.chunks.forEachIndexed { index, digest ->
            val chunk = requireNotNull(load(digest, CHUNK_BYTES)) { "COUNT_TRUST_VALUE_MISSING" }
            require(hash(chunk) == digest)
            require(chunk.size == pointer.chunkLength(index))
            bytes.write(chunk)
        }
        return CountTrustValueCodec.value(bytes.toByteArray())
    }

    /** Called only in the kernel's maintenance phase, after exact business effects were checked. */
    suspend fun putAll(values: Map<String, JsonElement?>) {
        requireLifetime()
        // The read scope's tree belongs to the old root. Each update batch gets its own scope,
        // closed before installing its new root; later reads cannot reuse the old decoded tree.
        readScope?.tree = null
        operationScope?.tree = null
        values.entries.chunked(32).forEach { batch ->
            poll()
            val replacements = batch.map { (name, value) -> record(name, value ?: JsonNull, ::store) }
            fun patch(tree: HistoryNodeSession.Session.Tree?): HistoryBlockUpdater.Patch {
                val edits = replacements.map { replacement ->
                    val budget = AuthenticatedHistoryDirectory.Budget(maxNodes = 32, maxRecords = 1)
                    val previous = (if (tree == null) directory.readPoint(root, replacement.key, loader, budget, poll)
                        else directory.readPoint(root, replacement.key, tree, budget, poll)).records.singleOrNull()
                    HistoryBlockUpdater.Edit(replacement.key, previous, replacement)
                }
                return if (tree == null) HistoryBlockUpdater.apply(root, edits, loader, checkCancelled = poll)
                    else HistoryBlockUpdater.apply(root, edits, tree, checkCancelled = poll)
            }
            val patch = try { HistoryNodeSession.use(this, root, HistoryNodeSession.Lookup { digest, limit ->
                load(digest, limit)?.let(Bytes::copyOf)
            }) { session -> patch(session.tree(this, root)) } }
            catch (error: HistoryBlockCodec.Rejected) {
                if (error.code != HistoryBlockCodec.Code.BUDGET) throw error
                // No patch/root has been installed. Re-run the SAME pure proof with its original
                // budgets, never retry corruption, context violations or cancellation as misses.
                patch(null)
            }
            patch.newBlocks.forEach { (digest, bytes) -> store(digest, bytes.toByteArray()) }
            root = patch.root
        }
    }

    private fun load(digest: String, limit: Int): ByteArray? {
        requireLifetime(); poll(); require(validHash(digest))
        nodes[digest]?.let { require(it.size <= limit); return it.copyOf() }
        val body = sql.query("SELECT typeof(body),length(body),body FROM count_trust_nodes WHERE digest=?",
            arrayOf(digest)).use { c ->
            if (!c.moveToFirst()) return@use null
            require(c.getType(0) == Cursor.FIELD_TYPE_STRING && c.getString(0) == "blob" &&
                c.getType(1) == Cursor.FIELD_TYPE_INTEGER && c.getLong(1) in 1..limit.toLong())
            c.getBlob(2).also { require(!c.moveToNext()) }
        } ?: return null
        require(hash(body) == digest) { "COUNT_TRUST_NODE_CHANGED" }
        loadedNodes++; loadedBytes += body.size
        // Operation-local only; bounded retention is not a product/history limit.
        if (nodes.size < 256 && nodes.values.sumOf { it.size.toLong() } + body.size <= 8L * 1024 * 1024)
            nodes[digest] = body.copyOf()
        return body
    }

    private fun store(digest: String, body: ByteArray) {
        poll(); require(validHash(digest) && hash(body) == digest && body.size <= HistoryBlockCodec.MAX_NODE_BYTES)
        val existing = load(digest, HistoryBlockCodec.MAX_NODE_BYTES)
        if (existing != null) { require(existing.contentEquals(body)); return }
        sql.execSQL("INSERT INTO count_trust_nodes(digest,body) VALUES(?,?)", arrayOf(digest, body))
        writtenNodes++
    }

    companion object {
        const val CHUNK_BYTES = 128 * 1024
        const val MAX_VALUE_BYTES = 16 * 1024 * 1024
        private val fixedDate = LocalDate.of(1970, 1, 1)
        fun unit(binding: String) = UnitKey(Partition(Scope(binding), "count-trust-v1", "directory", null), YearMonth.of(1970, 1))
        fun ref(binding: String, height: Int, count: Long, digest: String) =
            Ref(LeafRange(unit(binding), null, null), height, count, digest)
        fun validHash(value: String) = value.length == 64 && value.all { it in "0123456789abcdef" }
        fun hash(bytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            val alphabet = "0123456789abcdef"
            return CharArray(digest.size * 2).also { text -> digest.forEachIndexed { index, byte ->
                val value = byte.toInt() and 255
                text[index * 2] = alphabet[value ushr 4]; text[index * 2 + 1] = alphabet[value and 15]
            } }.concatToString()
        }
        private fun key(name: String) = hash(name.toByteArray(Charsets.UTF_8))
        private fun order(id: String) = OrderKey(fixedDate, id)

        private fun record(name: String, value: JsonElement, store: (String, ByteArray) -> Unit): Record {
            val bytes = value.toString().toByteArray(Charsets.UTF_8)
            require(bytes.size in 1..MAX_VALUE_BYTES) { "COUNT_TRUST_VALUE_BUDGET" }
            val chunks = mutableListOf<String>()
            var offset = 0
            while (offset < bytes.size) {
                val chunk = bytes.copyOfRange(offset, minOf(offset + CHUNK_BYTES, bytes.size))
                val digest = hash(chunk); store(digest, chunk); chunks += digest; offset += chunk.size
            }
            val pointer = buildJsonObject {
                put("key", name); put("length", bytes.size)
                put("chunks", JsonArray(chunks.map(::JsonPrimitive)))
            }
            val id = key(name)
            return Record(order(id), id, Bytes.copyOf(pointer.toString().toByteArray(Charsets.UTF_8)))
        }

        /** Initial issuance only. No fixed G0 account/member cap; leaves and branches stay bounded. */
        fun build(sql: SupportSQLiteDatabase, binding: String, values: Map<String, JsonElement>, poll: () -> Unit): Ref {
            val records = values.map { (name, value) ->
                poll()
                record(name, value) { digest, body ->
                    sql.execSQL("INSERT OR IGNORE INTO count_trust_nodes(digest,body) VALUES(?,?)", arrayOf(digest, body))
                    sql.query("SELECT typeof(body),length(body),body FROM count_trust_nodes WHERE digest=?", arrayOf(digest)).use {
                        require(it.moveToFirst() && it.getString(0) == "blob" && it.getLong(1) == body.size.toLong() &&
                            it.getBlob(2).contentEquals(body) && !it.moveToNext())
                    }
                }
            }.sortedBy { it.key }
            require(records.map { it.key }.distinct().size == records.size)
            fun retain(encoded: Encoded): Ref {
                poll()
                val bytes = encoded.bytes.toByteArray()
                sql.execSQL("INSERT OR IGNORE INTO count_trust_nodes(digest,body) VALUES(?,?)", arrayOf(encoded.ref.digest, bytes))
                sql.query("SELECT typeof(body),length(body),body FROM count_trust_nodes WHERE digest=?", arrayOf(encoded.ref.digest)).use {
                    require(it.moveToFirst() && it.getString(0) == "blob" && it.getLong(1) == bytes.size.toLong() &&
                        it.getBlob(2).contentEquals(bytes) && !it.moveToNext())
                }
                return encoded.ref
            }
            val batches = records.chunked(64).ifEmpty { listOf(emptyList()) }
            var level = batches.mapIndexed { index, rows ->
                retain(HistoryBlockCodec.encode(Leaf(LeafRange(unit(binding),
                    if (index == 0) null else rows.first().key,
                    batches.getOrNull(index + 1)?.firstOrNull()?.key), rows)))
            }
            while (level.size > 1) level = level.chunked(HistoryBlockCodec.MAX_CHILDREN).map { children ->
                retain(HistoryBlockCodec.encode(Branch(LeafRange(unit(binding), children.first().range.lowerInclusive,
                    children.last().range.upperExclusive), children.first().height + 1, children)))
            }
            return level.single()
        }
    }
}
