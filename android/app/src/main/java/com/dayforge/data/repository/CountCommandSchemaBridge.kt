package com.dayforge.data.repository

import android.database.Cursor
import com.dayforge.data.history.CountTrustIndex
import com.dayforge.data.local.CountCommandPrimarySchema
import com.dayforge.data.local.CountCommandV20Schema
import com.dayforge.data.local.CountTrustRuntime
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.LocalCoreWriteAccess
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/** Mandatory interpretation of an old v19 COUNT envelope after the exact additive v20 edge.
 * This is not qualification or current-account execution authority. A valid old tree remains
 * the same old tree; dirty, scope, validator and environment checks retain their original roles.
 */
internal object CountCommandSchemaBridge {
    class Invalid internal constructor(cause: Exception) : IllegalStateException("COMMAND_OLD_COUNT_SCHEMA_INVALID", cause)
    private data class Control(val storage: String, val generation: Long, val dirty: Long, val anchor: String?)
    private data class Legacy(val old: CountTrustSeal.Header, val currentSchema: String, val control: Control)
    private data class Inspection(val legacy: Legacy? = null, val warm: WarmSchema? = null)

    /** One inspection/COUNT-authentication handoff, never a reusable schema or SQL grant. */
    private class WarmSchema(val database: HabitDatabase, val owner: CountTrustRuntime,
        val token: Any, val job: Job, val epoch: Long, val cookie: Long, val hash: String) {
        var active = true
        var consumed = false
        private suspend fun requireOwner() {
            currentCoroutineContext().ensureActive()
            check(active && currentCoroutineContext()[Job] === job &&
                CountTrustRuntime.forDatabase(database) === owner && owner.generation == epoch && !owner.isRetired) {
                "COUNT_OPEN_SCHEMA_EXPIRED"
            }
            owner.requirePrimaryReadToken(database, token)
        }
        suspend fun verify() {
            requireOwner()
            val current = schemaVersion(database)
            // A final native query may retire this runtime without changing its generation.
            requireOwner()
            check(current == cookie) { "COUNT_OPEN_SCHEMA_CHANGED" }
        }
        suspend fun consume(): String {
            requireOwner()
            check(!consumed) { "COUNT_OPEN_SCHEMA_CONSUMED" }
            verify(); consumed = true
            return hash
        }
    }

