package com.dayforge.data.appearance

import com.dayforge.domain.model.IconPack
import java.io.IOException
import java.util.Collections
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class IconFileRecovery(val ready: Int, val pending: Int, val unknownFiles: List<String>)

/** Inactive local installation boundary. Never touches business Room, themes, UI or online queues. */
internal class AccountIconStore(private val metadata: AccountIconRepository, private val files: AccountIconFiles) {
    internal fun usesMetadata(value: AccountIconRepository): Boolean = metadata === value
    /** A local display choice, not a structural write. Validate the whole pack, not only visible icons. */
    suspend fun select(context: AccountIconContext, expectedGeneration: Long,
        version: IconPackVersion?): AccountIconSelection {
        require(expectedGeneration >= 0)
        metadata.reauthorize(context)
        val result = if (version == null) {
            metadata.selectValidated(context, expectedGeneration, null, emptyMap())
        } else files.exclusive(context.namespace, create = false, beforeAccess = { metadata.reauthorize(context) }) { directory ->
            val pack = requireNotNull(metadata.pack(context, version.packId, version.revision)) { "ICON_PACK_NOT_OWNED" }
            check(metadata.selection(context).generation == expectedGeneration) { "ICON_SELECTION_CHANGED" }
            val receipts = linkedMapOf<String, IconInstallation>()
            for (asset in pack.assets) for (blob in listOfNotNull(asset.light, asset.dark)) {
                currentCoroutineContext().ensureActive()
                val receipt = metadata.installation(context, asset.assetId, blob.sha256)
                val profile = requireNotNull(receipt.validationProfile) { "ICON_NOT_READY" }
                if (blob.sha256 !in receipts) {
                    (directory ?: throw IOException("ICON_FILES_MISSING")).files.read(blob, profile)
                    receipts[blob.sha256] = receipt
                } else check(receipts[blob.sha256] == receipt) { "ICON_INSTALL_CHANGED" }
            }
            metadata.selectValidated(context, expectedGeneration, pack, receipts)
        }
        // A concurrent choice can commit while withContext queues delivery back to the caller.
        return metadata.withSelection(context, result) { result }
    }

    suspend fun install(context: AccountIconContext, assetId: String, hash: String, content: ByteArray) {
        require(content.size in 1..2_097_152)
        val frozen = content.copyOf()
        // Authorize before creating paths; recheck after waiting for the process file lease.
        metadata.installation(context, assetId, hash, writing = true)
        files.exclusive(context.namespace, create = true, beforeAccess = { metadata.reauthorize(context, writing = true) }) { directory ->
            val before = metadata.installation(context, assetId, hash, writing = true)
            val target = requireNotNull(directory)
            // A ready receipt cannot authorize silently rebuilding a missing/corrupted final file.
            before.validationProfile?.let { target.files.read(before.reservation.blob, it) }
            publishReady(context, assetId, before, frozen, target)
        }
        metadata.reauthorize(context, writing = true)
    }

    /** Caller holds the namespace lease; both single and pack installs use the same publication proof. */
    private suspend fun publishReady(context: AccountIconContext, assetId: String, before: IconInstallation,
        frozen: ByteArray, target: AccountIconFiles.Directory) {
        target.capacity(frozen.size, before.reservation.blob.sha256)
        val profile = target.files.publish(before.reservation.operationId, frozen, before.reservation.blob)
        target.files.read(before.reservation.blob, profile)
        currentCoroutineContext().ensureActive()
        metadata.markReady(context, assetId, before.reservation, profile)
    }

    suspend fun read(context: AccountIconContext, assetId: String, hash: String): ByteArray {
        metadata.installation(context, assetId, hash)
        val result = files.exclusive(context.namespace, create = false, beforeAccess = { metadata.reauthorize(context) }) { directory ->
            val before = metadata.installation(context, assetId, hash)
            val profile = requireNotNull(before.validationProfile) { "ICON_NOT_READY" }
            val bytes = (directory ?: throw IOException("ICON_FILES_MISSING")).files.read(before.reservation.blob, profile)
            check(metadata.installation(context, assetId, hash) == before) { "ICON_INSTALL_CHANGED" }
            bytes
        }
        // withContext(IO) can wait for the caller dispatcher after its last in-file session check.
        metadata.reauthorize(context)
        return result
    }

    /** Full pack integrity, not per-image render cache readiness or a remote receipt. */
    suspend fun verifyPack(context: AccountIconContext, pack: IconPack) {
        metadata.reauthorize(context)
        files.exclusive(context.namespace, create = false, beforeAccess = { metadata.reauthorize(context) }) { directory ->
            verifyDirectory(context, pack, directory)
        }
        metadata.reauthorize(context)
    }

