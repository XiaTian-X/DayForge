package com.dayforge.ui.screens.settings

import android.content.Context
import android.net.Uri
import com.dayforge.data.appearance.AccountConfigExportPreview
import com.dayforge.data.appearance.ConfigBundleDocuments
import com.dayforge.data.local.LocalIconAccess
import com.dayforge.data.local.TokenManager
import com.dayforge.data.repository.*
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal enum class ConfigFileProfile { LOADING, LEGACY, NEXT, UNAVAILABLE }
internal enum class ConfigFileMessage { EXPORTED, LOCAL_COMMITTED, ABANDONED, FAILED }
internal data class ConfigV2PreviewSummary(val nodes: Int, val metrics: Int, val links: Int,
    val themes: Int, val assets: Int, val unresolvedRoles: Int, val removed: ConfigReplacementCounts?,
    val blockers: Set<ConfigReplacementBlocker>, val recovery: Boolean, val canAbandon: Boolean)
internal data class ConfigV2UiState(val profile: ConfigFileProfile = ConfigFileProfile.LOADING,
    val busy: Boolean = false, val pickerPending: Boolean = false, val canImport: Boolean = false,
    val progress: NextConfigImportProgress? = null, val preview: ConfigV2PreviewSummary? = null,
    val message: ConfigFileMessage? = null)

/** Settings owner only. Repositories own authority, transactions, original IDs and real ACKs.
 * No URI survives preview, no automatic retry/activation, no provider I/O under an account lock.
 */
