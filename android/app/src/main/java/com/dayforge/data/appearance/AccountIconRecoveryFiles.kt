package com.dayforge.data.appearance

import java.io.IOException
import java.security.MessageDigest
import java.util.Collections
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** A content witness only. Partial files remain partial, even when their hash matches final bytes. */
internal data class IconRecoveryFile(
    val name: String, val blobHash: String, val operationId: String, val temporary: Boolean,
    val byteLength: Int, val contentHash: String
)

internal data class AccountIconRecoverySnapshot(
    val namespace: AccountIconNamespace, val metadata: AccountIconRecoveryMetadata,
    val files: List<IconRecoveryFile>
) {
    val activationAuthorized: Boolean get() = false
}

/** Valid only during the lease callback, never a path or an escaping open file descriptor. */
internal class AccountIconRecoveryFileAccess internal constructor(
    private val readFile: suspend (String) -> ByteArray
) {
    @Volatile private var open = true
    internal fun close() { open = false }
    suspend fun read(name: String): ByteArray {
        check(open) { "ICON_RECOVERY_ACCESS_CLOSED" }
        val bytes = readFile(name)
        check(open) { "ICON_RECOVERY_ACCESS_CLOSED" }
        return bytes
    }
}

/**
 * Read-only full old-namespace file closure, NOT the repair/cleanup in AccountIconStore.recover.
 * No path creation, deletion, ready adoption, transfer ACK or new-scope activation. Bounded to one
 * blob in memory; the consumer can stream into a separate private archive while the lease is held.
 * It must not install/clean files or acquire the file lease recursively in the callback. Metadata
 * transactions are short and outside all file I/O, in the existing file -> account lock order.
 * Full raw SQL/schema/transfer receipts and durable archive verification are separate requirements.
 */
internal class AccountIconRecoveryFiles(
    private val metadata: AccountIconRepository, private val files: AccountIconFiles
) {
    suspend fun <T> inspect(context: AccountIconContext,
        block: suspend (AccountIconRecoverySnapshot, AccountIconRecoveryFileAccess) -> T): T {
        metadata.reauthorize(context)
        val result = files.exclusive(context.namespace, create = false,
            beforeAccess = { metadata.reauthorize(context) }) { directory ->
            val before = metadata.recoveryMetadata(context)
            val inventory = directory?.inventory().orEmpty()
            val owned = linkedMapOf<String, Pair<IconInstallation, Boolean>>()
            for (installation in before.installations) {
                val reservation = installation.reservation
                owned[reservation.blob.sha256] = installation to false
                owned[".install-${reservation.operationId}.part"] = installation to true
                check(installation.validationProfile == null || reservation.blob.sha256 in inventory) {
                    "ICON_RECOVERY_READY_FILE_MISSING"
                }
            }
            check(inventory.all { it in owned }) { "ICON_RECOVERY_UNKNOWN_FILE" }
            suspend fun readOwned(name: String): ByteArray {
                currentCoroutineContext().ensureActive()
                metadata.reauthorize(context)
                val (installation, temporary) = requireNotNull(owned[name]) { "ICON_RECOVERY_FILE_NOT_OWNED" }
                check(name in inventory) { "ICON_RECOVERY_FILE_NOT_PRESENT" }
                val source = directory ?: throw IOException("ICON_FILES_MISSING")
                val bytes = if (temporary) {
                    val coroutine = currentCoroutineContext()
                    source.files.readRecoveryTemporary(installation.reservation.operationId,
                        installation.reservation.blob.byteLength) { coroutine.ensureActive() }
                } else source.files.read(installation.reservation.blob,
                    installation.validationProfile ?: iconValidationProfile(installation.reservation.blob))
                currentCoroutineContext().ensureActive()
                metadata.reauthorize(context)
                return bytes
            }
            val witnesses = inventory.sorted().map { name ->
                val (installation, temporary) = owned.getValue(name)
                val bytes = readOwned(name)
                IconRecoveryFile(name, installation.reservation.blob.sha256,
                    installation.reservation.operationId, temporary, bytes.size, hash(bytes))
            }
            suspend fun verifyMetadataAndNames() {
                check(metadata.recoveryMetadata(context) == before) { "ICON_RECOVERY_METADATA_CHANGED" }
                check(directory?.inventory().orEmpty() == inventory) { "ICON_RECOVERY_INVENTORY_CHANGED" }
            }
            verifyMetadataAndNames()
            val byName = witnesses.associateBy { it.name }
            val access = AccountIconRecoveryFileAccess { name ->
                val witness = requireNotNull(byName[name]) { "ICON_RECOVERY_FILE_NOT_CAPTURED" }
                readOwned(name).also { bytes ->
                    check(bytes.size == witness.byteLength && hash(bytes) == witness.contentHash) {
                        "ICON_RECOVERY_CONTENT_CHANGED"
                    }
                }
            }
            try {
                val value = block(AccountIconRecoverySnapshot(context.namespace, before,
                    Collections.unmodifiableList(witnesses)), access)
                // Verify the actual closure again, not just file names or stale ready receipts.
                verifyMetadataAndNames()
                for (witness in witnesses) access.read(witness.name)
                verifyMetadataAndNames()
                value
            } finally { access.close() }
        }
        // Dispatch back to a caller can wait after the final in-file check.
        metadata.reauthorize(context)
        return result
    }

    private fun hash(bytes: ByteArray): String {
        val digits = "0123456789abcdef"
        return buildString(64) {
            for (byte in MessageDigest.getInstance("SHA-256").digest(bytes)) {
                val value = byte.toInt() and 255
                append(digits[value ushr 4]); append(digits[value and 15])
            }
        }
    }
}
