package com.dayforge.data.appearance

import com.dayforge.domain.model.ConfigBundle
import com.dayforge.domain.model.IconPack
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Frozen file plus its original authority. Not an import receipt, account claim or business snapshot. */
internal class AccountConfigExportPreview internal constructor(
    internal val context: AccountIconContext,
    internal val selection: AccountIconSelection,
    internal val bundle: ValidatedConfigBundle
)

/** Read-only offline export: no declaration, download, selection change or business mutation. */
internal class AccountConfigExport(private val metadata: AccountIconRepository, private val store: AccountIconStore) {
    init { require(store.usesMetadata(metadata)) { "ICON_EXPORT_STORE_MISMATCH" } }

    /** The caller must supply a separately authorized, consistent typed business snapshot. */
    suspend fun prepare(context: AccountIconContext, uses: List<ConfigIconUse>,
        manifest: (IconPack?, List<String>) -> ConfigBundle): AccountConfigExportPreview {
        require(uses.size <= 2000)
        val frozenUses = uses.toList()
        val dependencies = metadata.configDependencies(context, frozenUses)
        // This ID describes only a file-local dependency snapshot; it is never reserved in Room.
        val pack = dependencies.assets.takeIf { it.isNotEmpty() }?.let {
            IconPack("dayforge.icon-pack", 1, UUID.randomUUID().toString(), 1,
                "Configuration icons", it, dependencies.roles, null)
        }
        val source = manifest(pack, dependencies.unresolvedRoles)
        check(source.iconPack == pack && source.unresolvedRoles == dependencies.unresolvedRoles) {
            "CONFIG_ICON_SNAPSHOT_CHANGED"
        }
        check(usesIn(source) == frozenUses) { "CONFIG_ICON_REFERENCES_CHANGED" }
        val bundle = ConfigBundleOutput.create(source) { blob ->
            val owned = dependencies.blobs.getValue(blob.sha256)
            check(owned.installation.reservation.blob == blob) { "CONFIG_ICON_SNAPSHOT_CHANGED" }
            store.read(context, owned.assetId, blob.sha256)
        }
        currentCoroutineContext().ensureActive()
        // ConfigBundle can arrive with caller-owned mutable collections. Verify the actual frozen
        // manifest too, not only the pre-serialization object or its metadata equality.
        check(bundle.manifest.iconPack == pack && bundle.manifest.unresolvedRoles == dependencies.unresolvedRoles) {
            "CONFIG_ICON_SNAPSHOT_CHANGED"
        }
        check(usesIn(bundle.manifest) == frozenUses) { "CONFIG_ICON_REFERENCES_CHANGED" }
        // Source bytes are immutable. Recheck selection, owned declarations and all receipts after I/O.
        check(metadata.configDependencies(context, frozenUses) == dependencies) { "CONFIG_ICON_SNAPSHOT_CHANGED" }
        return metadata.withSelection(context, dependencies.selection) {
            AccountConfigExportPreview(context, dependencies.selection, bundle)
        }
    }

    /** Short, non-suspending publication only; never hold the account/Room lock across provider I/O. */
    suspend fun <T> publish(preview: AccountConfigExportPreview, block: (ValidatedConfigBundle) -> T): T =
        metadata.withSelection(preview.context, preview.selection) { block(preview.bundle) }

    private fun usesIn(bundle: ConfigBundle) = bundle.nodes.map {
        ConfigIconUse(it.appearance.icon, it.activity?.completionPolicy == "one_and_done")
    } + bundle.metrics.map { ConfigIconUse(it.appearance.icon, false) }
}
