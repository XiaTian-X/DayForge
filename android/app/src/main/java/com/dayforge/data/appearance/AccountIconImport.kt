package com.dayforge.data.appearance

import com.dayforge.domain.model.IconPack
import java.io.InputStream
import java.util.Collections
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

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
 * nothing here changes selection, theme, business Room or online queues. Failed/cancelled work may
 * have durable metadata, ready receipts and bytes; immutable retries reuse the original journals.
 */
internal class AccountIconImport(
    private val metadata: AccountIconRepository,
    private val store: AccountIconStore
) {
    /** The provider supplies a blocking-I/O deadline; the archive opens/closes it exactly once. */
    suspend fun preview(openSource: () -> InputStream): AccountIconPackPreview {
        val context = metadata.capture()
        val archive = ValidatedIconPack.read(openSource)
        // Check after IO has resumed the caller too, not merely before queuing its return.
        return metadata.authorized(context) { AccountIconPackPreview(context, archive) }
    }

    suspend fun confirm(preview: AccountIconPackPreview): LocalIconPackReceipt {
        val context = preview.context
        val pack = preview.manifest
        metadata.reauthorize(context, writing = true)
        // Whole metadata/quota/intent reservation is one transaction, before any file publication.
        metadata.reservePack(context, pack)
        val variants = pack.assets.flatMap { asset ->
            listOfNotNull(asset.light, asset.dark).map { asset.assetId to it }
        }.distinctBy { it.second.sha256 }
        for ((assetId, blob) in variants) {
            currentCoroutineContext().ensureActive()
            val bytes = preview.archive.readBlob(blob.sha256)
            store.recoverInstallation(context, assetId, blob.sha256)
            store.install(context, assetId, blob.sha256, bytes)
        }
        // A successful earlier ready transaction is not a final integrity proof. Read all actual
        // variants again, including unreferenced assets; never report a partially installed pack.
        for ((assetId, blob) in variants) {
            currentCoroutineContext().ensureActive()
            store.read(context, assetId, blob.sha256)
        }
        check(metadata.pack(context, pack.packId, pack.revision) == pack) { "ICON_PACK_NOT_OWNED" }
        metadata.reauthorize(context, writing = true)
        return metadata.authorized(context) {
            LocalIconPackReceipt(context.namespace, IconPackVersion(pack.packId, pack.revision),
                Collections.unmodifiableList(variants.map { it.second.sha256 }))
        }
    }
}
