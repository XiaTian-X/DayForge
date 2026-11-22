package com.dayforge.data.repository

import com.dayforge.data.local.CountCommandV20Schema
import com.dayforge.data.local.CountTrustRuntime
import com.dayforge.data.local.CountTrustSchema
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.LocalSyncAccess
import com.dayforge.data.local.TokenManager
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Recovery review counts, never execution/restore authority. Read the existing authenticated
 * frontier, not a misleading COUNT(status=P) or a filtered native subset. Full raw archive and
 * source hashes still retain every command table; no authenticated historical row is dropped.
 */
internal class CountCommandRecoveryInventory(private val database: HabitDatabase, private val tokens: TokenManager) {
    suspend fun pendingInTransaction(expected: LocalSyncAccess): Long {
        check(database.inTransaction())
        val owner = requireNotNull(CountTrustRuntime.forDatabase(database))
        val context = currentCoroutineContext()
        val header = owner.maintenance(database) {
            val token = owner.capturePrimaryReadToken(database)
            val live = { owner.requirePrimaryReadToken(database, token); context.ensureActive() }
            val sql = database.openHelper.writableDatabase
            CountTrustSchema.requireControl(sql)
            val storage = sql.query("SELECT storageId FROM count_trust_control WHERE id=1 LIMIT 2").use {
                require(it.moveToFirst()); it.getString(0).also { _ -> require(!it.moveToNext()) }
            }
            com.dayforge.data.local.CountCommandPrimarySchema.requireStatisticsPreflight(sql)
            val schema = owner.schemaSnapshot(); CountCommandV20Schema.previousSchema(schema)
            val hash = com.dayforge.data.local.CountCommandPrimarySchema.hash(schema)
            val envelope = CountCommandSeal().authenticate(CountCommandNativeRows(sql, live).root(), storage, hash) { CountCommandRootAnchor.read(sql).hash }
            val authenticated = envelope.header
            if (authenticated.mode == CountCommandRootCodec.Mode.ACCOUNT_BOUND_RETAINED)
                CountCommandRetainedPartition.verify(sql, envelope, live)
            if (authenticated.mode == CountCommandRootCodec.Mode.LEGACY_UNENROLLED)
                com.dayforge.data.local.CountCommandStorageInitialization.verifyMandatory(sql)
            when (authenticated.mode) {
                CountCommandRootCodec.Mode.EMPTY, CountCommandRootCodec.Mode.LEGACY_UNENROLLED -> {
                    for (table in listOf("count_command_intents", "count_command_status", "count_command_nodes"))
                        sql.query("SELECT 1 FROM `$table` LIMIT 1").use { require(!it.moveToFirst()) { "COMMAND_INITIAL_ANCHOR_CONTENT_CHANGED" } }
                    if (authenticated.mode == CountCommandRootCodec.Mode.EMPTY)
                        sql.query("SELECT 1 FROM count_command_results LIMIT 1").use { require(!it.moveToFirst()) { "COMMAND_INITIAL_ANCHOR_CONTENT_CHANGED" } }
                }
                CountCommandRootCodec.Mode.ACCOUNT_BOUND, CountCommandRootCodec.Mode.ACCOUNT_BOUND_RETAINED -> {
                    // Match Store's readonly requireBound=false catalog ownership, not GC's
                    // current execution namespace. Old server/epoch obligations stay visible
                    // as isolated historical data; no PREPARED may execute across that scope.
                    val current = CountCommandRecords.Namespace(expected.session.authentication.userId,
                        expected.session.serverInstanceId, expected.session.syncEpoch, expected.deviceId, storage, authenticated.datasetId)
                    require(authenticated.accountBinding == current.catalogBinding) { "COMMAND_STORAGE_ACCOUNT_CHANGED" }
                }
            }
            live(); authenticated
        }
        header.registration?.let { require(tokens.registeredInstallationId() == it.installationId) { "COMMAND_INSTALLATION_CHANGED" } }
        check(tokens.isCurrentSyncAuthority(expected)) { "SYNC_ACCOUNT_CHANGED" }
        return when (header.mode) {
            CountCommandRootCodec.Mode.EMPTY -> 0L
            CountCommandRootCodec.Mode.LEGACY_UNENROLLED -> requireNotNull(header.legacyCount)
            CountCommandRootCodec.Mode.ACCOUNT_BOUND -> header.pending.count
            CountCommandRootCodec.Mode.ACCOUNT_BOUND_RETAINED -> Math.addExact(header.pending.count, requireNotNull(header.legacyCount))
        }
    }
}
