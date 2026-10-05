package com.dayforge.data.appearance

import com.dayforge.domain.model.*
import java.security.MessageDigest
import java.util.Collections
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal data class IconCatalogCheckpoint(val generation: Long, val cursor: Long, val through: Long) {
    init {
        require(generation >= 0 && cursor in 0..MAX_ENTRIES && through in cursor..MAX_ENTRIES)
        require((generation != 0L || (cursor == 0L && through == 0L)) && (cursor != 0L || through == 0L))
    }
    companion object { const val MAX_ENTRIES = 33_768L }
}

/** Captured authorization and exact durable CAS; not transferable to another account/device/session. */
internal class IconCatalogAttempt internal constructor(val context: AccountIconContext, val checkpoint: IconCatalogCheckpoint) {
    val binding get() = AssetSyncContext(context.namespace.serverInstanceId, context.namespace.syncEpoch, context.access.deviceId)
    val after get() = checkpoint.cursor
    val through get() = checkpoint.through.takeIf { checkpoint.cursor < it }
}

/** One page = metadata/reservations/download intents/immutable history/checkpoint, all or nothing. */
internal class AccountIconRemoteCatalog(
    private val database: AccountIconDatabase, private val metadata: AccountIconRepository,
    private val transfers: AccountIconTransfers
) {
    init { require(metadata.usesDatabase(database) && transfers.usesMetadata(metadata)) { "ICON_CATALOG_STORE_MISMATCH" } }
    internal fun usesMetadata(value: AccountIconRepository) = metadata === value
    private val dao get() = database.remoteCatalog()
    private val json = Json { encodeDefaults = true }
    private data class Snapshot(val row: AccountIconCatalogStateRow?, val entries: List<AccountIconCatalogEntryRow>,
        val transfers: List<AccountIconTransferRow>) {
        val checkpoint get() = row?.let { IconCatalogCheckpoint(it.generation, it.cursor, it.through) }
            ?: IconCatalogCheckpoint(0, 0, 0)
    }

    suspend fun next(context: AccountIconContext): IconCatalogAttempt = metadata.transferTransaction(context) { catalog ->
        IconCatalogAttempt(context, read(context, catalog).checkpoint)
    }

    suspend fun accept(attempt: IconCatalogAttempt, input: AppearanceCatalogPage): IconCatalogCheckpoint {
        // Freeze caller-owned collections and revalidate under the same strict bounded JSON rules.
        require(input.entries.size <= PAGE_LIMIT)
        input.entries.filterIsInstance<AppearanceCatalogEntry.Pack>().forEach {
            require(it.pack.assets.size in 1..128 && it.pack.roles.size <= 256)
        }
        val coroutine = currentCoroutineContext()
        val raw = json.encodeToString(input).toByteArray(Charsets.UTF_8)
        val page = json.decodeFromString<AppearanceCatalogPage>(strictAppearanceJson(raw, 1_048_576,
            checkpoint = { coroutine.ensureActive() }, fail = { error("ICON_CATALOG_REPLY_$it") }))
        validatePage(attempt, page)
        val assets = page.entries.filterIsInstance<AppearanceCatalogEntry.Asset>().map { it.asset }
        val packs = page.entries.filterIsInstance<AppearanceCatalogEntry.Pack>().map { it.pack }
        return metadata.receiveCatalogTransaction(attempt.context, assets, packs) { catalog ->
            val context = attempt.context
            val before = read(context, catalog) // Missing history/intents fail BEFORE adding replacements.
            val entries = page.entries.map { row(context, it) }
            val checkpoint = before.checkpoint
            if (checkpoint != attempt.checkpoint) {
                // Only an exact duplicate of the latest page may reuse its committed receipt.
                check(attempt.checkpoint.generation < Long.MAX_VALUE &&
                    checkpoint.generation == attempt.checkpoint.generation + 1 &&
                    checkpoint.cursor == page.nextCursor && checkpoint.through == page.throughSequence &&
                    before.entries.filter { it.sequence > attempt.after } == entries) { "ICON_CATALOG_CHANGED" }
                return@receiveCatalogTransaction checkpoint
            }
            if (before.row != null && checkpoint.cursor == page.nextCursor && checkpoint.through == page.throughSequence)
                return@receiveCatalogTransaction checkpoint // Empty unchanged tail; do not exhaust generations.
            check(checkpoint.generation < Long.MAX_VALUE) { "ICON_CATALOG_EXHAUSTED" }
            val previousAssets = before.entries.filter { it.kind == "asset" }.map { it.targetId }.toMutableSet()
            val identities = before.entries.map { Triple(it.kind, it.targetId, it.revision) }.toMutableSet()
            for (entry in page.entries) {
                val receipt = row(context, entry)
                check(identities.add(Triple(receipt.kind, receipt.targetId, receipt.revision))) { "ICON_CATALOG_DUPLICATE" }
                when (entry) {
                    is AppearanceCatalogEntry.Asset -> previousAssets.add(entry.asset.assetId)
                    is AppearanceCatalogEntry.Pack -> check(entry.pack.assets.all { it.assetId in previousAssets }) {
                        "ICON_CATALOG_DEPENDENCY"
                    }
                }
            }
            val jobs = transfers.enqueueCatalogDownloads(context, catalog, assets.map { it.assetId }.toSet())
            entries.forEach { dao.insertEntry(it) }
            val ns = context.namespace
            val changed = AccountIconCatalogStateRow(ns.accountId, ns.serverInstanceId, ns.syncEpoch,
                checkpoint.generation + 1, page.nextCursor, page.throughSequence)
            if (before.row == null) dao.insertState(changed)
            else check(dao.advance(ns.accountId, ns.serverInstanceId, ns.syncEpoch,
                checkpoint.generation, checkpoint.cursor, checkpoint.through,
                changed.generation, changed.cursor, changed.through) == 1) { "ICON_CATALOG_CHANGED" }
            check(read(context, catalog) == Snapshot(changed, before.entries + entries, jobs)) { "ICON_CATALOG_CORRUPT" }
            IconCatalogCheckpoint(changed.generation, changed.cursor, changed.through)
        }
    }

    private fun row(context: AccountIconContext, entry: AppearanceCatalogEntry): AccountIconCatalogEntryRow {
        val ns = context.namespace
        return when (entry) {
            is AppearanceCatalogEntry.Asset -> AccountIconCatalogEntryRow(ns.accountId, ns.serverInstanceId, ns.syncEpoch,
                entry.sequence, "asset", entry.asset.assetId, 0, assetHash(entry.asset))
            is AppearanceCatalogEntry.Pack -> AccountIconCatalogEntryRow(ns.accountId, ns.serverInstanceId, ns.syncEpoch,
                entry.sequence, "pack", entry.pack.packId, entry.pack.revision, packHash(entry.pack))
        }
    }

    private suspend fun read(context: AccountIconContext, catalog: AccountIconTransferMetadata): Snapshot {
        val ns = context.namespace
        val audit = dao.audit(ns.accountId, ns.serverInstanceId, ns.syncEpoch)
        check(!audit.invalidValues && audit.count <= IconCatalogCheckpoint.MAX_ENTRIES) { "ICON_CATALOG_CORRUPT" }
        val state = dao.state(ns.accountId, ns.serverInstanceId, ns.syncEpoch)
        val rows = dao.entries(ns.accountId, ns.serverInstanceId, ns.syncEpoch)
        check(rows.size.toLong() == audit.count && audit.count == (state?.cursor ?: 0L)) { "ICON_CATALOG_CORRUPT" }
        val jobs = transfers.catalogRows(context, catalog)
        val downloads = jobs.filter { it.kind == "download" }.map { it.targetId to it.variant }.toSet()
        val assets = mutableSetOf<String>()
        val identities = mutableSetOf<Triple<String, String, Int>>()
        for ((index, row) in rows.withIndex()) {
            currentCoroutineContext().ensureActive()
            check(row.sequence == index + 1L && isContractUuid(row.targetId) &&
                identities.add(Triple(row.kind, row.targetId, row.revision))) { "ICON_CATALOG_CORRUPT" }
            when (row.kind) {
                "asset" -> {
                    val asset = catalog.assets[row.targetId]
                    check(row.revision == 0 && asset != null && row.metadataHash == assetHash(asset) &&
                        (row.targetId to "light") in downloads &&
                        (asset.dark == null || (row.targetId to "dark") in downloads)) { "ICON_CATALOG_CORRUPT" }
                    assets.add(row.targetId)
                }
                "pack" -> {
                    val pack = catalog.packs[row.targetId to row.revision]
                    check(row.revision > 0 && pack != null && row.metadataHash == packHash(pack) &&
                        pack.assets.all { it.assetId in assets }) { "ICON_CATALOG_CORRUPT" }
                }
                else -> error("ICON_CATALOG_CORRUPT")
            }
        }
        return Snapshot(state, Collections.unmodifiableList(rows), jobs)
    }

    private fun assetHash(asset: IconAsset) = sha(json.encodeToString(asset))
    private fun packHash(pack: IconPack) = sha(json.encodeToString(pack.copy(roles = pack.roles.toSortedMap())))
    private fun sha(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    companion object {
        // Four maximum valid packs fit the existing 1MiB JSON boundary; verified on the device.
        const val PAGE_LIMIT = 4
        fun validatePage(attempt: IconCatalogAttempt, page: AppearanceCatalogPage) {
            validateCatalogBinding(attempt.binding, attempt.after, attempt.through, page)
            require(page.entries.size <= PAGE_LIMIT && page.throughSequence <= IconCatalogCheckpoint.MAX_ENTRIES &&
                page.entries.withIndex().all { (index, entry) -> entry.sequence == attempt.after + index + 1L } &&
                page.nextCursor == attempt.after + page.entries.size) { "ICON_CATALOG_CONTINUITY" }
        }
    }
}
