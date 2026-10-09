package com.dayforge.data.appearance

import androidx.room.withTransaction
import com.dayforge.data.local.LocalIconAccess
import com.dayforge.data.local.TokenManager
import com.dayforge.domain.model.IconAsset
import com.dayforge.domain.model.IconBlob
import com.dayforge.domain.model.IconPack
import com.dayforge.domain.model.IconReference
import com.dayforge.domain.model.iconAllowed
import com.dayforge.domain.model.isContractUuid
import com.dayforge.domain.service.AccountSessionCoordinator
import java.util.Collections
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal data class AccountIconNamespace(val accountId: String, val serverInstanceId: String, val syncEpoch: String) {
    init { require(listOf(accountId, serverInstanceId, syncEpoch).all(::isContractUuid)) }
}

/** Captured request lifetime, not credentials. A caller cannot choose its own owner. */
internal class AccountIconContext internal constructor(internal val access: LocalIconAccess) {
    val namespace = AccountIconNamespace(
        access.session.authentication.userId,
        requireNotNull(access.session.serverInstanceId), requireNotNull(access.session.syncEpoch)
    )
}

internal data class IconReservation(val blob: IconBlob, val operationId: String)
internal data class IconInstallation(val reservation: IconReservation, val validationProfile: String?)

internal data class IconPackVersion(val packId: String, val revision: Int) {
    init { require(isContractUuid(packId) && revision > 0) }
}
internal data class AccountIconSelection(val generation: Long, val pack: IconPackVersion?) {
    init { require(generation >= 0 && (generation > 0 || pack == null)) }
}
internal data class AccountIconResolution(
    val reference: IconReference, val selection: AccountIconSelection,
    val asset: IconAsset?, val placeholder: Boolean
)

internal data class AccountIconTransferMetadata(
    val assets: Map<String, IconAsset>, val packs: Map<Pair<String, Int>, IconPack>
)

internal data class ConfigIconUse(val reference: IconReference, val oneTime: Boolean)
internal data class ConfigOwnedBlob(val assetId: String, val installation: IconInstallation)
internal data class ConfigIconDependencies(
    val selection: AccountIconSelection, val assets: List<IconAsset>, val roles: Map<String, String>,
    val unresolvedRoles: List<String>, val blobs: Map<String, ConfigOwnedBlob>
)

/** Readiness is an advisory local receipt, not a fresh file proof or server acknowledgement. */
internal data class AccountIconCatalog(
    val packs: List<IconPack>, val selection: AccountIconSelection, val readyVersions: Set<IconPackVersion>
)

/** Local safety ceilings; lower limits retain existing data and permit exact replay. */
internal data class AccountIconLimits(
    val assets: Int = 1000, val bytes: Long = 268_435_456L, val metadataBytes: Long = 8_388_608L
) {
    init { require(assets in 0..1000 && bytes in 0..268_435_456L && metadataBytes in 0..8_388_608L) }
}

/**
 * Inactive v5 foundation. Callers must share the account coordinator with login/sync/logout.
 * File installation is coordinated separately; no image cache, UI activation, remote acknowledgement or purge API.
 */