    private suspend fun verifyDirectory(context: AccountIconContext, pack: IconPack,
        directory: AccountIconFiles.Directory?) {
        val before = metadata.packInstallations(context, pack)
        for (receipt in before.values) {
            currentCoroutineContext().ensureActive()
            metadata.reauthorize(context)
            (directory ?: throw IOException("ICON_FILES_MISSING")).files.read(receipt.reservation.blob,
                requireNotNull(receipt.validationProfile))
        }
        check(metadata.packInstallations(context, pack) == before) { "ICON_INSTALL_CHANGED" }
    }

    /** One namespace lease/durable directory creation for the pack, never one per image. */
    suspend fun installPack(context: AccountIconContext, pack: IconPack, source: suspend (String) -> ByteArray) {
        metadata.reauthorize(context, writing = true)
        val reserved = metadata.packInstallations(context, pack, requireReady = false)
        // Check existing ready/pending final files WITHOUT creating a missing namespace first.
        // Corruption stops recovery before any temporary cleanup, including on exact replay.
        files.exclusive(context.namespace, create = false, beforeAccess = { metadata.reauthorize(context, writing = true) }) { directory ->
            val before = metadata.packInstallations(context, pack, requireReady = false)
            check(before.mapValues { it.value.reservation } == reserved.mapValues { it.value.reservation }) { "ICON_INSTALL_CHANGED" }
            val names = directory?.inventory().orEmpty()
            for ((hash, receipt) in before) {
                currentCoroutineContext().ensureActive()
                metadata.reauthorize(context, writing = true)
                if (receipt.validationProfile != null || hash in names) {
                    (directory ?: throw IOException("ICON_FILES_MISSING")).files.read(receipt.reservation.blob,
                        receipt.validationProfile ?: iconValidationProfile(receipt.reservation.blob))
                }
            }
            check(metadata.packInstallations(context, pack, requireReady = false) == before) { "ICON_INSTALL_CHANGED" }
        }
        val variants = pack.assets.flatMap { asset -> listOfNotNull(asset.light, asset.dark).map { asset.assetId to it } }
            .distinctBy { it.second.sha256 }
        files.exclusive(context.namespace, create = true, beforeAccess = { metadata.reauthorize(context, writing = true) }) { directory ->
            val target = requireNotNull(directory)
            for ((assetId, blob) in variants) {
                currentCoroutineContext().ensureActive()
                val before = metadata.installation(context, assetId, blob.sha256, writing = true)
                check(before.reservation == reserved[blob.sha256]?.reservation) { "ICON_INSTALL_CHANGED" }
                val content = source(blob.sha256)
                require(content.size == blob.byteLength)
                val frozen = content.copyOf()
                val names = target.inventory()
                if (before.validationProfile != null || blob.sha256 in names) {
                    target.files.read(blob, before.validationProfile ?: iconValidationProfile(blob))
                }
                check(metadata.installation(context, assetId, blob.sha256, writing = true) == before) { "ICON_INSTALL_CHANGED" }
                target.files.cleanupTemporary(before.reservation.operationId)
                check(metadata.installation(context, assetId, blob.sha256, writing = true) == before) { "ICON_INSTALL_CHANGED" }
                publishReady(context, assetId, before, frozen, target)
            }
            verifyDirectory(context, pack, target)
        }
        metadata.reauthorize(context, writing = true)
    }

    /** Does not infer ready from final bytes, remove final hashes, or adopt unjournalled files. */
    suspend fun recover(context: AccountIconContext): IconFileRecovery {
        metadata.installations(context)
        val result = files.exclusive(context.namespace, create = false, beforeAccess = { metadata.reauthorize(context, writing = true) }) { directory ->
            val entries = metadata.installations(context)
            val names = directory?.inventory().orEmpty()
            // Validate the entire known final-file set before any cleanup, including pending publications.
            for (entry in entries) {
                currentCoroutineContext().ensureActive()
                val hash = entry.reservation.blob.sha256
                if (entry.validationProfile != null || hash in names) {
                    (directory ?: throw IOException("ICON_FILES_MISSING")).files.read(entry.reservation.blob,
                        entry.validationProfile ?: iconValidationProfile(entry.reservation.blob))
                }
            }
            check(metadata.installations(context) == entries) { "ICON_INSTALL_CHANGED" }
            for (entry in entries) {
                currentCoroutineContext().ensureActive()
                // Reauthorize every exact cleanup; no account lock spans blocking image I/O.
                metadata.reauthorize(context, writing = true)
                directory?.files?.cleanupTemporary(entry.reservation.operationId)
            }
            check(metadata.installations(context) == entries) { "ICON_INSTALL_CHANGED" }
            val known = entries.flatMap { listOf(it.reservation.blob.sha256, ".install-${it.reservation.operationId}.part") }.toSet()
            IconFileRecovery(entries.count { it.validationProfile != null }, entries.count { it.validationProfile == null },
                Collections.unmodifiableList((names - known).sorted()))
        }
        metadata.reauthorize(context, writing = true)
        return result
    }
}
