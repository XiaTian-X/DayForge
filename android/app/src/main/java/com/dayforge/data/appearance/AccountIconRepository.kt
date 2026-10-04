package com.dayforge.data.appearance

import androidx.room.withTransaction
import com.dayforge.data.local.LocalIconAccess
import com.dayforge.data.local.TokenManager
import com.dayforge.domain.model.IconAsset
import com.dayforge.domain.model.IconBlob
import com.dayforge.domain.model.IconPack
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

/** Local safety ceilings; lower limits retain existing data and permit exact replay. */
internal data class AccountIconLimits(
    val assets: Int = 1000, val bytes: Long = 268_435_456L, val metadataBytes: Long = 8_388_608L
) {
    init { require(assets in 0..1000 && bytes in 0..268_435_456L && metadataBytes in 0..8_388_608L) }
}

/**
 * Inactive v5 foundation. Callers must share the account coordinator with login/sync/logout.
 * File installation is coordinated separately; no cache, UI activation, remote acknowledgement or purge API.
 */
internal class AccountIconRepository(
    private val database: AccountIconDatabase,
    private val tokens: TokenManager,
    private val sessions: AccountSessionCoordinator,
    private val limits: AccountIconLimits = AccountIconLimits()
) {
    private val json = Json { encodeDefaults = true }

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

    suspend fun reservations(context: AccountIconContext): List<IconReservation> = scoped(context) {
        Collections.unmodifiableList(catalog(context.namespace).blobs.values.sortedBy { it.blob.sha256 })
    }

    /** A bare hash is never an authorization: the current account must own the referring asset. */
    suspend fun installation(context: AccountIconContext, assetId: String, hash: String, writing: Boolean = false): IconInstallation =
        scoped(context, writing) { installation(catalog(context.namespace), assetId, hash) }

    suspend fun installations(context: AccountIconContext): List<IconInstallation> = scoped(context, writing = true) {
        val state = catalog(context.namespace)
        Collections.unmodifiableList(state.blobs.values.sortedBy { it.blob.sha256 }.map {
            IconInstallation(it, state.ready[it.blob.sha256]?.validationProfile)
        })
    }

    /** Only AccountIconStore calls this after publishing and reading back validated durable bytes. */
    suspend fun markReady(context: AccountIconContext, assetId: String, expected: IconReservation, profile: String) =
        scoped(context, writing = true) {
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
            check(context, writing = true)
        }

    private fun installation(state: Catalog, assetId: String, hash: String): IconInstallation {
        require(isContractUuid(assetId))
        val asset = requireNotNull(state.assets[assetId]) { "ICON_ASSET_NOT_OWNED" }
        check(listOfNotNull(asset.light, asset.dark).any { it.sha256 == hash }) { "ICON_ASSET_NOT_OWNED" }
        return IconInstallation(state.blobs.getValue(hash), state.ready[hash]?.validationProfile)
    }

    suspend fun reserveAsset(context: AccountIconContext, asset: IconAsset) {
        // Freeze and revalidate even a caller-built value, before any persistence.
        val frozen = decodeAsset(json.encodeToString(asset))
        declare(context, listOf(frozen), null)
    }

    suspend fun reservePack(context: AccountIconContext, pack: IconPack) {
        // Caller-owned collections can have changed since the DTO's constructor validation.
        require(pack.assets.size in 1..128 && pack.roles.size <= 256)
        val snapshot = pack.copy(assets = pack.assets.toList(), roles = pack.roles.toMap())
        val frozen = decodePack(json.encodeToString(snapshot))
        declare(context, frozen.assets, frozen)
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

    private suspend fun declare(context: AccountIconContext, assets: List<IconAsset>, pack: IconPack?) =
        sessions.exclusive {
            check(context, writing = true)
            database.withTransaction {
                val ns = context.namespace
                val before = catalog(ns)
                val newAssets = assets.filter { asset ->
                    val old = before.assets[asset.assetId]
                    check(old == null || old == asset) { "ASSET_ID_REUSED" }
                    old == null
                }
                val oldPack = pack?.let { before.packs[it.packId to it.revision] }
                check(oldPack == null || oldPack == pack) { "PACK_VERSION_REUSED" }
                val newPack = pack?.takeIf { oldPack == null }
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
                val packRow = newPack?.let {
                    AccountIconPackRow(ns.accountId, ns.serverInstanceId, ns.syncEpoch, it.packId, it.revision, json.encodeToString(it))
                }
                fun quota(used: Long, growth: Long, ceiling: Long) {
                    check(growth == 0L || used + growth <= ceiling) { "ICON_QUOTA_EXCEEDED" }
                }
                quota(before.assets.size.toLong(), assetRows.size.toLong(), limits.assets.toLong())
                quota(before.byteCount, newBlobs.sumOf { it.byteLength.toLong() }, limits.bytes)
                quota(before.metadataBytes, assetRows.sumOf { utf8Size(it.metadataJson) } +
                    (packRow?.let { utf8Size(it.metadataJson) } ?: 0L), limits.metadataBytes)
                val dao = database.icons()
                val blobRows = newBlobs.map {
                    AccountIconBlobRow(ns.accountId, ns.serverInstanceId, ns.syncEpoch,
                        it.sha256, it.byteLength, it.mediaType, it.width, it.height, UUID.randomUUID().toString())
                }
                blobRows.forEach { dao.insertBlob(it) }
                assetRows.forEach { dao.insertAsset(it) }
                packRow?.let { dao.insertPack(it) }
                // Detect ignored writes and damaged descriptors before the transaction commits.
                val after = catalog(ns)
                check(after.assets == before.assets + assets.associateBy { it.assetId }) { "ICON_STORE_CORRUPT" }
                check(after.packs == before.packs + (pack?.let { mapOf((it.packId to it.revision) to it) } ?: emptyMap())) {
                    "ICON_STORE_CORRUPT"
                }
                val expectedBlobs = before.blobs + blobRows.associate {
                    it.sha256 to IconReservation(descriptors.getValue(it.sha256), it.operationId)
                }
                check(after.blobs == expectedBlobs) { "ICON_STORE_CORRUPT" }
                check(after.ready == before.ready) { "ICON_STORE_CORRUPT" }
                check(context, writing = true)
            }
            check(context, writing = true)
        }

    private data class Catalog(
        val assets: Map<String, IconAsset>, val packs: Map<Pair<String, Int>, IconPack>,
        val blobs: Map<String, IconReservation>, val metadataBytes: Long, val ready: Map<String, AccountIconReadyRow>
    ) {
        val byteCount get() = blobs.values.sumOf { it.blob.byteLength.toLong() }
    }

    /** Audit the selected namespace only; missing/corrupt journals are never silently repaired. */
    private suspend fun catalog(ns: AccountIconNamespace): Catalog {
        val dao = database.icons()
        check(!dao.invalidStoredValues(ns.accountId, ns.serverInstanceId, ns.syncEpoch) &&
            dao.assetCount(ns.accountId, ns.serverInstanceId, ns.syncEpoch) <= 1000 &&
            dao.packCount(ns.accountId, ns.serverInstanceId, ns.syncEpoch) <= 32768 &&
            dao.metadataBytes(ns.accountId, ns.serverInstanceId, ns.syncEpoch) in 0..8_388_608L) {
            "ICON_STORE_CORRUPT"
        }
        val assetRows = dao.assets(ns.accountId, ns.serverInstanceId, ns.syncEpoch)
        val packRows = dao.packs(ns.accountId, ns.serverInstanceId, ns.syncEpoch)
        val blobRows = dao.blobs(ns.accountId, ns.serverInstanceId, ns.syncEpoch)
        check(!dao.invalidReadyValues(ns.accountId, ns.serverInstanceId, ns.syncEpoch)) { "ICON_STORE_CORRUPT" }
        val readyRows = dao.ready(ns.accountId, ns.serverInstanceId, ns.syncEpoch)
        check(assetRows.size <= 1000 && packRows.size <= 32768 && blobRows.size <= 2000 && readyRows.size <= 2000) { "ICON_STORE_CORRUPT" }
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
        val blobs = blobRows.associate { row ->
            check(isContractUuid(row.operationId)) { "ICON_STORE_CORRUPT" }
            row.sha256 to IconReservation(IconBlob(row.sha256, row.byteLength, row.mediaType, row.width, row.height), row.operationId)
        }
        check(blobs.values.map { it.operationId }.toSet().size == blobs.size) { "ICON_STORE_CORRUPT" }
        val ready = readyRows.associate { row ->
            val reservation = blobs[row.sha256]
            check(reservation != null && row.operationId == reservation.operationId &&
                row.validationProfile == iconValidationProfile(reservation.blob)) { "ICON_STORE_CORRUPT" }
            row.sha256 to row
        }
        val described = assets.values.flatMap { listOfNotNull(it.light, it.dark) }.groupBy { it.sha256 }
        check(described.keys == blobs.keys && described.all { (hash, variants) ->
            variants.all { it == blobs[hash]?.blob }
        }) { "ICON_STORE_CORRUPT" }
        // Re-encoding ignores incidental map-key order, not malformed/duplicate persisted keys.
        val metadataBytes = assets.values.sumOf { utf8Size(json.encodeToString(it)) } +
            packs.values.sumOf { utf8Size(json.encodeToString(it)) }
        check(metadataBytes <= 8_388_608L && blobs.values.sumOf { it.blob.byteLength.toLong() } <= 268_435_456L) {
            "ICON_STORE_CORRUPT"
        }
        return Catalog(assets, packs, blobs, metadataBytes, ready)
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