internal class AccountIconRepository(
    private val database: AccountIconDatabase,
    private val tokens: TokenManager,
    private val sessions: AccountSessionCoordinator,
    private val limits: AccountIconLimits = AccountIconLimits()
) {
    private val json = Json { encodeDefaults = true }
    // Parsing only: every use still reads/audits real rows, journals, ready, selection and access.
    // One exact namespace/row snapshot, <= 1 MiB raw metadata; account lock serializes all access.
    private var parsedMetadata: ParsedMetadata? = null

    internal fun usesDatabase(value: AccountIconDatabase): Boolean = database === value
    internal fun remoteCatalog(transfers: AccountIconTransfers) = AccountIconRemoteCatalog(database, this, transfers)

    internal fun registerCache(cache: com.dayforge.data.local.AccountIconMemory.Cache) =
        tokens.registerIconCache(cache)

    suspend fun capture(): AccountIconContext = sessions.exclusive {
        AccountIconContext(requireNotNull(tokens.localIconAccess()) { "ICON_ACCESS_DENIED" })
    }

    suspend fun reauthorize(context: AccountIconContext, writing: Boolean = false) = sessions.exclusive {
        check(context, writing)
    }

    /** Short in-memory publication only. Never perform image I/O/rendering or suspend in block. */
    suspend fun <T> authorized(context: AccountIconContext, block: () -> T): T = sessions.exclusive {
        check(context)
        val result = block()
        check(context)
        result
    }

    suspend fun asset(context: AccountIconContext, assetId: String): IconAsset? {
        require(isContractUuid(assetId))
        return scoped(context) { catalog(context.namespace).assets[assetId] }
    }

    suspend fun pack(context: AccountIconContext, packId: String, revision: Int): IconPack? {
        require(isContractUuid(packId) && revision > 0)
        return scoped(context) { catalog(context.namespace).packs[packId to revision] }
    }

    suspend fun selection(context: AccountIconContext): AccountIconSelection = scoped(context) {
        catalog(context.namespace).selection
    }

    suspend fun library(context: AccountIconContext): AccountIconCatalog = scoped(context) {
        val state = catalog(context.namespace)
        val packs = state.packs.values.sortedWith(compareBy<IconPack> { it.name }.thenBy { it.packId }.thenBy { it.revision })
        val ready = packs.filter { pack -> pack.assets.all { asset ->
            listOfNotNull(asset.light, asset.dark).all { it.sha256 in state.ready }
        } }.map { IconPackVersion(it.packId, it.revision) }.toSet()
        AccountIconCatalog(Collections.unmodifiableList(packs), state.selection, Collections.unmodifiableSet(ready))
    }

    /** One owned metadata/receipt snapshot, not the display resolver's placeholder fallback. */
    internal suspend fun configDependencies(context: AccountIconContext,
        uses: List<ConfigIconUse>): ConfigIconDependencies = scoped(context) {
        require(uses.size <= 2000)
        val state = catalog(context.namespace)
        val selected = state.selection.pack?.let { state.packs.getValue(it.packId to it.revision) }
        val assets = linkedMapOf<String, IconAsset>()
        val roles = sortedMapOf<String, String>()
        val unresolved = sortedSetOf<String>()
        val blobs = sortedMapOf<String, ConfigOwnedBlob>()
        for (use in uses) {
            val asset = when (val reference = use.reference) {
                is IconReference.Role -> {
                    require(iconAllowed(reference, use.oneTime)) { "ICON_PURPOSE_MISMATCH" }
                    val assetId = selected?.roles?.get(reference.role)
                    if (assetId == null) { unresolved.add(reference.role); null }
                    else { roles[reference.role] = assetId; state.assets.getValue(assetId) }
                }
                is IconReference.Asset -> requireNotNull(state.assets[reference.assetId]) { "ICON_ASSET_NOT_OWNED" }
            }
            if (asset != null) {
                require(iconAllowed(use.reference, use.oneTime, asset)) { "ICON_PURPOSE_MISMATCH" }
                assets[asset.assetId] = asset
                // Prove every referring asset before deduplicating bytes. A shared hash is not ownership.
                for (blob in listOfNotNull(asset.light, asset.dark)) {
                    val receipt = installation(state, asset.assetId, blob.sha256)
                    check(receipt.validationProfile != null) { "ICON_NOT_READY" }
                    val previous = blobs[blob.sha256]
                    check(previous == null || previous.installation == receipt) { "ICON_INSTALL_CHANGED" }
                    if (previous == null || asset.assetId < previous.assetId)
                        blobs[blob.sha256] = ConfigOwnedBlob(asset.assetId, receipt)
                }
            }
        }
        check(assets.size <= 128 && unresolved.size <= 256) { "CONFIG_ICON_DEPENDENCY_LIMIT" }
        check(blobs.values.sumOf { it.installation.reservation.blob.byteLength.toLong() } <= 67_108_864L) {
            "CONFIG_ICON_DEPENDENCY_LIMIT"
        }
        ConfigIconDependencies(state.selection, Collections.unmodifiableList(assets.values.sortedBy { it.assetId }),
            Collections.unmodifiableMap(roles), Collections.unmodifiableList(unresolved.toList()),
            Collections.unmodifiableMap(blobs))
    }

    /** Short queue transaction only: no I/O/network, nested account locking or preference writes. */
    internal suspend fun <T> transferTransaction(context: AccountIconContext,
        block: suspend (AccountIconTransferMetadata) -> T): T = scoped(context) {
        val before = catalog(context.namespace)
        val result = block(AccountIconTransferMetadata(
            Collections.unmodifiableMap(before.assets), Collections.unmodifiableMap(before.packs)))
        check(catalog(context.namespace) == before) { "ICON_TRANSFER_CHANGED_METADATA" }
        check(context)
        result
    }

    /** Only the file store may activate a pack, after proving all its real ready variants. */
    suspend fun selectValidated(context: AccountIconContext, expectedGeneration: Long,
        pack: IconPack?, receipts: Map<String, IconInstallation>): AccountIconSelection {
        require(expectedGeneration >= 0)
        var transitioning = false
        try {
            return scoped(context) {
                val ns = context.namespace
                val before = catalog(ns)
                check(before.selection.generation == expectedGeneration) { "ICON_SELECTION_CHANGED" }
                val version = pack?.let { IconPackVersion(it.packId, it.revision) }
                if (pack != null) {
                    check(before.packs[pack.packId to pack.revision] == pack) { "ICON_PACK_NOT_OWNED" }
                    val variants = pack.assets.flatMap { asset ->
                        listOfNotNull(asset.light, asset.dark).map { installation(before, asset.assetId, it.sha256) }
                    }.associateBy { it.reservation.blob.sha256 }
                    check(variants == receipts && variants.values.all { it.validationProfile != null }) { "ICON_NOT_READY" }
                } else require(receipts.isEmpty())
                if (before.selection.pack == version) return@scoped before.selection
                check(expectedGeneration < Long.MAX_VALUE) { "ICON_SELECTION_EXHAUSTED" }
                val next = AccountIconSelection(expectedGeneration + 1, version)
                tokens.beginIconSelectionTransition(); transitioning = true
                val dao = database.icons()
                if (expectedGeneration == 0L) {
                    dao.insertSelection(AccountIconSelectionRow(ns.accountId, ns.serverInstanceId, ns.syncEpoch,
                        next.generation, version?.packId, version?.revision))
                } else check(dao.updateSelection(ns.accountId, ns.serverInstanceId, ns.syncEpoch,
                    expectedGeneration, next.generation, version?.packId, version?.revision) == 1) { "ICON_SELECTION_CHANGED" }
                check(catalog(ns) == before.copy(selection = next)) { "ICON_STORE_CORRUPT" }
                check(context)
                next
            }
        } finally {
            // Keep cache publication closed until the actual Room commit/rollback has finished.
            // Failed/cancelled delivery may have committed: never manufacture a preference rollback.
            if (transitioning) tokens.endIconSelectionTransition()
        }
    }

    /** Short publication check; never render or do file I/O in block. */
    suspend fun <T> withSelection(context: AccountIconContext, expected: AccountIconSelection, block: () -> T): T =
        scoped(context) {
            check(catalog(context.namespace).selection == expected) { "ICON_SELECTION_CHANGED" }
            block()
        }

    /** Missing metadata stays unresolved; a placeholder is only display, never a rewritten reference. */
    suspend fun resolve(context: AccountIconContext, reference: IconReference, oneTime: Boolean): AccountIconResolution =
        scoped(context) {
            val state = catalog(context.namespace)
            val pack = state.selection.pack?.let { state.packs.getValue(it.packId to it.revision) }
            val asset = when (reference) {
                is IconReference.Role -> {
                    require(iconAllowed(reference, oneTime)) { "ICON_PURPOSE_MISMATCH" }
                    pack?.roles?.get(reference.role)?.let(state.assets::getValue)
                }
                is IconReference.Asset -> state.assets[reference.assetId]?.also {
                    require(iconAllowed(reference, oneTime, it)) { "ICON_PURPOSE_MISMATCH" }
                }
            }
            val ready = asset?.takeIf { listOfNotNull(it.light, it.dark).all { blob -> blob.sha256 in state.ready } }
            val fallback = if (ready == null) pack?.placeholderAssetId?.let(state.assets::getValue)?.takeIf {
                listOfNotNull(it.light, it.dark).all { blob -> blob.sha256 in state.ready }
            } else null
            AccountIconResolution(reference, state.selection, ready ?: fallback, fallback != null)
        }

    suspend fun reservations(context: AccountIconContext): List<IconReservation> = scoped(context) {
        Collections.unmodifiableList(catalog(context.namespace).blobs.values.sortedBy { it.blob.sha256 })
    }

    /** A bare hash is never an authorization: the current account must own the referring asset. */
    suspend fun installation(context: AccountIconContext, assetId: String, hash: String, writing: Boolean = false): IconInstallation =
        scoped(context, writing) { installation(catalog(context.namespace), assetId, hash) }

    suspend fun installations(context: AccountIconContext, writing: Boolean = true): List<IconInstallation> = scoped(context, writing) {
        val state = catalog(context.namespace)
        Collections.unmodifiableList(state.blobs.values.sortedBy { it.blob.sha256 }.map {
            IconInstallation(it, state.ready[it.blob.sha256]?.validationProfile)
        })
    }

    /** One real, transactionally audited snapshot for a whole immutable pack read. */
    suspend fun packInstallations(context: AccountIconContext, pack: IconPack,
        requireReady: Boolean = true): Map<String, IconInstallation> = scoped(context) {
        val state = catalog(context.namespace)
        val owned = state.packs[pack.packId to pack.revision]
        check(owned != null && owned == pack) { "ICON_PACK_NOT_OWNED" }
        val receipts = linkedMapOf<String, IconInstallation>()
        for (asset in owned.assets) for (blob in listOfNotNull(asset.light, asset.dark)) {
            val receipt = installation(state, asset.assetId, blob.sha256)
            check(!requireReady || receipt.validationProfile != null) { "ICON_NOT_READY" }
            val previous = receipts.put(blob.sha256, receipt)
            check(previous == null || previous == receipt) { "ICON_INSTALL_CHANGED" }
        }
        Collections.unmodifiableMap(receipts)
    }

    /** Only AccountIconStore calls this after publishing and reading back validated durable bytes. */
    suspend fun markReady(context: AccountIconContext, assetId: String, expected: IconReservation, profile: String) =
        ready(context, assetId, expected, profile, writing = true)

    /** Known owned download bytes change local availability only, never structural metadata. */
    internal suspend fun markDownloadedReady(context: AccountIconContext, assetId: String,
        expected: IconReservation, profile: String) = ready(context, assetId, expected, profile, writing = false)

    private suspend fun ready(context: AccountIconContext, assetId: String, expected: IconReservation,
        profile: String, writing: Boolean) = scoped(context, writing) {
            val ns = context.namespace
            val before = catalog(ns)
            val current = installation(before, assetId, expected.blob.sha256)
            check(current.reservation == expected && profile == iconValidationProfile(expected.blob)) { "ICON_INSTALL_CHANGED" }
            val row = AccountIconReadyRow(ns.accountId, ns.serverInstanceId, ns.syncEpoch,
                expected.blob.sha256, expected.operationId, profile)
            val old = before.ready[expected.blob.sha256]
            check(old == null || old == row) { "ICON_STORE_CORRUPT" }
            if (old == null) database.icons().insertReady(row)
            val after = catalog(ns)
            check(after == before.copy(ready = before.ready + (expected.blob.sha256 to row))) { "ICON_STORE_CORRUPT" }
            check(context, writing)
        }

    private fun installation(state: Catalog, assetId: String, hash: String): IconInstallation {
        require(isContractUuid(assetId))
        val asset = requireNotNull(state.assets[assetId]) { "ICON_ASSET_NOT_OWNED" }
        check(listOfNotNull(asset.light, asset.dark).any { it.sha256 == hash }) { "ICON_ASSET_NOT_OWNED" }
        return IconInstallation(state.blobs.getValue(hash), state.ready[hash]?.validationProfile)
    }

    suspend fun reserveAsset(context: AccountIconContext, asset: IconAsset) {
        reserveAssetTransaction(context, asset) {}
    }

    suspend fun reservePack(context: AccountIconContext, pack: IconPack) {
        reservePackTransaction(context, pack) {}
    }

    /** Queue-only callback in the same short declaration transaction; never files/network/preferences. */
    internal suspend fun <T> reservePackTransaction(context: AccountIconContext, pack: IconPack,
        block: suspend (AccountIconTransferMetadata) -> T): T {
        // Caller-owned collections can have changed since the DTO's constructor validation.
        require(pack.assets.size in 1..128 && pack.roles.size <= 256)
        val snapshot = pack.copy(assets = pack.assets.toList(), roles = pack.roles.toMap())
        val frozen = decodePack(json.encodeToString(snapshot))
        return declare(context, frozen.assets, listOf(frozen), writing = true, block)
    }

    internal suspend fun <T> reserveAssetTransaction(context: AccountIconContext, asset: IconAsset,
        block: suspend (AccountIconTransferMetadata) -> T): T {
        // Freeze and revalidate even a caller-built value, before any persistence.
        val frozen = decodeAsset(json.encodeToString(asset))
        return declare(context, listOf(frozen), emptyList(), writing = true, block)
    }

    /** Authenticated remote catalog only; received metadata is not a local declaration or acknowledgement. */
    internal suspend fun <T> receiveCatalogTransaction(context: AccountIconContext, assets: List<IconAsset>,
        packs: List<IconPack>, block: suspend (AccountIconTransferMetadata) -> T): T {
        require(assets.size + packs.size <= 4)
        val frozenAssets = assets.toList().map { decodeAsset(json.encodeToString(it)) }
        val frozenPacks = packs.toList().map { pack ->
            require(pack.assets.size in 1..128 && pack.roles.size <= 256)
            decodePack(json.encodeToString(pack.copy(assets = pack.assets.toList(), roles = pack.roles.toMap())))
        }
        return declare(context, frozenAssets, frozenPacks, writing = false, block)
    }

    private suspend fun check(context: AccountIconContext, writing: Boolean = false) {
        currentCoroutineContext().ensureActive()
        check(tokens.localIconAccess() == context.access) { "ICON_SESSION_CHANGED" }
        check(!writing || context.access.canDeclare) { "ICON_DECLARATION_DENIED" }
    }

    private suspend fun <T> scoped(context: AccountIconContext, writing: Boolean = false, block: suspend () -> T): T = sessions.exclusive {
        check(context, writing)
        val result = database.withTransaction { block() }
        check(context, writing)
        result
    }

    private suspend fun <T> declare(context: AccountIconContext, assets: List<IconAsset>, packs: List<IconPack>, writing: Boolean,
        block: suspend (AccountIconTransferMetadata) -> T): T =
        sessions.exclusive {
            check(context, writing)
            val result = database.withTransaction {
                val ns = context.namespace
                val before = catalog(ns)
                val newAssets = assets.filter { asset ->
                    val old = before.assets[asset.assetId]
                    check(old == null || old == asset) { "ASSET_ID_REUSED" }
                    old == null
                }
                val owned = before.assets + assets.associateBy { it.assetId }
                check(packs.all { pack -> pack.assets.all { owned[it.assetId] == it } }) { "ICON_ASSET_NOT_OWNED" }
                val newPacks = packs.filter { pack ->
                    val old = before.packs[pack.packId to pack.revision]
                    check(old == null || old == pack) { "PACK_VERSION_REUSED" }
                    old == null
                }
                val descriptors = assets.flatMap { listOfNotNull(it.light, it.dark) }.groupBy { it.sha256 }
                    .mapValues { (_, variants) ->
                        check(variants.all { it == variants.first() }) { "ICON_BLOB_CONFLICT" }
                        variants.first()
                    }
                val newBlobs = descriptors.values.filter { blob ->
                    val old = before.blobs[blob.sha256]?.blob
                    check(old == null || old == blob) { "ICON_BLOB_CONFLICT" }
                    old == null
                }
                val assetRows = newAssets.map {
                    AccountIconAssetRow(ns.accountId, ns.serverInstanceId, ns.syncEpoch, it.assetId, json.encodeToString(it))
                }
                val packRows = newPacks.map {
                    AccountIconPackRow(ns.accountId, ns.serverInstanceId, ns.syncEpoch, it.packId, it.revision, json.encodeToString(it))
                }
                fun quota(used: Long, growth: Long, ceiling: Long) {
                    check(growth == 0L || used + growth <= ceiling) { "ICON_QUOTA_EXCEEDED" }
                }
                quota(before.assets.size.toLong(), assetRows.size.toLong(), limits.assets.toLong())
                quota(before.byteCount, newBlobs.sumOf { it.byteLength.toLong() }, limits.bytes)
                quota(before.metadataBytes, assetRows.sumOf { utf8Size(it.metadataJson) } +
                    packRows.sumOf { utf8Size(it.metadataJson) }, limits.metadataBytes)
                val dao = database.icons()
                val blobRows = newBlobs.map {
                    AccountIconBlobRow(ns.accountId, ns.serverInstanceId, ns.syncEpoch,
                        it.sha256, it.byteLength, it.mediaType, it.width, it.height, UUID.randomUUID().toString())
                }
                blobRows.forEach { dao.insertBlob(it) }
                assetRows.forEach { dao.insertAsset(it) }
                packRows.forEach { dao.insertPack(it) }
                // Detect ignored writes and damaged descriptors before the transaction commits.
                val after = catalog(ns)
                check(after.assets == before.assets + assets.associateBy { it.assetId }) { "ICON_STORE_CORRUPT" }
                check(after.packs == before.packs + packs.associateBy { it.packId to it.revision }) {
                    "ICON_STORE_CORRUPT"
                }
                val expectedBlobs = before.blobs + blobRows.associate {
                    it.sha256 to IconReservation(descriptors.getValue(it.sha256), it.operationId)
                }
                check(after.blobs == expectedBlobs) { "ICON_STORE_CORRUPT" }
                check(after.ready == before.ready) { "ICON_STORE_CORRUPT" }
                check(after.selection == before.selection) { "ICON_STORE_CORRUPT" }
                val value = block(AccountIconTransferMetadata(
                    Collections.unmodifiableMap(after.assets), Collections.unmodifiableMap(after.packs)))
                check(catalog(ns) == after) { "ICON_TRANSFER_CHANGED_METADATA" }
                check(context, writing)
                value
            }
            check(context, writing)
            result
        }

    private data class Catalog(
        val assets: Map<String, IconAsset>, val packs: Map<Pair<String, Int>, IconPack>,
        val blobs: Map<String, IconReservation>, val metadataBytes: Long, val ready: Map<String, AccountIconReadyRow>,
        val selection: AccountIconSelection
    ) {
        val byteCount get() = blobs.values.sumOf { it.blob.byteLength.toLong() }
    }

    private data class ParsedMetadata(
        val namespace: AccountIconNamespace,
        val assetRows: List<AccountIconAssetRow>, val packRows: List<AccountIconPackRow>,
        val assets: Map<String, IconAsset>, val packs: Map<Pair<String, Int>, IconPack>,
        val described: Map<String, IconBlob>, val metadataBytes: Long
    )

    private suspend fun parseMetadata(ns: AccountIconNamespace, assetRows: List<AccountIconAssetRow>,
        packRows: List<AccountIconPackRow>): ParsedMetadata {
        parsedMetadata?.let { old ->
            if (old.namespace == ns && old.assetRows == assetRows && old.packRows == packRows) return old
        }
        // Drop old namespace/content even when parsing the new snapshot fails.
        parsedMetadata = null
        val assets = assetRows.associate { row ->
            val asset = decodeAsset(row.metadataJson)
            check(asset.assetId == row.assetId) { "ICON_STORE_CORRUPT" }
            row.assetId to asset
        }
        val packs = packRows.associate { row ->
            val pack = decodePack(row.metadataJson)
            check(pack.packId == row.packId && pack.revision == row.revision &&
                pack.assets.all { assets[it.assetId] == it }) { "ICON_STORE_CORRUPT" }
            (row.packId to row.revision) to pack
        }
        val described = assets.values.flatMap { listOfNotNull(it.light, it.dark) }.groupBy { it.sha256 }
            .mapValues { (_, variants) ->
                check(variants.all { it == variants.first() }) { "ICON_STORE_CORRUPT" }
                variants.first()
            }
        // Charge canonical JSON, not the incidental whitespace/key order of persisted metadata.
        val metadataBytes = assets.values.sumOf { utf8Size(json.encodeToString(it)) } +
            packs.values.sumOf { utf8Size(json.encodeToString(it)) }
        val parsed = ParsedMetadata(ns, assetRows.toList(), packRows.toList(), assets, packs, described, metadataBytes)
        val rawBytes = assetRows.sumOf { utf8Size(it.metadataJson) } + packRows.sumOf { utf8Size(it.metadataJson) }
        if (rawBytes <= 1_048_576L) parsedMetadata = parsed
        return parsed
    }

    /** Audit the selected namespace only; missing/corrupt journals are never silently repaired. */
    private suspend fun catalog(ns: AccountIconNamespace): Catalog {
        val dao = database.icons()
        val audit = dao.audit(ns.accountId, ns.serverInstanceId, ns.syncEpoch)
        check(!audit.invalidStoredValues && !audit.invalidReadyValues && !audit.invalidSelectionValues &&
            audit.assetCount <= 1000 && audit.packCount <= 32768 && audit.metadataBytes in 0..8_388_608L) {
            "ICON_STORE_CORRUPT"
        }
        val assetRows = dao.assets(ns.accountId, ns.serverInstanceId, ns.syncEpoch)
        val packRows = dao.packs(ns.accountId, ns.serverInstanceId, ns.syncEpoch)
        val blobRows = dao.blobs(ns.accountId, ns.serverInstanceId, ns.syncEpoch)
        val readyRows = dao.ready(ns.accountId, ns.serverInstanceId, ns.syncEpoch)
        check(assetRows.size <= 1000 && packRows.size <= 32768 && blobRows.size <= 2000 && readyRows.size <= 2000) { "ICON_STORE_CORRUPT" }
        val parsed = parseMetadata(ns, assetRows, packRows)
        val assets = parsed.assets
        val packs = parsed.packs
        val selectionRow = dao.selection(ns.accountId, ns.serverInstanceId, ns.syncEpoch)
        val selection = selectionRow?.let {
            val version = it.packId?.let { id -> IconPackVersion(id, requireNotNull(it.revision)) }
            check(version == null || (version.packId to version.revision) in packs) { "ICON_STORE_CORRUPT" }
            AccountIconSelection(it.generation, version)
        } ?: AccountIconSelection(0, null)
        val blobs = blobRows.associate { row ->
            check(isContractUuid(row.operationId)) { "ICON_STORE_CORRUPT" }
            // The exact-current metadata descriptor is already fully validated. Compare every
            // actual SQL field instead of constructing/revalidating the same IconBlob per audit.
            val blob = parsed.described[row.sha256]
            check(blob != null && blob.byteLength == row.byteLength && blob.mediaType == row.mediaType &&
                blob.width == row.width && blob.height == row.height) { "ICON_STORE_CORRUPT" }
            row.sha256 to IconReservation(blob, row.operationId)
        }
        check(blobs.values.map { it.operationId }.toSet().size == blobs.size) { "ICON_STORE_CORRUPT" }
        val ready = readyRows.associate { row ->
            val reservation = blobs[row.sha256]
            check(reservation != null && row.operationId == reservation.operationId &&
                row.validationProfile == iconValidationProfile(reservation.blob)) { "ICON_STORE_CORRUPT" }
            row.sha256 to row
        }
        check(parsed.described.keys == blobs.keys && parsed.described.all { (hash, blob) ->
            blob == blobs[hash]?.blob
        }) { "ICON_STORE_CORRUPT" }
        val metadataBytes = parsed.metadataBytes
        check(metadataBytes <= 8_388_608L && blobs.values.sumOf { it.blob.byteLength.toLong() } <= 268_435_456L) {
            "ICON_STORE_CORRUPT"
        }
        return Catalog(assets, packs, blobs, metadataBytes, ready, selection)
    }

    private suspend fun strict(text: String): String {
        val coroutine = currentCoroutineContext()
        coroutine.ensureActive()
        return strictAppearanceJson(text.toByteArray(Charsets.UTF_8), 1_048_576,
            checkpoint = { coroutine.ensureActive() }, fail = { error("ICON_METADATA_$it") })
    }

    private suspend fun decodeAsset(text: String): IconAsset = json.decodeFromString(strict(text))

    private suspend fun decodePack(text: String): IconPack {
        val pack = json.decodeFromString<IconPack>(strict(text))
        return pack.copy(assets = Collections.unmodifiableList(pack.assets.toList()),
            roles = Collections.unmodifiableMap(pack.roles.toMap()))
    }

    private fun utf8Size(text: String) = text.toByteArray(Charsets.UTF_8).size.toLong()
}
