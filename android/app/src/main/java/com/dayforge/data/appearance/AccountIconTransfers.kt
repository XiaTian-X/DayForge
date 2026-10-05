package com.dayforge.data.appearance

import com.dayforge.domain.model.*
import java.security.MessageDigest
import java.util.Collections
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal enum class IconTransferKind(val wire: String) {
    DECLARE_ASSET("declare_asset"), DECLARE_PACK("declare_pack"), UPLOAD("upload"), DOWNLOAD("download")
}
internal enum class IconTransferState(val wire: String) { PENDING("pending"), SENDING("sending"), BLOCKED("blocked"), COMPLETE("complete") }
internal enum class IconTransferFailure { REMOTE_ID_REUSED, REMOTE_QUOTA, REMOTE_CAPABILITY, REMOTE_METADATA, REMOTE_NOT_FOUND, LOCAL_CONTENT }

internal data class IconTransferJob(val operationId: String, val kind: IconTransferKind, val targetId: String,
    val revision: Int, val variant: String, val state: IconTransferState, val generation: Long,
    val failure: IconTransferFailure?)

/** The captured session is transient. The durable generation, not an in-memory boolean, guards replies. */
internal class IconTransferAttempt internal constructor(
    internal val context: AccountIconContext, internal val row: AccountIconTransferRow,
    val asset: IconAsset?, val pack: IconPack?
) {
    val job get() = row.job()
    val binding get() = AssetSyncContext(context.namespace.serverInstanceId, context.namespace.syncEpoch, context.access.deviceId)
}

private fun AccountIconTransferRow.job() = IconTransferJob(operationId,
    IconTransferKind.entries.single { it.wire == kind }, targetId, revision, variant,
    IconTransferState.entries.single { it.wire == state }, generation, failureCode?.let(IconTransferFailure::valueOf))

