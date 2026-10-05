package com.dayforge.data.appearance

import com.dayforge.domain.model.IconPack
import java.io.InputStream
import java.util.Collections

/** Frozen provider input bound to the authenticated preview lifetime; not an installation receipt. */
internal class AccountIconPackPreview internal constructor(
    internal val context: AccountIconContext,
    internal val archive: ValidatedIconPack
) {
    val manifest: IconPack get() = archive.manifest
}

/** Local verification only. Does not prove upload, server ownership or an active display choice. */
internal data class LocalIconPackReceipt(
    val namespace: AccountIconNamespace,
    val version: IconPackVersion,
    val verifiedHashes: List<String>
)

/**
 * Authenticated import coordination. Import and applying a style are separate explicit actions:
 * nothing here changes selection, theme, business Room or server confirmation. Metadata, installation
 * reservations and transfer intents commit together before files. Failed/cancelled file work may
 * leave those durable intents, ready receipts and bytes; retries reuse the original journals.
 */
internal class AccountIconImport(
    private val metadata: AccountIconRepository,
    private val store: AccountIconStore,
    internal val transfers: AccountIconTransfers
) {
    init {
        require(store.usesMetadata(metadata) && transfers.usesStores(metadata, store)) { "ICON_IMPORT_STORE_MISMATCH" }
    }
    /** The provider supplies a blocking-I/O deadline; the archive opens/closes it exactly once. */
    suspend fun preview(openSource: () -> InputStream): AccountIconPackPreview =
        previewArchive { ValidatedIconPack.read(openSource) }

    /** Capture authorization before opening the provider, not after consuming its bytes. */
    internal suspend fun previewArchive(readArchive: suspend () -> ValidatedIconPack): AccountIconPackPreview {
        val context = metadata.capture()
        val archive = readArchive()
        // Check after IO has resumed the caller too, not merely before queuing its return.
        return metadata.authorized(context) { AccountIconPackPreview(context, archive) }
    }

    suspend fun confirm(preview: AccountIconPackPreview): LocalIconPackReceipt {
        val context = preview.context
        val pack = preview.manifest
        metadata.reauthorize(context, writing = true)
        // Whole metadata/quota/intent reservation is one transaction, before any file publication.
        transfers.reserveAndEnqueuePack(context, pack)
        val variants = pack.assets.flatMap { asset ->
            listOfNotNull(asset.light, asset.dark).map { asset.assetId to it }
        }.distinctBy { it.second.sha256 }
        // A successful earlier ready transaction is not a final integrity proof. Read all actual
        // variants again under the pack lease, including unused assets, before returning success.
        store.installPack(context, pack, preview.archive::readBlob)
        check(metadata.pack(context, pack.packId, pack.revision) == pack) { "ICON_PACK_NOT_OWNED" }
        metadata.reauthorize(context, writing = true)
        return metadata.authorized(context) {
            LocalIconPackReceipt(context.namespace, IconPackVersion(pack.packId, pack.revision),
                Collections.unmodifiableList(variants.map { it.second.sha256 }))
        }
    }
}
