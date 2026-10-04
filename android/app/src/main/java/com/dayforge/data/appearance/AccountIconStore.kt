package com.dayforge.data.appearance

import java.io.IOException
import java.util.Collections
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class IconFileRecovery(val ready: Int, val pending: Int, val unknownFiles: List<String>)

/** Inactive local installation boundary. Never touches business Room, themes, UI or online queues. */
internal class AccountIconStore(private val metadata: AccountIconRepository, private val files: AccountIconFiles) {
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
            target.capacity(frozen.size, hash)
            val profile = target.files.publish(before.reservation.operationId, frozen, before.reservation.blob)
            // Read the final file, not just the temporary writer's handle, before the ready transaction.
            target.files.read(before.reservation.blob, profile)
            currentCoroutineContext().ensureActive()
            metadata.markReady(context, assetId, before.reservation, profile)
        }
        metadata.reauthorize(context, writing = true)
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

    /** Retry only this proven installation intent, never another import's temporary or final bytes. */
    suspend fun recoverInstallation(context: AccountIconContext, assetId: String, hash: String) {
        metadata.installation(context, assetId, hash, writing = true)
        files.exclusive(context.namespace, create = false, beforeAccess = { metadata.reauthorize(context, writing = true) }) { directory ->
            val before = metadata.installation(context, assetId, hash, writing = true)
            val names = directory?.inventory().orEmpty()
            if (before.validationProfile != null || hash in names) {
                (directory ?: throw IOException("ICON_FILES_MISSING")).files.read(before.reservation.blob,
                    before.validationProfile ?: iconValidationProfile(before.reservation.blob))
            }
            check(metadata.installation(context, assetId, hash, writing = true) == before) { "ICON_INSTALL_CHANGED" }
            // A failure never deletes the immutable final file or manufactures a ready receipt.
            directory?.files?.cleanupTemporary(before.reservation.operationId)
            check(metadata.installation(context, assetId, hash, writing = true) == before) { "ICON_INSTALL_CHANGED" }
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
