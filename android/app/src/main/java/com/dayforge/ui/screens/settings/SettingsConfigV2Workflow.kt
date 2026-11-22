package com.dayforge.ui.screens.settings

import android.content.Context
import android.net.Uri
import com.dayforge.data.appearance.AccountConfigExportPreview
import com.dayforge.data.appearance.ConfigBundleDocuments
import com.dayforge.data.local.DatabaseEpochPublication
import com.dayforge.data.local.DatabaseRuntimeHost
import com.dayforge.data.local.LocalIconAccess
import com.dayforge.data.local.TokenManager
import com.dayforge.data.repository.*
import com.dayforge.domain.appearance.ThemeVersionRef
import com.dayforge.domain.model.ThemeDefinition
import com.dayforge.domain.service.DeviceThemeController
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal enum class ConfigFileProfile { LOADING, LEGACY, NEXT, UNAVAILABLE }
internal enum class ConfigFileMessage { EXPORTED, LOCAL_COMMITTED, ABANDONED, FAILED }
internal data class ConfigV2PreviewSummary(val nodes: Int, val metrics: Int, val links: Int,
    val themes: Int, val assets: Int, val unresolvedRoles: Int, val removed: ConfigReplacementCounts?,
    val blockers: Set<ConfigReplacementBlocker>, val recovery: Boolean, val canAbandon: Boolean,
    val themeChoices: List<ThemeDefinition> = emptyList())
internal data class ConfigExportChoices(val items: List<Pair<String, String>>, val themes: List<ThemeDefinition>)
internal class ConfigFileSource internal constructor(val business: DatabaseEpochPublication,
    val control: DatabaseEpochPublication)
/** Identity only: picker callbacks never carry an export, URI, account or new authority. */
internal class ConfigFilePicker internal constructor(internal val source: ConfigFileSource?, internal val recovery: Boolean)
internal data class ConfigV2UiState(val profile: ConfigFileProfile = ConfigFileProfile.LOADING,
    val busy: Boolean = false, val pickerPending: Boolean = false, val canImport: Boolean = false,
    val progress: NextConfigImportProgress? = null, val preview: ConfigV2PreviewSummary? = null,
    val message: ConfigFileMessage? = null, val exportChoices: ConfigExportChoices? = null,
    val selectedItems: Set<String> = emptySet(), val selectedThemes: Set<ThemeVersionRef> = emptySet(),
    val source: ConfigFileSource? = null, val request: Any = Any(), val businessAvailable: Boolean = true)

/** Graph lifetime restrictions supplement the repositories' original fresh authorization.
 * Frozen dialogs/pickers retain the original source and repository preview. No source is
 * recaptured at confirmation; provider I/O remains outside account and Room locks.
 */