/** Durable queue only. No scheduling, remote calls, business/outbox writes, or implicit purge. */
internal class AccountIconTransfers(
    private val database: AccountIconDatabase, private val metadata: AccountIconRepository,
    private val store: AccountIconStore, private val maximumJobs: Int = MAX_JOBS,
    private val operationId: () -> String = { UUID.randomUUID().toString() }
) {
    init {
        require(maximumJobs in 0..MAX_JOBS)
        // A same-file OTHER Room instance is not part of this transaction either.
        require(metadata.usesDatabase(database) && store.usesMetadata(metadata)) { "ICON_TRANSFER_STORE_MISMATCH" }
    }
    private val json = Json { encodeDefaults = true }
    internal fun usesStores(valueMetadata: AccountIconRepository, valueStore: AccountIconStore) =
        metadata === valueMetadata && store === valueStore
    private val dao get() = database.transfers()
    private data class Key(val kind: String, val targetId: String, val revision: Int = 0, val variant: String = "")
    private fun AccountIconTransferRow.key() = Key(kind, targetId, revision, variant)
    private fun next(value: Long): Long { check(value < Long.MAX_VALUE) { "ICON_TRANSFER_EXHAUSTED" }; return value + 1 }
    private fun writing(context: AccountIconContext, kind: String) {
        check(kind == IconTransferKind.DOWNLOAD.wire || context.access.canDeclare) { "ICON_DECLARATION_DENIED" }
    }

    suspend fun jobs(context: AccountIconContext): List<IconTransferJob> = scoped(context) { _, rows ->
        Collections.unmodifiableList(rows.map { it.job() })
    }

    suspend fun enqueueAsset(context: AccountIconContext, assetId: String): List<IconTransferJob> = enqueue(context) { catalog ->
        val asset = requireNotNull(catalog.assets[assetId]) { "ICON_ASSET_NOT_OWNED" }
        assetKeys(asset)
    }

    /** Freeze declaration and reserve every intent before committing, with no nested account lock. */
    suspend fun reserveAndEnqueueAsset(context: AccountIconContext, asset: IconAsset): List<IconTransferJob> =
        metadata.reserveAssetTransaction(context, asset) { catalog ->
            enqueueWithinTransaction(context, catalog, read(context, catalog), assetKeys(catalog.assets.getValue(asset.assetId)))
        }

    suspend fun reserveAndEnqueuePack(context: AccountIconContext, pack: IconPack): List<IconTransferJob> =
        metadata.reservePackTransaction(context, pack) { catalog ->
            enqueueWithinTransaction(context, catalog, read(context, catalog), packKeys(catalog, IconPackVersion(pack.packId, pack.revision)))
        }

    /** All declarations and variants enter the same short transaction; no network/byte success inferred. */
    suspend fun enqueuePack(context: AccountIconContext, version: IconPackVersion): List<IconTransferJob> = enqueue(context) { catalog ->
        packKeys(catalog, version)
    }

    private fun packKeys(catalog: AccountIconTransferMetadata, version: IconPackVersion): List<Key> {
        val pack = requireNotNull(catalog.packs[version.packId to version.revision]) { "ICON_PACK_NOT_OWNED" }
        return pack.assets.flatMap(::assetKeys) + Key(IconTransferKind.DECLARE_PACK.wire, pack.packId, pack.revision)
    }

    suspend fun enqueueDownload(context: AccountIconContext, assetId: String, variant: String): IconTransferJob =
        enqueue(context) { catalog ->
            require(isContractUuid(assetId) && variant in setOf("light", "dark"))
            val asset = requireNotNull(catalog.assets[assetId]) { "ICON_ASSET_NOT_OWNED" }
            require(variant == "light" || asset.dark != null)
            listOf(Key(IconTransferKind.DOWNLOAD.wire, assetId, variant = variant))
        }.single()

    private fun assetKeys(asset: IconAsset) = listOf(Key(IconTransferKind.DECLARE_ASSET.wire, asset.assetId),
        Key(IconTransferKind.UPLOAD.wire, asset.assetId, variant = "light")) +
        if (asset.dark == null) emptyList() else listOf(Key(IconTransferKind.UPLOAD.wire, asset.assetId, variant = "dark"))

    private suspend fun enqueue(context: AccountIconContext, keys: (AccountIconTransferMetadata) -> List<Key>): List<IconTransferJob> =
        scoped(context) { catalog, before ->
            enqueueWithinTransaction(context, catalog, before, keys(catalog))
        }

    private suspend fun enqueueWithinTransaction(context: AccountIconContext, catalog: AccountIconTransferMetadata,
        before: List<AccountIconTransferRow>, keys: List<Key>): List<IconTransferJob> {
        val requests = keys.distinct()
        requests.forEach { writing(context, it.kind) }
        val existing = before.associateBy { it.key() }
        val added = requests.filter { it !in existing }.map { key ->
            val id = operationId(); require(isContractUuid(id))
            AccountIconTransferRow(context.namespace.accountId, context.namespace.serverInstanceId, context.namespace.syncEpoch,
                id, key.kind, key.targetId, key.revision, key.variant, hash(catalog, key), "pending", 0, null, null, null, 0)
        }
        check(added.isEmpty() || before.size + added.size <= maximumJobs) { "ICON_TRANSFER_QUOTA" }
        added.forEach { dao.insert(it) }
        val after = read(context, catalog)
        check(after == (before + added).sortedBy { it.operationId }) { "ICON_TRANSFER_CORRUPT" }
        val byKey = after.associateBy { it.key() }
        // Match durable enumeration, independent of the caller's pack/variant order.
        return Collections.unmodifiableList(requests.map { byKey.getValue(it).job() }.sortedBy { it.operationId })
    }

    /** One CAS claim. Uploads/packs wait for the exact asset declarations, not local ready rows. */
    suspend fun prepareNext(context: AccountIconContext, deferred: Set<String> = emptySet()): IconTransferAttempt? = scoped(context) { catalog, before ->
        require(deferred.size <= 32)
        val excluded = deferred.toSet()
        require(excluded.size <= 32 && excluded.all(::isContractUuid))
        val confirmed = before.filter { it.state == "complete" }.map { it.key() }.toSet()
        // UUID order is only a stable tie-breaker. Otherwise a temporarily pending download
        // can win every bounded batch and prevent the very uploads it is waiting for.
        val row = before.asSequence().filter { it.state == "pending" && it.operationId !in excluded &&
            (it.kind == IconTransferKind.DOWNLOAD.wire || context.access.canDeclare) && dependencies(catalog, it).all { key ->
                key in confirmed
            }
        }.minWithOrNull(compareBy<AccountIconTransferRow> { it.generation }.thenBy { it.operationId }) ?: return@scoped null
        check(row.generation <= Long.MAX_VALUE - 2) { "ICON_TRANSFER_EXHAUSTED" } // Reserve the confirmation/release transition too.
        val changed = row.copy(state = "sending", generation = next(row.generation), deviceId = context.access.deviceId)
        change(context, catalog, before, row, changed)
        IconTransferAttempt(context, changed, catalog.assets[row.targetId], catalog.packs[row.targetId to row.revision])
    }

    suspend fun confirmAsset(attempt: IconTransferAttempt, response: AssetRecord): IconTransferJob {
        require(attempt.row.kind == IconTransferKind.DECLARE_ASSET.wire && response.readyVariants.size <= 2)
        val frozen = json.decodeFromString<AssetRecord>(json.encodeToString(response))
        validateAssetRecordBinding(AssetDeclaration(attempt.binding, requireNotNull(attempt.asset)), frozen)
        val mask = (if ("light" in frozen.readyVariants) 1 else 0) + (if ("dark" in frozen.readyVariants) 2 else 0)
        return confirm(attempt, sha(json.encodeToString(frozen.copy(readyVariants = frozen.readyVariants.sorted()))), mask)
    }

    suspend fun confirmPack(attempt: IconTransferAttempt, response: PackDeclaration): IconTransferJob {
        require(attempt.row.kind == IconTransferKind.DECLARE_PACK.wire && response.pack.assets.size in 1..128 && response.pack.roles.size <= 256)
        val frozen = json.decodeFromString<PackDeclaration>(json.encodeToString(response))
        validatePackBinding(PackDeclaration(attempt.binding, requireNotNull(attempt.pack)), frozen)
        return confirm(attempt, sha(json.encodeToString(frozen.copy(pack = frozen.pack.copy(roles = frozen.pack.roles.toSortedMap())))))
    }

    suspend fun confirmUpload(attempt: IconTransferAttempt, response: AssetTransferReceipt): IconTransferJob {
        require(attempt.row.kind == IconTransferKind.UPLOAD.wire)
        validateTransferBinding(AssetDeclaration(attempt.binding, requireNotNull(attempt.asset)), attempt.row.variant, response)
        return confirm(attempt, sha(json.encodeToString(response)))
    }

    /** Local availability only, NOT a server acknowledgement. Never trust a ready row alone. */
    suspend fun confirmDownload(attempt: IconTransferAttempt): IconTransferJob {
        require(attempt.row.kind == IconTransferKind.DOWNLOAD.wire)
        val asset = requireNotNull(attempt.asset)
        val blob = if (attempt.row.variant == "light") asset.light else requireNotNull(asset.dark)
        val proof = sha("local-validated:${json.encodeToString(blob)}:${iconValidationProfile(blob)}")
        val completed = attempt.row.copy(state = "complete", generation = next(attempt.row.generation), confirmationHash = proof)
        // Exact repeated delivery still proves the actual file; never return an old ready-only success.
        scoped(attempt.context) { catalog, rows ->
            checkAttemptMetadata(catalog, attempt)
            check(rows.any { it == attempt.row || it == completed }) { "ICON_TRANSFER_CHANGED" }
        }
        store.read(attempt.context, asset.assetId, blob.sha256)
        return confirm(attempt, proof)
    }

    private suspend fun confirm(attempt: IconTransferAttempt, proof: String, mask: Int = 0): IconTransferJob =
        scoped(attempt.context) { catalog, before ->
            checkAttemptMetadata(catalog, attempt)
            val expected = attempt.row.copy(state = "complete", generation = next(attempt.row.generation), confirmationHash = proof, readyMask = mask)
            val current = before.singleOrNull { it.operationId == attempt.row.operationId }
            if (current == expected) return@scoped current.job() // Exact duplicate delivery, no write.
            writing(attempt.context, attempt.row.kind)
            change(attempt.context, catalog, before, attempt.row, expected).job()
        }

    private fun checkAttemptMetadata(catalog: AccountIconTransferMetadata, attempt: IconTransferAttempt) {
        val ns = attempt.context.namespace
        val matches = if (attempt.row.kind == "declare_pack")
            attempt.pack == catalog.packs[attempt.row.targetId to attempt.row.revision]
        else attempt.asset == catalog.assets[attempt.row.targetId]
        check(attempt.row.accountId == ns.accountId && attempt.row.serverInstanceId == ns.serverInstanceId &&
            attempt.row.syncEpoch == ns.syncEpoch && attempt.row.state == "sending" &&
            attempt.row.deviceId == attempt.context.access.deviceId && matches) { "ICON_TRANSFER_CHANGED" }
    }

    suspend fun release(attempt: IconTransferAttempt): IconTransferJob = finishFailure(attempt, null)
    suspend fun block(attempt: IconTransferAttempt, failure: IconTransferFailure): IconTransferJob = finishFailure(attempt, failure)
    private suspend fun finishFailure(attempt: IconTransferAttempt, failure: IconTransferFailure?): IconTransferJob =
        scoped(attempt.context) { catalog, before ->
            checkAttemptMetadata(catalog, attempt)
            writing(attempt.context, attempt.row.kind)
            change(attempt.context, catalog, before, attempt.row, attempt.row.copy(
                state = if (failure == null) "pending" else "blocked", generation = next(attempt.row.generation),
                deviceId = null, failureCode = failure?.name)).job()
        }

    suspend fun retryBlocked(context: AccountIconContext, operation: String, generation: Long): IconTransferJob =
        scoped(context) { catalog, before ->
            val row = requireNotNull(before.singleOrNull { it.operationId == operation }) { "ICON_TRANSFER_NOT_FOUND" }
            writing(context, row.kind)
            check(row.state == "blocked" && row.generation == generation) { "ICON_TRANSFER_CHANGED" }
            change(context, catalog, before, row, row.copy(state = "pending", generation = next(row.generation), failureCode = null)).job()
        }

    /** A local availability proof is not permanent. Explicit recheck preserves identity and bytes. */
    suspend fun retryCompletedDownload(context: AccountIconContext, operation: String, generation: Long): IconTransferJob =
        scoped(context) { catalog, before ->
            val row = requireNotNull(before.singleOrNull { it.operationId == operation }) { "ICON_TRANSFER_NOT_FOUND" }
            check(row.kind == "download" && row.state == "complete" && row.generation == generation) { "ICON_TRANSFER_CHANGED" }
            change(context, catalog, before, row, row.copy(state = "pending", generation = next(row.generation),
                deviceId = null, confirmationHash = null, readyMask = 0)).job()
        }

    /** Call only after the old processor has joined (startup/reauthentication), never on each tick. */
    suspend fun recoverInterrupted(context: AccountIconContext): Int = scoped(context) { catalog, before ->
        var current = before
        val interrupted = before.filter { it.state == "sending" && (it.kind == IconTransferKind.DOWNLOAD.wire || context.access.canDeclare) }
        // Prove ALL counters before the first write so exhaustion cannot yield partial recovery.
        interrupted.forEach { next(it.generation) }
        for (row in interrupted) {
            val replacement = row.copy(state = "pending", generation = next(row.generation), deviceId = null)
            change(context, catalog, current, row, replacement)
            current = current.map { if (it.operationId == row.operationId) replacement else it }
        }
        interrupted.size
    }

    private suspend fun change(context: AccountIconContext, catalog: AccountIconTransferMetadata,
        before: List<AccountIconTransferRow>, row: AccountIconTransferRow, replacement: AccountIconTransferRow): AccountIconTransferRow {
        check(row.state == "sending" || row.state == "pending" || row.state == "blocked" ||
            (row.state == "complete" && row.kind == "download")) { "ICON_TRANSFER_CHANGED" }
        check(before.any { it == row }) { "ICON_TRANSFER_CHANGED" }
        val ns = context.namespace
        check(dao.transition(ns.accountId, ns.serverInstanceId, ns.syncEpoch, row.operationId, row.generation, row.state,
            replacement.state, replacement.generation, replacement.deviceId, replacement.failureCode,
            replacement.confirmationHash, replacement.readyMask) == 1) { "ICON_TRANSFER_CHANGED" }
        check(read(context, catalog) == before.map { if (it == row) replacement else it }) { "ICON_TRANSFER_CORRUPT" }
        return replacement
    }

    private suspend fun <T> scoped(context: AccountIconContext,
        block: suspend (AccountIconTransferMetadata, List<AccountIconTransferRow>) -> T): T = metadata.transferTransaction(context) { catalog ->
        block(catalog, read(context, catalog))
    }

    private fun hash(catalog: AccountIconTransferMetadata, key: Key): String = if (key.kind == "declare_pack") {
        val pack = requireNotNull(catalog.packs[key.targetId to key.revision]) { "ICON_TRANSFER_CORRUPT" }
        sha(json.encodeToString(pack.copy(roles = pack.roles.toSortedMap())))
    } else sha(json.encodeToString(requireNotNull(catalog.assets[key.targetId]) { "ICON_TRANSFER_CORRUPT" }))

    private fun dependencies(catalog: AccountIconTransferMetadata, row: AccountIconTransferRow): List<Key> = when (row.kind) {
        "upload" -> listOf(Key("declare_asset", row.targetId))
        "declare_pack" -> catalog.packs.getValue(row.targetId to row.revision).assets.map { Key("declare_asset", it.assetId) }
        else -> emptyList()
    }

    private suspend fun read(context: AccountIconContext, catalog: AccountIconTransferMetadata): List<AccountIconTransferRow> {
        val ns = context.namespace
        val audit = dao.audit(ns.accountId, ns.serverInstanceId, ns.syncEpoch)
        check(!audit.invalidValues && audit.count <= MAX_JOBS) { "ICON_TRANSFER_CORRUPT" }
        val rows = dao.rows(ns.accountId, ns.serverInstanceId, ns.syncEpoch)
        check(rows.size.toLong() == audit.count) { "ICON_TRANSFER_CORRUPT" }
        val byKey = rows.associateBy { it.key() }
        check(byKey.size == rows.size) { "ICON_TRANSFER_CORRUPT" }
        val hashes = mutableMapOf<Pair<String, Int>, String>()
        for (row in rows) {
            currentCoroutineContext().ensureActive()
            check(isContractUuid(row.operationId) && isContractUuid(row.targetId) && row.kind in KIND_NAMES) { "ICON_TRANSFER_CORRUPT" }
            if (row.kind == "declare_pack") check(row.revision > 0 && row.variant.isEmpty()) { "ICON_TRANSFER_CORRUPT" }
            else {
                check(row.revision == 0) { "ICON_TRANSFER_CORRUPT" }
                val asset = requireNotNull(catalog.assets[row.targetId]) { "ICON_TRANSFER_CORRUPT" }
                if (row.kind == "declare_asset") check(row.variant.isEmpty()) { "ICON_TRANSFER_CORRUPT" }
                else check(row.variant == "light" || (row.variant == "dark" && asset.dark != null)) { "ICON_TRANSFER_CORRUPT" }
            }
            check(row.metadataHash == hashes.getOrPut(row.targetId to row.revision) { hash(catalog, row.key()) }) { "ICON_TRANSFER_CORRUPT" }
            check(row.failureCode == null || row.failureCode in FAILURE_NAMES) { "ICON_TRANSFER_CORRUPT" }
            when (row.state) {
                "pending" -> check(row.deviceId == null && row.failureCode == null && row.confirmationHash == null && row.readyMask == 0)
                "sending" -> check(row.generation > 0 && row.deviceId?.let(::isContractUuid) == true && row.failureCode == null && row.confirmationHash == null && row.readyMask == 0)
                "blocked" -> check(row.generation >= 2 && row.deviceId == null && row.failureCode != null && row.confirmationHash == null && row.readyMask == 0)
                "complete" -> {
                    check(row.generation >= 2 && row.deviceId?.let(::isContractUuid) == true && row.failureCode == null && row.confirmationHash?.matches(Regex("[0-9a-f]{64}")) == true)
                    if (row.kind == "declare_asset") check(catalog.assets.getValue(row.targetId).dark != null || row.readyMask and 2 == 0)
                    else check(row.readyMask == 0)
                }
                else -> error("ICON_TRANSFER_CORRUPT")
            }
            if (row.state == "sending" || row.state == "complete" || row.state == "blocked")
                check(dependencies(catalog, row).all { key -> byKey[key]?.state == "complete" }) { "ICON_TRANSFER_CORRUPT" }
        }
        return rows
    }

    private fun sha(value: String): String = buildString(64) {
        for (byte in MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))) {
            append(HEX[(byte.toInt() ushr 4) and 15]); append(HEX[byte.toInt() and 15])
        }
    }

    companion object {
        const val MAX_JOBS = 37_768
        private const val HEX = "0123456789abcdef"
        private val KIND_NAMES = IconTransferKind.entries.map { it.wire }.toSet()
        private val FAILURE_NAMES = IconTransferFailure.entries.map { it.name }.toSet()
    }
}