class SettingsConfigV2Workflow internal constructor(
    private val tokens: TokenManager, private val exports: NextConfigExportRepository,
    private val imports: NextConfigImportRepository, private val documents: ConfigBundleDocuments
) {
    @Inject internal constructor(@ApplicationContext context: Context, tokens: TokenManager,
        exports: NextConfigExportRepository, imports: NextConfigImportRepository) :
        this(tokens, exports, imports, ConfigBundleDocuments(context.contentResolver))

    private val mutex = Mutex()
    private val mutable = MutableStateFlow(ConfigV2UiState())
    internal val state = mutable.asStateFlow()
    private var owner: LocalIconAccess? = null
    private var initialized = false
    private var export: AccountConfigExportPreview? = null
    private var picker: Pair<LocalIconAccess, String?>? = null
    private var replacement: NextConfigReplacementPreview? = null
    private var recovery: NextConfigRecoveryPreview? = null
    private var knownImportId: String? = null

    internal suspend fun observe() { exports.changes().conflate().catch { error ->
        if (error is CancellationException) throw error
        mutex.withLock {
            export = null; picker = null; replacement = null; recovery = null
            mutable.value = ConfigV2UiState(profile = ConfigFileProfile.UNAVAILABLE,
                pickerPending = mutable.value.pickerPending)
        }
    }.collect { refresh() } }
    internal suspend fun refresh() = mutex.withLock { refreshLocked() }

    /** A late v1 picker callback is not permission to reinterpret a next replica as old JSON. */
    internal suspend fun allowLegacy(): Boolean = mutex.withLock {
        refreshLocked()
        (mutable.value.profile == ConfigFileProfile.LEGACY).also { allowed ->
            if (!allowed) mutable.value = mutable.value.copy(message = ConfigFileMessage.FAILED)
        }
    }

    private suspend fun refreshLocked() {
        try {
            val current = tokens.localIconAccess()
            if (!initialized || current != owner) {
                owner = current; initialized = true; export = null; picker = null
                replacement = null; recovery = null; knownImportId = null
                // Drain an already launched picker before allowing another launch on the same
                // registry key. Its late URI must not consume a new account's prepared intent.
                mutable.value = ConfigV2UiState(pickerPending = mutable.value.pickerPending)
            }
            val next = exports.usesNextReplica()
            if (!next) {
                mutable.value = mutable.value.copy(profile = ConfigFileProfile.LEGACY, canImport = false, progress = null)
                return
            }
            check(current != null && tokens.localIconAccess() == current) { "CONFIG_SESSION_CHANGED" }
            val progress = if (current.canDeclare) imports.progress(knownImportId) else null
            check(tokens.localIconAccess() == current) { "CONFIG_SESSION_CHANGED" }
            mutable.value = mutable.value.copy(profile = ConfigFileProfile.NEXT, canImport = current.canDeclare,
                progress = progress)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            export = null; picker = null; replacement = null; recovery = null
            mutable.value = mutable.value.copy(profile = ConfigFileProfile.UNAVAILABLE, canImport = false,
                preview = null, progress = null)
        }
    }

    private suspend fun requireOwner(expected: LocalIconAccess = requireNotNull(owner)) {
        check(mutable.value.profile == ConfigFileProfile.NEXT && tokens.localIconAccess() == expected) {
            "CONFIG_SESSION_CHANGED"
        }
        check(exports.usesNextReplica()) { "CONFIG_REPLICA_CHANGED" }
    }

    /** Freeze the complete archive BEFORE opening CreateDocument, never at its later callback. */
    internal suspend fun beginExport(): Boolean = mutex.withLock {
        action {
            check(!mutable.value.pickerPending && mutable.value.preview == null)
            requireOwner()
            val frozen = exports.prepare()
            requireOwner(frozen.context.access)
            export = frozen; mutable.value = mutable.value.copy(pickerPending = true)
        }
    }

    internal suspend fun writeExport(uri: Uri?) = mutex.withLock {
        val frozen = export
        export = null; mutable.value = mutable.value.copy(pickerPending = false)
        if (uri == null) return@withLock
        action {
            check(frozen != null) { "CONFIG_EXPORT_NOT_PREPARED" }
            requireOwner(frozen.context.access)
            // Short publication is the irrevocable hand-off point. Provider writing is bounded,
            // joined, outside that lock; a later logout cannot recall externally handed-off bytes.
            val bundle = exports.publish(frozen) { it }
            documents.write(uri, bundle)
            requireOwner(frozen.context.access)
            mutable.value = mutable.value.copy(message = ConfigFileMessage.EXPORTED)
        }
    }

    internal suspend fun beginImport(recover: Boolean = false): Boolean = mutex.withLock {
        action {
            check(!mutable.value.pickerPending && mutable.value.preview == null)
            requireOwner(); check(requireNotNull(owner).canDeclare)
            val progress = imports.progress(knownImportId)
            val id = if (recover) requireNotNull(progress).also {
                check(it.phase == ConfigImportPhase.PREPARED)
            }.importId else null
            if (!recover) check(progress == null || progress.phase == ConfigImportPhase.OPERATIONS_ACCEPTED) {
                "CONFIG_IMPORT_PENDING"
            }
            picker = requireNotNull(owner) to id
            mutable.value = mutable.value.copy(pickerPending = true)
        }
    }

    internal suspend fun readImport(uri: Uri?) = mutex.withLock {
        val intent = picker; picker = null
        mutable.value = mutable.value.copy(pickerPending = false)
        if (uri == null) return@withLock
        action {
            check(intent != null) { "CONFIG_IMPORT_NOT_PREPARED" }
            requireOwner(intent.first)
            val source = documents.preview(uri) // exactly one provider open
            requireOwner(intent.first)
            val id = intent.second
            val summary = if (id == null) {
                val preview = imports.previewReplacement(source)
                check(preview.original.context.access == intent.first)
                replacement = preview
                ConfigV2PreviewSummary(source.manifest.nodes.size, source.manifest.metrics.size,
                    source.manifest.links.size, source.manifest.themes.size,
                    source.manifest.iconPack?.assets?.size ?: 0, source.manifest.unresolvedRoles.size,
                    preview.counts, preview.blockers, false, false)
            } else {
                val preview = imports.previewRecovery(id, source)
                check(preview.original.context.access == intent.first)
                recovery = preview
                ConfigV2PreviewSummary(source.manifest.nodes.size, source.manifest.metrics.size,
                    source.manifest.links.size, source.manifest.themes.size,
                    source.manifest.iconPack?.assets?.size ?: 0, source.manifest.unresolvedRoles.size,
                    null, emptySet(), true, !preview.committed)
            }
            requireOwner(intent.first)
            mutable.value = mutable.value.copy(preview = summary)
        }
    }

    internal suspend fun confirmImport() = mutex.withLock {
        val completed = action {
            val old = replacement; val restore = recovery
            check((old != null) != (restore != null)) { "CONFIG_IMPORT_NOT_PREPARED" }
            val original = old?.original ?: requireNotNull(restore).original
            requireOwner(original.context.access)
            val id = restore?.importId ?: imports.confirmReplacement(requireNotNull(old))
            knownImportId = id.takeIf { restore?.networkTracked != false } // actual durable prepare, before material I/O
            replacement = null; recovery = null; mutable.value = mutable.value.copy(preview = null)
            imports.resume(id, original.source, original.context)
            requireOwner(original.context.access)
            refreshLocked()
            requireOwner(original.context.access)
            mutable.value = mutable.value.copy(message = ConfigFileMessage.LOCAL_COMMITTED)
        }
        if (!completed) refreshLocked()
    }

    internal suspend fun abandonPrepared() = mutex.withLock {
        val completed = action {
            val preview = requireNotNull(recovery)
            requireOwner(preview.original.context.access); check(!preview.committed)
            imports.cancelPrepared(preview.importId, preview.original.source, preview.original.context)
            knownImportId = null; replacement = null; recovery = null
            refreshLocked()
            requireOwner(preview.original.context.access)
            mutable.value = mutable.value.copy(preview = null, message = ConfigFileMessage.ABANDONED)
        }
        if (!completed) refreshLocked()
    }

    internal suspend fun dismissPreview() = mutex.withLock {
        replacement = null; recovery = null; mutable.value = mutable.value.copy(preview = null)
    }
    internal suspend fun dismissMessage() = mutex.withLock { mutable.value = mutable.value.copy(message = null) }
    internal suspend fun pickerFailed() = mutex.withLock {
        export = null; picker = null
        mutable.value = mutable.value.copy(pickerPending = false, message = ConfigFileMessage.FAILED)
    }

    private suspend fun action(block: suspend () -> Unit): Boolean {
        mutable.value = mutable.value.copy(busy = true, message = null)
        return try { block(); true }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            replacement = null; recovery = null
            mutable.value = mutable.value.copy(preview = null, message = ConfigFileMessage.FAILED)
            false
        } finally { mutable.value = mutable.value.copy(busy = false) }
    }
}