    /** The caller may only perform its existing COUNT authentication in this short scope.
     * Mandatory inspection precedes optional retired/dirty exits. The business callback runs
     * after this returns; legacy/public pending/advance keep their original inspections.
     */
    internal suspend fun <T> withOpenSchema(database: HabitDatabase, access: LocalCoreWriteAccess,
        block: suspend (Boolean, suspend () -> String?) -> T): T {
        if (database.openHelper.writableDatabase.version < CountCommandV20Schema.VERSION)
            return block(false) { null }
        var inspected = false
        try {
            val owner = requireNotNull(CountTrustRuntime.forDatabase(database))
            require(database.inTransaction()) { "COMMAND_SCHEMA_TRANSACTION_REQUIRED" }
            return owner.maintenance(database) {
                val result = inspectGuarded(database, access, captureWarm = true)
                inspected = true
                val warm = result.warm
                try {
                    warm?.verify()
                    // Deliberately outside inspectGuarded: COUNT MAC/caller failures retain their identity.
                    block(result.legacy != null) { warm?.consume() }.also {
                        if (warm?.consumed == true) warm.verify()
                    }
                } finally { if (warm != null) warm.active = false }
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (invalid: Invalid) { throw invalid }
        catch (error: Exception) {
            // Establishing the original inspection lease is mandatory too. Optional COUNT
            // callers must not turn an entry failure into a qualification miss.
            if (!inspected) throw Invalid(error)
            throw error
        }
    }

    /** Invoke before dirty/retired early returns and outside optional COUNT failure catches.
     * true means authenticated old bytes await a separate fresh-authorized write transaction;
     * readers must not rebuild/overwrite this root or silently promote it to current schema.
     */
    suspend fun pending(database: HabitDatabase, access: LocalCoreWriteAccess): Boolean {
        if (database.openHelper.writableDatabase.version < CountCommandV20Schema.VERSION) return false
        return inspectGuarded(database, access).legacy != null
    }

    /** The original account owner supplies fresh entry/final authority and actual END. Only
     * this exact initialization edge can rewrite the envelope; never while a replica is frozen.
     * The root UPDATE does not clear dirty, rewrite nodes, change binding or invent a new key.
     */
    suspend fun advance(database: HabitDatabase, access: LocalCoreWriteAccess) {
      try {
        if (database.openHelper.writableDatabase.version < CountCommandV20Schema.VERSION) return
        val proof = inspectGuarded(database, access).legacy ?: return
        val owner = requireNotNull(CountTrustRuntime.forDatabase(database))
        owner.maintenance(database) {
            val sql = database.openHelper.writableDatabase
            sql.query("SELECT mode FROM next_replica_fence LIMIT 2").use { c ->
                if (c.moveToFirst()) require(c.getType(0) == Cursor.FIELD_TYPE_STRING && c.getString(0) == "closed" && !c.moveToNext()) {
                    "COMMAND_SCHEMA_REPLICA_FROZEN"
                }
            }
            require(control(database) == proof.control && CountTrustSeal.read(sql) == proof.old) { "COMMAND_SCHEMA_BRIDGE_CHANGED" }
            val fresh = CountTrustSeal().prepareWithExistingKey(proof.old.binding, proof.currentSchema, proof.old.root,
                Math.addExact(proof.old.revision, 1L), proof.old.generation, proof.old.validator)
            sql.execSQL("UPDATE count_trust_root SET schemaHash=?,revision=?,mac=? WHERE id=1", arrayOf(fresh.schemaHash, fresh.revision, fresh.mac()))
            require(sql.query("SELECT changes()").use { it.moveToFirst() && it.getLong(0) == 1L && !it.moveToNext() })
            require(CountTrustSeal.read(sql) == fresh && control(database) == proof.control) { "COMMAND_SCHEMA_BRIDGE_CHANGED" }
        }
      } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (invalid: Invalid) { throw invalid }
        catch (error: Exception) { throw Invalid(error) }
    }

    private suspend fun inspectGuarded(database: HabitDatabase, access: LocalCoreWriteAccess,
        captureWarm: Boolean = false): Inspection = try {
        inspect(database, access, captureWarm)
    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
    catch (invalid: Invalid) { throw invalid }
    catch (error: Exception) { throw Invalid(error) }

    private suspend fun inspect(database: HabitDatabase, access: LocalCoreWriteAccess, captureWarm: Boolean): Inspection {
        val owner = requireNotNull(CountTrustRuntime.forDatabase(database))
        require(database.inTransaction()) { "COMMAND_SCHEMA_TRANSACTION_REQUIRED" }
        val caller = currentCoroutineContext()
        return owner.maintenance(database) {
            val sql = database.openHelper.writableDatabase
            require(sql.version == CountCommandV20Schema.VERSION)
            val token = owner.capturePrimaryReadToken(database)
            val lifetime = { owner.requirePrimaryReadToken(database, token); caller.ensureActive() }
            CountCommandPrimarySchema.requireStatisticsPreflight(sql)
            // Capture the baseline before materialization, never after a possibly changed read.
            // Soft-retired primary inspection remains valid and does not need COUNT evidence.
            val epoch = owner.generation
            val job = requireNotNull(currentCoroutineContext()[Job])
            val cookie = if (captureWarm && !owner.isRetired) schemaVersion(database) else null
            val schema = owner.schemaSnapshot()
            caller.ensureActive()
            val current = schemaHash(schema)
            val previousRows = CountCommandV20Schema.previousSchema(schema)
            val previous = schemaHash(previousRows)
            val old = CountTrustSeal.read(sql) ?: return@maintenance Inspection()
            if (old.schemaHash == current) return@maintenance Inspection(warm = cookie?.let {
                WarmSchema(database, owner, token, job, epoch, it, current)
            }) // No legacy traversal/MAC on ordinary warm open.
            val state = control(database)
            val primary = CountCommandSeal().authenticate(CountCommandNativeRows(sql, lifetime).root(), state.storage,
                CountCommandPrimarySchema.hash(schema)) { CountCommandRootAnchor.read(sql).hash }.header
            val exactMigration = old.schemaHash == previous && primary.source == CountCommandRootCodec.Source.MIGRATION_19_20 &&
                primary.mode in setOf(CountCommandRootCodec.Mode.EMPTY, CountCommandRootCodec.Mode.LEGACY_UNENROLLED) && primary.revision == 1L
            val statisticsChanged = state.dirty == 1L && (old.schemaHash in CountCommandPrimarySchema.completeSchemaVariants(schema) ||
                primary.source == CountCommandRootCodec.Source.MIGRATION_19_20 && old.schemaHash != previous &&
                old.schemaHash in CountCommandPrimarySchema.completeSchemaVariants(previousRows))
            require(exactMigration || statisticsChanged) { "COMMAND_SCHEMA_NOT_EXACT_MIGRATION" }
            // Original registration may occur before this first post-upgrade command. Its
            // permitted old unregistered namespace has the same authenticated account/storage;
            // no non-null server/epoch/device replacement is inferred from a matching MAC.
            val candidates = listOf(access,
                access.copy(session = access.session.copy(serverInstanceId = null, syncEpoch = null)),
                access.copy(session = access.session.copy(serverInstanceId = null, syncEpoch = null), capturedDeviceId = null))
                .map { CountTrustSeal.binding(it, state.storage, old.validator) }.toSet()
            require(old.binding in candidates && CountTrustSeal().matches(old, old.binding, old.schemaHash)) { "COMMAND_OLD_COUNT_MAC_INVALID" }
            require(old.generation <= state.generation && (state.dirty != 0L || old.generation == state.generation)) {
                "COMMAND_OLD_COUNT_GENERATION_CHANGED"
            }
            CountTrustIndex(sql, old.binding, old.root, { caller.ensureActive() }, lifetime).verifyContents()
            require(control(database) == state && CountTrustSeal.read(sql) == old && owner.schemaSnapshot() == schema) {
                "COMMAND_SCHEMA_BRIDGE_CHANGED"
            }
            // Tracked optional statistics DDL only invalidates derived eligibility. Verify
            // old MAC and every old node above, then let the original dirty/full-business
            // path rebuild. Never re-sign that old proof against current facts/schema.
            Inspection(legacy = if (statisticsChanged && !exactMigration) null else Legacy(old, current, state))
        }
    }

    private fun schemaVersion(database: HabitDatabase): Long =
        database.openHelper.writableDatabase.query("PRAGMA main.schema_version").use { cursor ->
            check(cursor.columnCount == 1 && cursor.moveToFirst() && cursor.getType(0) == Cursor.FIELD_TYPE_INTEGER) {
                "COUNT_OPEN_SCHEMA_COOKIE_INVALID"
            }
            cursor.getLong(0).also { check(!cursor.moveToNext()) { "COUNT_OPEN_SCHEMA_COOKIE_INVALID" } }
        }

    private fun control(database: HabitDatabase): Control {
        val sql = database.openHelper.writableDatabase
        val state = CountCommandRootAnchor.read(sql)
        return Control(state.storage, state.generation, state.dirty, state.hash)
    }
    private fun schemaHash(rows: List<List<String?>>) = CountTrustIndex.hash(JsonArray(rows.map { row ->
        JsonArray(row.map { it?.let(::JsonPrimitive) ?: JsonNull }) }).toString().toByteArray(Charsets.UTF_8))
}
