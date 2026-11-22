package com.dayforge.data.repository

import androidx.room.withTransaction
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.LocalCoreWriteAccess
import com.dayforge.data.local.TokenManager
import com.dayforge.domain.service.AccountSessionCoordinator
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Test-only storage experiment. Synthetic append-only facts, not a replacement domain writer.
 * Measures the block/tree/double-COMMIT floor with real Room and fresh Keystore authorization.
 * Its bounded append contract deliberately rejects every other mutation. Full DayForge domain
 * equivalence and SQL handle encapsulation require separate G0 gates; this probe cannot enable V2.
 */
internal class G0BlockProofProbe(
    private val db: HabitDatabase,
    private val tokens: TokenManager,
    private val sessions: AccountSessionCoordinator,
    private val gate: G0SqlGate? = null,
    private val blockSize: Int = 128
) {
    internal data class Root(val digest: String, val sum: Long, val count: Long)
    internal data class Work(var rows: Long = 0, var nodes: Long = 0, var hashBytes: Long = 0, var reservationNs: Long = 0)
    private var coldConnection: androidx.sqlite.db.SupportSQLiteDatabase? = null
    private val sql get() = coldConnection ?: db.openHelper.writableDatabase
    private var coldCandidate: Root? = null
    private var current: Root? = null
    private lateinit var authority: LocalCoreWriteAccess
    private lateinit var nonce: String
    private var externalVersion = -1L
    private var schemaVersion = -1L
    private var tempSchemaVersion = -1L
    private var journalFrontier = -1L
    private val height = 12 // 4096 bounded leaves; unsupported capacity fails closed.
    private val empty = mutableMapOf<Int, Root>()
    var work = Work()
        private set

    private suspend fun <T> protectedSession(block: suspend () -> T): T = sessions.exclusive {
        val entered = android.os.SystemClock.elapsedRealtimeNanos()
        try { block() } finally { work.reservationNs = android.os.SystemClock.elapsedRealtimeNanos() - entered }
    }
    private suspend fun <T> transaction(phase: G0SqlGate.Phase = G0SqlGate.Phase.READ, block: suspend () -> T): T =
        if (gate == null) db.withTransaction { block() } else gate.transaction(db, phase, block)
    private suspend fun <T> finalRead(block: suspend () -> T): T = if (gate == null) block() else gate.finalRead(block)

    private fun scalar(query: String, args: Array<out Any> = emptyArray()): Long = sql.query(query, args).use {
        check(it.moveToFirst()); it.getLong(0)
    }
    private fun text(query: String): String = sql.query(query).use { check(it.moveToFirst()); it.getString(0) }
    private fun hash(write: DataOutputStream.() -> Unit): String {
        val bytes = ByteArrayOutputStream().also { out -> DataOutputStream(out).use { it.write() } }.toByteArray()
        work.hashBytes += bytes.size
        return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
    private fun parent(level: Int, left: Root, right: Root): Root = Root(hash {
        writeUTF("dayforge-g0-node-v1"); writeInt(level)
        for (child in listOf(left, right)) { writeUTF(child.digest); writeLong(child.sum); writeLong(child.count) }
    }, Math.addExact(left.sum, right.sum), Math.addExact(left.count, right.count))
    private fun empty(level: Int): Root = empty.getOrPut(level) {
        if (level == 0) Root(hash { writeUTF("dayforge-g0-empty-v1") }, 0, 0)
        else parent(level, empty(level - 1), empty(level - 1))
    }
    private fun node(level: Int, position: Long): Root {
        work.nodes++
        return sql.query("SELECT digest,total,entries FROM g0_nodes WHERE level=? AND position=?",
            arrayOf<Any>(level, position)).use {
            if (!it.moveToFirst()) empty(level) else {
                check(it.getType(0) == 3 && it.getType(1) == 1 && it.getType(2) == 1)
                Root(it.getString(0), it.getLong(1), it.getLong(2)).also { row ->
                    check(row.count > 0 && row.sum == row.count && row.digest.matches(Regex("[0-9a-f]{64}")))
                }
            }
        }
    }
    private fun save(level: Int, position: Long, root: Root): String {
        val update = sql.compileStatement("UPDATE g0_nodes SET digest=?,total=?,entries=? WHERE level=? AND position=?")
        val count = try {
            update.bindString(1, root.digest); update.bindLong(2, root.sum); update.bindLong(3, root.count)
            update.bindLong(4, level.toLong()); update.bindLong(5, position); update.executeUpdateDelete()
        } finally { update.close() }
        check(count in 0..1)
        if (count == 1) return "UPDATE"
        sql.execSQL("INSERT INTO g0_nodes VALUES(?,?,?,?,?)", arrayOf<Any>(level, position, root.digest, root.sum, root.count))
        return "INSERT"
    }
    private fun leaf(position: Long): Root {
        val first = position * blockSize + 1
        val rows = sql.query("SELECT seq,owner,value FROM g0_facts WHERE seq>=? AND seq<? ORDER BY seq",
            arrayOf<Any>(first, first + blockSize)).use { c -> buildList {
            while (c.moveToNext()) {
                check(c.getType(0) == 1 && c.getType(1) == 3 && c.getType(2) == 1)
                check(c.getLong(0) == first + size && c.getString(1) == authority.session.authentication.userId && c.getLong(2) == 1L)
                add(c.getLong(0)); work.rows++
            }
        } }
        if (rows.isEmpty()) return empty(0)
        return Root(hash {
            writeUTF("dayforge-g0-block-v1"); writeUTF(authority.session.authentication.userId)
            writeLong(position); writeInt(rows.size)
            rows.forEach { writeLong(it); writeLong(1) }
        }, rows.size.toLong(), rows.size.toLong())
    }
    private fun proveLeaf(position: Long, expected: Root) {
        var at = position
        var computed = leaf(at)
        check(computed == node(0, at)) { "G0_BLOCK_CHANGED" }
        for (level in 0 until height) {
            val sibling = node(level, at xor 1L)
            computed = if (at % 2 == 0L) parent(level + 1, computed, sibling) else parent(level + 1, sibling, computed)
            at /= 2
        }
        check(computed == expected) { "G0_PROOF_CHANGED" }
    }
    private fun updatePath(position: Long, writes: MutableList<String>? = null): Root {
        var at = position
        var computed = leaf(at)
        val first = save(0, at, computed); writes?.add("nodes:$first:0:$at")
        for (level in 0 until height) {
            val sibling = node(level, at xor 1L)
            computed = if (at % 2 == 0L) parent(level + 1, computed, sibling) else parent(level + 1, sibling, computed)
            at /= 2
            val operation = save(level + 1, at, computed); writes?.add("nodes:$operation:${level + 1}:$at")
        }
        return computed
    }
    private suspend fun authenticate() {
        check(tokens.localCoreWriteAccess() == authority) { "G0_AUTHORITY_CHANGED" }
    }
    private fun watermark() = scalar("SELECT coalesce(max(id),0) FROM g0_changes")
    private fun changesSince(frontier: Long): List<String> =
        sql.query("SELECT id,entry FROM g0_changes WHERE id>? ORDER BY id", arrayOf<Any>(frontier)).use { c ->
            buildList {
                while (c.moveToNext()) {
                    check(c.getType(0) == 1 && c.getType(1) == 3 && c.getLong(0) == frontier + size + 1) { "G0_JOURNAL_GAP" }
                    add(c.getString(1))
                }
            }
        }
    private fun environment() {
        check(sql.inTransaction())
        check(text("SELECT nonce FROM temp.g0_proof_nonce") == nonce) { "G0_CONNECTION_CHANGED" }
        check(scalar("PRAGMA main.data_version") == externalVersion) { "G0_EXTERNAL_CHANGE" }
        check(scalar("PRAGMA main.schema_version") == schemaVersion) { "G0_SCHEMA_CHANGED" }
        if (gate == null) check(scalar("PRAGMA temp.schema_version") == tempSchemaVersion) { "G0_TEMP_SCHEMA_CHANGED" }
        else gate.requireSealedConnection() // Only exact, non-business Room TEMP maintenance can change schema.
    }
    private fun boundary(): Root {
        environment()
        check(watermark() == journalFrontier) { "G0_UNCONSUMED_CHANGE" }
        val root = checkNotNull(current) { "G0_UNVERIFIED" }
        check(node(height, 0) == root) { "G0_ROOT_CHANGED" }
        return root
    }

    suspend fun initialize(records: Int) = withContext(Dispatchers.IO) { protectedSession {
        check(records > 0 && records < blockSize * (1 shl height))
        authority = checkNotNull(tokens.localCoreWriteAccess())
        val installation = tokens.getOrCreateInstallationId()
        val root = db.withTransaction {
            sql.execSQL("CREATE TABLE g0_identity(id INTEGER PRIMARY KEY CHECK(id=1),owner TEXT NOT NULL,server TEXT NOT NULL,epoch TEXT NOT NULL,installation TEXT NOT NULL)")
            sql.execSQL("INSERT INTO g0_identity VALUES(1,?,?,?,?)", arrayOf(authority.session.authentication.userId,
                checkNotNull(authority.session.serverInstanceId), checkNotNull(authority.session.syncEpoch), installation))
            sql.execSQL("CREATE TRIGGER g0_identity_no_update BEFORE UPDATE ON g0_identity BEGIN SELECT RAISE(ABORT,'G0_IDENTITY_IMMUTABLE'); END")
            sql.execSQL("CREATE TRIGGER g0_identity_no_delete BEFORE DELETE ON g0_identity BEGIN SELECT RAISE(ABORT,'G0_IDENTITY_IMMUTABLE'); END")
            sql.execSQL("CREATE TABLE g0_facts(seq INTEGER PRIMARY KEY,owner TEXT NOT NULL,value INTEGER NOT NULL)")
            sql.execSQL("CREATE TABLE g0_nodes(level INTEGER NOT NULL,position INTEGER NOT NULL,digest TEXT NOT NULL,total INTEGER NOT NULL,entries INTEGER NOT NULL,PRIMARY KEY(level,position))")
            sql.execSQL("CREATE TABLE g0_intents(command TEXT PRIMARY KEY,owner TEXT NOT NULL)")
            sql.execSQL("CREATE TABLE g0_results(command TEXT PRIMARY KEY,seq INTEGER NOT NULL UNIQUE,FOREIGN KEY(command) REFERENCES g0_intents(command))")
            sql.execSQL("CREATE TABLE g0_outbox(command TEXT PRIMARY KEY,seq INTEGER NOT NULL UNIQUE,FOREIGN KEY(seq) REFERENCES g0_facts(seq))")
            sql.execSQL("CREATE TABLE g0_changes(id INTEGER PRIMARY KEY AUTOINCREMENT,entry TEXT NOT NULL)")
            sql.execSQL("CREATE INDEX g0_change_entry ON g0_changes(entry,id)")
            sql.execSQL("CREATE TRIGGER g0_no_update BEFORE UPDATE ON g0_facts BEGIN SELECT RAISE(ABORT,'G0_IMMUTABLE'); END")
            sql.execSQL("CREATE TRIGGER g0_no_delete BEFORE DELETE ON g0_facts BEGIN SELECT RAISE(ABORT,'G0_IMMUTABLE'); END")
            for ((table, key) in mapOf("facts" to "seq", "nodes" to "level || ':' || position",
                "intents" to "command", "results" to "command", "outbox" to "command")) {
                for (operation in listOf("INSERT", "UPDATE", "DELETE")) {
                    val prefix = if (operation == "DELETE") "OLD" else "NEW"
                    val qualified = key.split(" || ").joinToString(" || ") { if (it.startsWith("'")) it else "$prefix.$it" }
                    sql.execSQL("CREATE TRIGGER g0_capture_${table}_$operation AFTER $operation ON g0_$table " +
                        "BEGIN INSERT INTO g0_changes(entry) VALUES('$table:$operation:' || $qualified); END")
                }
            }
            sql.execSQL("CREATE TRIGGER g0_changes_no_update BEFORE UPDATE ON g0_changes BEGIN SELECT RAISE(ABORT,'G0_JOURNAL_IMMUTABLE'); END")
            sql.execSQL("CREATE TRIGGER g0_changes_no_delete BEFORE DELETE ON g0_changes BEGIN SELECT RAISE(ABORT,'G0_JOURNAL_IMMUTABLE'); END")
            nonce = UUID.randomUUID().toString()
            sql.execSQL("CREATE TEMP TABLE g0_proof_nonce(nonce TEXT NOT NULL)")
            sql.execSQL("INSERT INTO temp.g0_proof_nonce VALUES(?)", arrayOf<Any>(nonce))
            val insert = sql.compileStatement("INSERT INTO g0_facts VALUES(?,?,1)")
            try { repeat(records) { n ->
                insert.clearBindings(); insert.bindLong(1, n + 1L)
                insert.bindString(2, authority.session.authentication.userId); insert.executeInsert()
            } } finally { insert.close() }
            for (block in 0..(records - 1) / blockSize) updatePath(block.toLong())
            // Freeze the COMPLETE callback schema before seal. onOpen installs this original
            // fence for every table, including the isolated model; it must not add late objects.
            com.dayforge.data.local.NextReplicaFenceSql.install(sql)
            schemaVersion = scalar("PRAGMA main.schema_version")
            externalVersion = scalar("PRAGMA main.data_version")
            tempSchemaVersion = scalar("PRAGMA temp.schema_version")
            journalFrontier = watermark()
            authenticate()
            val verified = node(height, 0)
            // Seal while the writer reservation is still held, before COMMIT and publication.
            gate?.seal()
            verified
        }
        current = root
    } }

    suspend fun read(): Root = withContext(Dispatchers.IO) { protectedSession {
        work = Work()
        authenticate()
        transaction {
            val root = boundary()
            // Exact aggregate is committed in the root; no materialization of all facts.
            authenticate(); check(boundary() == root); root
        }
    } }

    suspend fun verifyBlock(): Root = withContext(Dispatchers.IO) { protectedSession {
        work = Work()
        authenticate()
        transaction {
            val root = boundary()
            proveLeaf((root.count - 1) / blockSize, root)
            authenticate(); check(boundary() == root); root
        }
    } }

    suspend fun append(command: String, failAfterFacts: Boolean = false, beforeFinal: (() -> Unit)? = null,
        afterCommit: (() -> Unit)? = null): Root = withContext(Dispatchers.IO) { protectedSession {
        work = Work()
        authenticate()
        check(authority.capabilities == null || "facts.append" in authority.capabilities!!) { "G0_WRITE_DENIED" }
        // Intent and business are separately durable, both inside measured end-to-end time.
        val intentFrontier = transaction(G0SqlGate.Phase.INTENT) {
            boundary()
            val exists = scalar("SELECT count(*) FROM g0_intents WHERE command=?", arrayOf<Any>(command)) == 1L
            sql.execSQL("INSERT OR IGNORE INTO g0_intents VALUES(?,?)", arrayOf<Any>(command, authority.session.authentication.userId))
            check(sql.query("SELECT owner FROM g0_intents WHERE command=?", arrayOf<Any>(command)).use {
                it.moveToFirst() && it.getString(0) == authority.session.authentication.userId
            })
            finalRead {
                authenticate(); environment()
                check(changesSince(journalFrontier) == if (exists) emptyList<String>() else listOf("intents:INSERT:$command")) { "G0_UNEXPECTED_INTENT_WRITE" }
                watermark()
            }
        }
        journalFrontier = intentFrontier
        val committed = transaction(G0SqlGate.Phase.BUSINESS) {
            val before = boundary()
            if (scalar("SELECT count(*) FROM g0_results WHERE command=?", arrayOf<Any>(command)) == 1L) {
                authenticate(); check(boundary() == before); return@transaction before to journalFrontier
            }
            val sequence = Math.addExact(before.count, 1L)
            check(sequence <= blockSize * (1 shl height)) { "G0_CAPACITY_UNSUPPORTED" }
            val block = (sequence - 1) / blockSize
            proveLeaf(block, before)
            val expectedWrites = mutableListOf("facts:INSERT:$sequence", "outbox:INSERT:$command", "results:INSERT:$command")
            sql.execSQL("INSERT INTO g0_facts VALUES(?,?,1)", arrayOf<Any>(sequence, authority.session.authentication.userId))
            if (failAfterFacts) error("G0_INJECTED_FAILURE")
            sql.execSQL("INSERT INTO g0_outbox VALUES(?,?)", arrayOf<Any>(command, sequence))
            sql.execSQL("INSERT INTO g0_results VALUES(?,?)", arrayOf<Any>(command, sequence))
            val candidate = updatePath(block, expectedWrites)
            beforeFinal?.invoke()
            finalRead {
                check(candidate.count == sequence && candidate.sum == sequence)
                proveLeaf(block, candidate)
                check(scalar("SELECT count(*) FROM g0_outbox WHERE command=? AND seq=?", arrayOf<Any>(command, sequence)) == 1L)
                authenticate(); environment()
                check(changesSince(journalFrontier) == expectedWrites) { "G0_UNEXPECTED_WRITE" }
                check(node(height, 0) == candidate)
                candidate to watermark()
            }
        }
        // Only the actual outer COMMIT promotes the trusted in-memory root.
        current = committed.first
        journalFrontier = committed.second
        afterCommit?.invoke()
        committed.first
    } }

    suspend fun fullAudit(): Root = withContext(Dispatchers.IO) { protectedSession {
        work = Work(); authenticate()
        transaction {
            val expected = boundary()
            auditRawTree(expected)
            authenticate(); check(boundary() == expected); expected
        }
    } }

    private fun auditRawTree(expected: Root, checkActive: () -> Unit = {}, onBlock: (Long) -> Unit = {}) {
        val levels = mutableMapOf<Long, Root>()
        for (block in 0..(expected.count - 1) / blockSize) {
            checkActive()
            val computed = leaf(block)
            check(computed == node(0, block)); levels[block] = computed
            onBlock(work.rows)
            checkActive()
        }
        var currentLevel: Map<Long, Root> = levels
        var expectedNodes = levels.size.toLong()
        repeat(height) { level ->
            currentLevel = currentLevel.keys.map { it / 2 }.distinct().associateWith { position ->
                checkActive()
                parent(level + 1, currentLevel[position * 2] ?: empty(level),
                    currentLevel[position * 2 + 1] ?: empty(level)).also {
                    check(node(level + 1, position) == it) { "G0_DIRECTORY_CHANGED" }
                }
            }
            expectedNodes += currentLevel.size
        }
        check(currentLevel.getValue(0) == expected)
        check(scalar("SELECT count(*) FROM g0_nodes") == expectedNodes) { "G0_DIRECTORY_EXTRA_NODE" }
        check(scalar("SELECT count(*) FROM g0_facts") == expected.count)
    }

    /** Runs only inside the cold owner's continuous reservation, before actual COMMIT.
     * The root read from disk is an untrusted comparison target: all facts, saved tree nodes,
     * journal entries and both directions of command/source membership are reconstructed.
     */
    suspend fun prepareColdRecovery(connection: androidx.sqlite.db.SupportSQLiteDatabase, onBlock: (Long) -> Unit = {},
        beforeFinal: () -> Unit = {}) {
        check(current == null && coldCandidate == null && connection.inTransaction())
        coldConnection = connection
        try {
            val context = currentCoroutineContext()
            context.ensureActive()
            authority = checkNotNull(tokens.localCoreWriteAccess())
            check(scalar("SELECT count(*) FROM next_replica_fence WHERE id IS NOT 1 OR mode IS NOT 'closed'") == 0L) {
                "G0_COLD_RECOVERY_FENCED"
            }
            val installation = checkNotNull(tokens.registeredInstallationId())
            sql.query("SELECT id,owner,server,epoch,installation FROM g0_identity").use { c ->
                check(c.moveToFirst() && c.getType(0) == 1 && c.getLong(0) == 1L) { "G0_COLD_IDENTITY" }
                val identity = listOf(authority.session.authentication.userId, authority.session.serverInstanceId,
                    authority.session.syncEpoch, installation)
                identity.forEachIndexed { index, value ->
                    check(c.getType(index + 1) == 3 && c.getString(index + 1) == value) { "G0_COLD_IDENTITY" }
                }
                check(!c.moveToNext()) { "G0_COLD_IDENTITY" }
            }
            val expected = node(height, 0)
            check(expected.count in 1..(blockSize * (1L shl height)))
            auditRawTree(expected, { context.ensureActive() }, onBlock)
            auditColdCommands(expected) { context.ensureActive() }
            nonce = UUID.randomUUID().toString()
            sql.execSQL("CREATE TEMP TABLE g0_proof_nonce(nonce TEXT NOT NULL)")
            sql.execSQL("INSERT INTO temp.g0_proof_nonce VALUES(?)", arrayOf<Any>(nonce))
            schemaVersion = scalar("PRAGMA main.schema_version")
            externalVersion = scalar("PRAGMA main.data_version")
            tempSchemaVersion = scalar("PRAGMA temp.schema_version")
            journalFrontier = watermark()
            beforeFinal()
            authenticate()
            coldCandidate = expected
            gate?.seal()
        } finally { coldConnection = null }
    }

    suspend fun acceptColdRecovery(actualCommitConfirmed: Boolean) {
        check(actualCommitConfirmed && current == null)
        authenticate()
        current = checkNotNull(coldCandidate)
        coldCandidate = null
        try { transaction { boundary(); authenticate() } }
        catch (failure: Throwable) { current = null; gate?.invalidateConnection(); throw failure }
    }

    private fun auditColdCommands(expected: Root, checkActive: () -> Unit) {
        sql.query("SELECT id,entry FROM g0_changes WHERE id>? ORDER BY id", arrayOf<Any>(0L)).use { cursor ->
            var frontier = 0L
            var entry: String? = null
            fun next() {
                checkActive()
                entry = if (!cursor.moveToNext()) null else {
                    check(cursor.getType(0) == 1 && cursor.getType(1) == 3 &&
                        cursor.getLong(0) == frontier + 1) { "G0_COLD_JOURNAL_GAP" }
                    frontier++
                    cursor.getString(1)
                }
            }
            fun consume(expectedEntry: String) {
                check(entry == expectedEntry) { "G0_COLD_JOURNAL_TRANSITION" }
                next()
            }
            fun path(block: Long, previousBlock: Long?) {
                for (level in 0..height) {
                    val position = block shr level
                    val operation = if (previousBlock == null || position != (previousBlock shr level)) "INSERT" else "UPDATE"
                    consume("nodes:$operation:$level:$position")
                }
            }
            next()
            var count = 0L
            while (entry == "facts:INSERT:${count + 1}") { count++; next() }
            check(count in 1..expected.count) { "G0_COLD_SEED" }
            for (block in 0..(count - 1) / blockSize) path(block, if (block == 0L) null else block - 1)
            var intentCount = 0L
            var appliedCount = 0L
            while (entry != null) {
                val value = checkNotNull(entry)
                if (value.startsWith("intents:INSERT:")) {
                    val command = value.removePrefix("intents:INSERT:")
                    sql.query("SELECT owner FROM g0_intents WHERE command=?", arrayOf<Any>(command)).use { c ->
                        check(c.moveToFirst() && c.getType(0) == 3 &&
                            c.getString(0) == authority.session.authentication.userId && !c.moveToNext()) {
                            "G0_COLD_INTENT_MEMBERSHIP"
                        }
                    }
                    intentCount++; next()
                } else {
                    val factFrontier = frontier
                    val previousBlock = (count - 1) / blockSize
                    count++
                    consume("facts:INSERT:$count")
                    check(entry?.startsWith("outbox:INSERT:") == true)
                    val command = checkNotNull(entry).removePrefix("outbox:INSERT:")
                    check(scalar("SELECT count(*) FROM g0_changes WHERE id<? AND entry=?",
                        arrayOf<Any>(factFrontier, "intents:INSERT:$command")) == 1L) { "G0_COLD_COMMAND_CONFLICT" }
                    consume("outbox:INSERT:$command"); consume("results:INSERT:$command")
                    path((count - 1) / blockSize, previousBlock)
                    for (table in listOf("g0_results", "g0_outbox")) {
                        sql.query("SELECT seq FROM $table WHERE command=?", arrayOf<Any>(command)).use { c ->
                            check(c.moveToFirst()) { "G0_COLD_RESULT_MEMBERSHIP" }
                            check(c.getType(0) == 1) { "G0_COLD_RESULT_TYPE" }
                            check(c.getLong(0) == count && !c.moveToNext()) { "G0_COLD_RESULT_MEMBERSHIP" }
                        }
                    }
                    appliedCount++
                }
            }
            check(count == expected.count && frontier == watermark()) { "G0_COLD_EFFECT_COUNT" }
            check(scalar("SELECT count(DISTINCT entry) FROM g0_changes WHERE entry LIKE 'intents:INSERT:%'") == intentCount) {
                "G0_COLD_DUPLICATE_INTENT"
            }
            check(scalar("SELECT count(*) FROM g0_intents") == intentCount) { "G0_COLD_INTENT_MEMBERSHIP" }
            check(scalar("SELECT count(*) FROM g0_results") == appliedCount &&
                scalar("SELECT count(*) FROM g0_outbox") == appliedCount) { "G0_COLD_RESULT_MEMBERSHIP" }
            checkActive()
        }
    }
}