class SettingsConfigV2Workflow private constructor(
    private val tokens: TokenManager, private val fixedExports: NextConfigExportRepository?,
    private val fixedImports: NextConfigImportRepository?, private val documents: ConfigBundleDocuments,
    private val themes: DeviceThemeController?, private val host: DatabaseRuntimeHost?
) {
    internal constructor(tokens: TokenManager, exports: NextConfigExportRepository,
        imports: NextConfigImportRepository, documents: ConfigBundleDocuments, themes: DeviceThemeController? = null) :
        this(tokens, exports, imports, documents, themes, null)
    @Inject internal constructor(@ApplicationContext context: Context, tokens: TokenManager,
        host: DatabaseRuntimeHost, themes: DeviceThemeController) :
        this(tokens, null, null, ConfigBundleDocuments(context.contentResolver), themes, host)

    private val exports get() = fixedExports ?: requireNotNull(host).boundGraph().nextConfigExportRepository
    private val imports get() = fixedImports ?: requireNotNull(host).boundGraph().nextConfigImportRepository
    private val mutex = Mutex()
    private val monitor = Any()
    private val mutable = MutableStateFlow(ConfigV2UiState())
    internal val state = mutable.asStateFlow()
    private class Running(val source: ConfigFileSource?, val request: Any, val recovery: Boolean)
    private data class Session(val ui: ConfigV2UiState = ConfigV2UiState(), val owner: LocalIconAccess? = null,
        val initialized: Boolean = false, val export: AccountConfigExportPreview? = null,
        val options: NextConfigExportRepository.Options? = null, val picker: ConfigFilePicker? = null,
        val importIntent: Pair<LocalIconAccess, String?>? = null,
        val replacement: NextConfigReplacementPreview? = null, val recovery: NextConfigRecoveryPreview? = null,
        val knownImportId: String? = null, val busyOwner: Running? = null)
    private var session = Session()
    private fun snapshot(): Session = synchronized(monitor) { session }
    private fun assign(next: Session) { session = next; mutable.value = next.ui }
    private fun clear() = synchronized(monitor) {
        // Keep only the opaque outstanding picker identity to drain its registry result.
        assign(Session(ui = ConfigV2UiState(profile = ConfigFileProfile.UNAVAILABLE,
            pickerPending = session.picker != null, businessAvailable = false), picker = session.picker))
    }
    /** A failed read revokes every visible result. The exact owned import ID is only a
     * private lookup hint: next refresh still compares owner/source and fully audits it.
     * Token/Host invalidation uses clear(), which never retains this hint. */
    private fun hideFailedRead() {
        val before = snapshot()
        val retained = publish(before.ui.source, recovery = true) { current ->
            if (current !== before) current else Session(
                ui = ConfigV2UiState(profile = ConfigFileProfile.UNAVAILABLE, busy = current.ui.busy,
                    pickerPending = current.picker != null, source = current.ui.source, businessAvailable = false),
                owner = current.owner, initialized = current.initialized, picker = current.picker,
                knownImportId = current.knownImportId, busyOwner = current.busyOwner)
        }
        if (!retained) synchronized(monitor) { if (session === before) clear() }
    }

    private val listener = object : DatabaseRuntimeHost.MemoryListener {
        override fun transition(blocked: Boolean) {
            val before = snapshot()
            if (blocked || before.ui.source?.control?.isCurrentInMemory() != true) {
                synchronized(monitor) { if (session.ui.source === before.ui.source) clear() }
            } else if (before.ui.source?.business?.isCurrentInMemory() != true) {
                synchronized(monitor) { if (session.ui.source === before.ui.source) {
                    val current = session
                    // Recovery can start after the listener's outside snapshot. Recheck its
                    // current request under the same monitor before clearing business data.
                    val preparingRecovery = current.busyOwner?.let {
                        it.recovery && it.source === current.ui.source && it.request === current.ui.request
                    } == true
                    if (preparingRecovery || current.ui.preview?.recovery == true || current.picker?.recovery == true) {
                        // CONTROL still owns this exact request, including the preparation
                        // window before a picker/preview exists. This marker grants no access.
                        if (current.ui.businessAvailable)
                            assign(current.copy(ui = current.ui.copy(businessAvailable = false)))
                    } else {
                        val revoked = current.copy(export = null, options = null, importIntent = null, replacement = null,
                            ui = current.ui.copy(preview = null, exportChoices = null, selectedItems = emptySet(),
                                selectedThemes = emptySet(), businessAvailable = false))
                        // Repeated reserve/defer hints do not revoke the same empty business
                        // state again and again, or replace a still-valid CONTROL request.
                        if (revoked != current) assign(revoked.copy(ui = revoked.ui.copy(request = Any())))
                    }
                } }
            }
        }
    }
    @Suppress("unused") private val registration = host?.observeMemory(listener)

    /** The source fence is acquired before the producer monitor and Host publication lock. */
    private fun publish(source: ConfigFileSource?, recovery: Boolean = false, change: (Session) -> Session): Boolean {
        if (host == null) { synchronized(monitor) { assign(change(session)) }; return true }
        val publication = (if (recovery) source?.control else source?.business) ?: return false
        return publication.withSourceFence {
            synchronized(monitor) { publication.publish { assign(change(session)) } }
        }
    }
    private fun matches(frame: ConfigV2UiState, current: Session): Boolean =
        frame.source === current.ui.source && frame.request === current.ui.request
    private fun edit(frame: ConfigV2UiState, recovery: Boolean = false, change: (Session) -> Session): Boolean {
        var changed = false
        publish(frame.source, recovery) { current ->
            if (!matches(frame, current)) current else change(current).also { changed = true }
        }
        return changed
    }
    private fun fixedFrame(): ConfigV2UiState { check(host == null) { "CONFIG_ORIGINAL_INPUT_REQUIRED" }; return snapshot().ui }
    private suspend fun <T> inSource(source: ConfigFileSource?, recovery: Boolean, block: suspend () -> T): T =
        if (host == null) block() else if (recovery) host.withControlSourceGraph(source?.control) { block() }
        else host.withSourceGraph(source?.business) { block() }
    private suspend fun <T> control(block: suspend () -> T): T =
        if (host == null) block() else host.withGraph(DatabaseRuntimeHost.Work.CONTROL) { block() }

    internal suspend fun observe() {
        val changes = if (host == null) exports.changes() else merge(
            host.controlHints { it.nextConfigExportRepository.changes() }, host.controlChanges)
        changes.conflate().catch { error ->
            if (error is CancellationException) throw error
            hideFailedRead()
        }.collect { refresh() }
    }
    internal suspend fun refresh() = mutex.withLock { refreshLocked() }
    private suspend fun refreshLocked() {
        try { control {
            val before = snapshot()
            val current = tokens.localIconAccess()
            val source = if (host == null) null else before.ui.source?.takeIf { it.control.isCurrentInMemory() }
                ?: ConfigFileSource(host.currentPublication(), host.currentControlPublication())
            val reset = !before.initialized || current != before.owner || before.ui.source !== source
            val nextReplica = exports.usesNextReplica()
            if (nextReplica) check(current != null && tokens.localIconAccess() == current) { "CONFIG_SESSION_CHANGED" }
            val progress = if (nextReplica && current?.canDeclare == true)
                imports.progress(if (reset) null else before.knownImportId) else null
            if (nextReplica) check(tokens.localIconAccess() == current) { "CONFIG_SESSION_CHANGED" }
            val business = host == null || source?.business?.isCurrentInMemory() == true
            publish(source, recovery = true) { latest ->
                val base = if (reset) Session(ui = ConfigV2UiState(pickerPending = latest.picker != null,
                    source = source), picker = latest.picker) else latest
                base.copy(owner = current, initialized = true, ui = base.ui.copy(source = source,
                    profile = if (nextReplica) ConfigFileProfile.NEXT else ConfigFileProfile.LEGACY,
                    canImport = nextReplica && current?.canDeclare == true, progress = progress,
                    businessAvailable = business))
            }
        } } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { hideFailedRead() }
    }
    internal suspend fun allowLegacy(): Boolean = mutex.withLock {
        fixedFrame(); refreshLocked()
        (snapshot().ui.profile == ConfigFileProfile.LEGACY).also { allowed ->
            if (!allowed) publish(null) { it.copy(ui = it.ui.copy(message = ConfigFileMessage.FAILED)) }
        }
    }
    internal suspend fun allowLegacyFrom(frame: ConfigV2UiState): Boolean = mutex.withLock {
        try { inSource(frame.source, false) {
            val before = snapshot()
            matches(frame, before) && frame.profile == ConfigFileProfile.LEGACY &&
                tokens.localIconAccess() == before.owner && !exports.usesNextReplica()
        } } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { false }
    }
    private suspend fun requireOwner(expected: LocalIconAccess) {
        check(snapshot().ui.profile == ConfigFileProfile.NEXT && tokens.localIconAccess() == expected) { "CONFIG_SESSION_CHANGED" }
        check(exports.usesNextReplica()) { "CONFIG_REPLICA_CHANGED" }
    }
    private suspend fun action(frame: ConfigV2UiState, recovery: Boolean = false,
        afterSource: (suspend (Session, Running) -> Unit)? = null,
        block: suspend (Session) -> Unit): Boolean {
        val before = snapshot()
        if (!matches(frame, before) || before.ui.busy || frame.profile != ConfigFileProfile.NEXT) return false
        val running = Running(frame.source, frame.request, recovery)
        if (!edit(frame, recovery) { it.copy(busyOwner = running, ui = it.ui.copy(busy = true, message = null)) }) return false
        return try {
            inSource(frame.source, recovery) {
                requireOwner(requireNotNull(before.owner))
                block(before)
            }
            // The original business lease is finished before CONTROL refresh. Keep this
            // exact Running busy until the original source's final result publication.
            afterSource?.invoke(before, running)
            true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            publish(frame.source, recovery) { current -> if (current.busyOwner !== running) current else
                current.copy(replacement = null, recovery = null, options = null,
                    ui = current.ui.copy(preview = null, exportChoices = null, selectedItems = emptySet(),
                        selectedThemes = emptySet(), message = ConfigFileMessage.FAILED, request = Any())) }
            false
        } finally {
            // Pure bookkeeping for this accepted operation remains releasable when its
            // business source retires. Original CONTROL source never publishes its result.
            publish(frame.source, recovery = true) { current -> if (current.busyOwner !== running) current else
                current.copy(busyOwner = null, ui = current.ui.copy(busy = false)) }
        }
    }
    internal suspend fun openExportChoices() = openExportChoicesFrom(fixedFrame())
    internal suspend fun openExportChoicesFrom(frame: ConfigV2UiState) = mutex.withLock {
        action(frame) { before ->
            check(!before.ui.pickerPending && before.ui.preview == null && before.options == null)
            val options = exports.options(); requireOwner(options.context.access)
            check(edit(frame) { it.copy(options = options, ui = it.ui.copy(request = Any(),
                exportChoices = ConfigExportChoices(options.completedItems, options.themes),
                selectedItems = emptySet(), selectedThemes = emptySet())) })
        }
    }
    internal suspend fun selectItem(id: String, checked: Boolean) = selectItemFrom(fixedFrame(), id, checked)
    internal suspend fun selectItemFrom(frame: ConfigV2UiState, id: String, checked: Boolean) = mutex.withLock {
        edit(frame) { current ->
            if (current.ui.busy || current.options?.completedItems?.none { it.first == id } != false) current else
                current.copy(ui = current.ui.copy(selectedItems = if (checked) current.ui.selectedItems + id else current.ui.selectedItems - id))
        }; Unit
    }
    internal suspend fun selectTheme(ref: ThemeVersionRef, checked: Boolean) = selectThemeFrom(fixedFrame(), ref, checked)
    internal suspend fun selectThemeFrom(frame: ConfigV2UiState, ref: ThemeVersionRef, checked: Boolean) = mutex.withLock {
        edit(frame, frame.preview?.recovery == true) { current ->
            val candidates = current.ui.exportChoices?.themes ?: current.ui.preview?.themeChoices.orEmpty()
            val selected = if (checked) current.ui.selectedThemes + ref else current.ui.selectedThemes - ref
            if (current.ui.busy || selected.size > 16 || candidates.none { it.themeId == ref.themeId && it.revision == ref.revision }) current
            else current.copy(ui = current.ui.copy(selectedThemes = selected))
        }; Unit
    }
    internal suspend fun beginExport(requireSelection: Boolean = false): Boolean = beginExportFrom(fixedFrame(), requireSelection) != null
    internal suspend fun beginExportFrom(frame: ConfigV2UiState, requireSelection: Boolean = true): ConfigFilePicker? = mutex.withLock {
        var ticket: ConfigFilePicker? = null
        var delivered = false
        try {
            val success = action(frame) { before ->
                check(!before.ui.pickerPending && before.ui.preview == null)
                check(frame.selectedItems == before.ui.selectedItems && frame.selectedThemes == before.ui.selectedThemes)
                check(!requireSelection || before.options != null) { "CONFIG_EXPORT_SELECTION_EXPIRED" }
                val frozen = before.options?.let { options ->
                    requireOwner(options.context.access)
                    exports.prepare(options, frame.selectedItems, frame.selectedThemes)
                } ?: exports.prepare()
                requireOwner(frozen.context.access)
                val request = ConfigFilePicker(frame.source, false)
                ticket = request
                check(edit(frame) { it.copy(export = frozen, options = null, picker = request,
                    ui = it.ui.copy(request = Any(), pickerPending = true, exportChoices = null,
                        selectedItems = emptySet(), selectedThemes = emptySet())) })
            }
            ticket.takeIf { success }.also { delivered = it != null }
        } finally {
            // A cancellable Host return may discard the value after publication. Only this
            // not-yet-delivered picker is released; an older/newer request cannot be consumed.
            if (!delivered) consumePicker(ticket)
        }
    }
    internal suspend fun writeExport(uri: Uri?) { fixedFrame(); writeExportFrom(snapshot().picker, uri) }
    internal suspend fun writeExportFrom(ticket: ConfigFilePicker?, uri: Uri?) = mutex.withLock {
        val before = consumePicker(ticket) ?: return@withLock
        if (uri == null) return@withLock
        action(before.ui) {
            val frozen = requireNotNull(before.export) { "CONFIG_EXPORT_NOT_PREPARED" }
            requireOwner(frozen.context.access)
            val bundle = exports.publish(frozen) { value ->
                check(edit(before.ui) { it }) { "CONFIG_SOURCE_EXPIRED" }
                value
            }
            documents.write(uri, bundle)
            requireOwner(frozen.context.access)
            edit(before.ui) { it.copy(ui = it.ui.copy(message = ConfigFileMessage.EXPORTED, request = Any())) }
        }
    }
    internal suspend fun beginImport(recover: Boolean = false): Boolean = beginImportFrom(fixedFrame(), recover) != null
    internal suspend fun beginImportFrom(frame: ConfigV2UiState, recover: Boolean = false): ConfigFilePicker? = mutex.withLock {
        var ticket: ConfigFilePicker? = null
        var delivered = false
        try {
            val success = action(frame, recover) { before ->
                check(!before.ui.pickerPending && before.ui.preview == null && before.options == null)
                val owner = requireNotNull(before.owner); check(owner.canDeclare)
                val progress = imports.progress(before.knownImportId)
                val id = if (recover) requireNotNull(progress).also { check(it.phase == ConfigImportPhase.PREPARED) }.importId else null
                if (!recover) check(progress == null || progress.phase == ConfigImportPhase.OPERATIONS_ACCEPTED) { "CONFIG_IMPORT_PENDING" }
                val request = ConfigFilePicker(frame.source, recover)
                ticket = request
                check(edit(frame, recover) { it.copy(picker = request, importIntent = owner to id,
                    ui = it.ui.copy(request = Any(), pickerPending = true)) })
            }
            ticket.takeIf { success }.also { delivered = it != null }
        } finally {
            // A cancellable Host return may discard the value after publication. Only this
            // not-yet-delivered picker is released; an older/newer request cannot be consumed.
            if (!delivered) consumePicker(ticket)
        }
    }
    /** Draining a revoked picker may clear its opaque slot, but can never consume a newer slot. */
    private fun consumePicker(ticket: ConfigFilePicker?): Session? = synchronized(monitor) {
        if (ticket == null || session.picker !== ticket) return@synchronized null
        val before = session
        val ui = before.ui.copy(pickerPending = false)
        assign(before.copy(export = null, picker = null, importIntent = null, ui = ui))
        before.copy(ui = ui)
    }
    internal suspend fun readImport(uri: Uri?) { fixedFrame(); readImportFrom(snapshot().picker, uri) }
    internal suspend fun readImportFrom(ticket: ConfigFilePicker?, uri: Uri?) = mutex.withLock {
        val before = consumePicker(ticket) ?: return@withLock
        if (uri == null) return@withLock
        action(before.ui, ticket?.recovery == true) {
            val intent = requireNotNull(before.importIntent) { "CONFIG_IMPORT_NOT_PREPARED" }
            requireOwner(intent.first)
            val source = documents.preview(uri)
            requireOwner(intent.first)
            val id = intent.second
            val replacement = if (id == null) imports.previewReplacement(source) else null
            val recovery = if (id != null) imports.previewRecovery(id, source) else null
            check((replacement?.original ?: requireNotNull(recovery).original).context.access == intent.first)
            val summary = ConfigV2PreviewSummary(source.manifest.nodes.size, source.manifest.metrics.size,
                source.manifest.links.size, source.manifest.themes.size, source.manifest.iconPack?.assets?.size ?: 0,
                source.manifest.unresolvedRoles.size, replacement?.counts, replacement?.blockers ?: emptySet(),
                recovery != null, recovery?.committed == false, source.manifest.themes)
            requireOwner(intent.first)
            check(edit(before.ui, ticket?.recovery == true) { it.copy(replacement = replacement, recovery = recovery,
                ui = it.ui.copy(request = Any(), preview = summary, selectedItems = emptySet(), selectedThemes = emptySet())) })
        }
    }
    internal suspend fun confirmImport() = confirmImportFrom(fixedFrame())
    internal suspend fun confirmImportFrom(frame: ConfigV2UiState) = mutex.withLock {
        if (frame.preview == null) return@withLock
        val completed = action(frame, frame.preview.recovery, afterSource = { before, running ->
            refreshLocked()
            inSource(frame.source, frame.preview.recovery) {
                val original = before.replacement?.original ?: requireNotNull(before.recovery).original
                requireOwner(original.context.access)
                check(edit(frame, frame.preview.recovery) { current ->
                    check(current.busyOwner === running) { "CONFIG_SOURCE_EXPIRED" }
                    current.copy(replacement = null, recovery = null, ui = current.ui.copy(preview = null,
                        selectedThemes = emptySet(), message = ConfigFileMessage.LOCAL_COMMITTED, request = Any()))
                }) { "CONFIG_SOURCE_EXPIRED" }
            }
        }) { before ->
            check(frame.selectedThemes == before.ui.selectedThemes)
            val old = before.replacement; val restore = before.recovery
            check((old != null) != (restore != null)) { "CONFIG_IMPORT_NOT_PREPARED" }
            val original = old?.original ?: requireNotNull(restore).original
            requireOwner(original.context.access)
            val id = restore?.importId ?: imports.confirmReplacement(requireNotNull(old))
            edit(frame, frame.preview.recovery) { it.copy(knownImportId = id.takeIf { restore?.networkTracked != false }) }
            val selected = frame.selectedThemes
            if (selected.isNotEmpty()) {
                val controller = requireNotNull(themes)
                imports.prepareThemeInstall(id, original.source, selected, original.context)
                for (ref in selected) {
                    val definition = imports.prepareThemeInstall(id, original.source, setOf(ref), original.context).single()
                    requireOwner(original.context.access)
                    check(edit(frame, frame.preview.recovery) { it }) { "CONFIG_SOURCE_EXPIRED" }
                    controller.install(definition)
                    requireOwner(original.context.access)
                }
            }
            imports.resume(id, original.source, original.context)
            requireOwner(original.context.access)
        }
        if (!completed) refreshLocked()
    }
    internal suspend fun abandonPrepared() = abandonPreparedFrom(fixedFrame())
    internal suspend fun abandonPreparedFrom(frame: ConfigV2UiState) = mutex.withLock {
        if (frame.preview?.recovery != true) return@withLock
        val completed = action(frame, true, afterSource = { before, running ->
            refreshLocked()
            inSource(frame.source, true) {
                requireOwner(requireNotNull(before.recovery).original.context.access)
                check(edit(frame, true) { current ->
                    check(current.busyOwner === running) { "CONFIG_SOURCE_EXPIRED" }
                    current.copy(ui = current.ui.copy(message = ConfigFileMessage.ABANDONED, request = Any()))
                }) { "CONFIG_SOURCE_EXPIRED" }
            }
        }) { before ->
            val preview = requireNotNull(before.recovery)
            requireOwner(preview.original.context.access); check(!preview.committed)
            imports.cancelPrepared(preview.importId, preview.original.source, preview.original.context)
            requireOwner(preview.original.context.access)
            check(edit(frame, true) { it.copy(knownImportId = null, replacement = null, recovery = null,
                ui = it.ui.copy(preview = null, selectedThemes = emptySet())) }) { "CONFIG_SOURCE_EXPIRED" }
        }
        if (!completed) refreshLocked()
    }
    internal suspend fun dismissPreview() = dismissPreviewFrom(fixedFrame())
    internal suspend fun dismissPreviewFrom(frame: ConfigV2UiState) = mutex.withLock {
        edit(frame, frame.preview?.recovery == true) { if (it.ui.busy) it else it.copy(replacement = null, recovery = null, options = null,
            ui = it.ui.copy(preview = null, exportChoices = null, selectedItems = emptySet(), selectedThemes = emptySet(), request = Any())) }; Unit
    }
    internal suspend fun dismissMessage() = dismissMessageFrom(fixedFrame())
    internal suspend fun dismissMessageFrom(frame: ConfigV2UiState) = mutex.withLock {
        edit(frame, recovery = true) { it.copy(ui = it.ui.copy(message = null, request = Any())) }; Unit
    }
    internal suspend fun pickerFailed() { fixedFrame(); pickerFailedFrom(snapshot().picker) }
    internal suspend fun pickerFailedFrom(ticket: ConfigFilePicker?) = mutex.withLock {
        val before = consumePicker(ticket) ?: return@withLock
        edit(before.ui, ticket?.recovery == true) { it.copy(ui = it.ui.copy(message = ConfigFileMessage.FAILED, request = Any())) }; Unit
    }
}
