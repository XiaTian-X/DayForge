package com.dayforge.data.appearance

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.dayforge.domain.appearance.ThemeVersionRef
import com.dayforge.domain.appearance.SavedThemeSelection
import com.dayforge.domain.model.ThemeDefinition
import java.util.Collections
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** A broken unused file remains discoverable for explicit deletion; it is never replaced by a preset. */
internal sealed interface ThemeCatalogContent {
    data class Available(val definition: ThemeDefinition) : ThemeCatalogContent
    data object Pending : ThemeCatalogContent
    data class Unavailable(val error: Exception) : ThemeCatalogContent
}

internal data class ThemeCatalogItem(
    val slot: ThemeCatalogSlot,
    val builtIn: BuiltInTheme?,
    val content: ThemeCatalogContent
)

internal data class ThemeCatalogListing(
    val state: ThemeCatalogState,
    val selected: SavedThemeSelection?,
    val items: List<ThemeCatalogItem>
)

/** The durable local catalog, not an account asset repository. All mutations serialize in one process. */
internal class ThemeCatalogRepository(
    private val preferences: DataStore<Preferences>,
    private val files: ThemeFileRepository,
    private val builtIns: BuiltInThemes
) {
    private val selection = DeviceThemeStore(preferences, files)

    fun states(): Flow<ThemeCatalogState?> = preferences.data.map { values ->
        ThemeCatalogState.decode(values[THEME_CATALOG_KEY]).also { catalog ->
            selection.readFrom(values)?.let { catalog?.requireSelectable(it.selection) }
        }
    }.distinctUntilChanged()
    suspend fun state(): ThemeCatalogState? = states().first()

    /** One metadata snapshot, excluding concurrent catalog mutation while each file is validated. */
    suspend fun listing(): ThemeCatalogListing = serialized {
        val values = preferences.data.first()
        val state = ThemeCatalogState.decode(values[THEME_CATALOG_KEY])
            ?: throw ThemeCatalogException("THEME_CATALOG_UNINITIALIZED")
        val selected = selection.readFrom(values)
        selected?.let { state.requireSelectable(it.selection) }
        val items = state.slots.map { slot ->
            currentCoroutineContext().ensureActive()
            val content = if (slot.phase != ThemeInstallPhase.ACTIVE) ThemeCatalogContent.Pending else try {
                val theme = files.read(slot.ref.themeId, slot.ref.revision)
                verify(slot, theme)
                ThemeCatalogContent.Available(theme.definition)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                ThemeCatalogContent.Unavailable(error)
            }
            ThemeCatalogItem(slot, BuiltInTheme.entries.find { it.themeId == slot.ref.themeId }, content)
        }
        ThemeCatalogListing(state, selected, Collections.unmodifiableList(items))
    }

    suspend fun initialize(): ThemeCatalogState = serialized {
        // Reject corrupt metadata before attempting to install anything.
        val previous = state()
        // Existing catalog: validate its fixed APK identities without silently repairing files.
        // Selected files are validated by the loader; damage to an unused entry belongs in listing(),
        // and must not prevent the otherwise valid app theme from loading.
        val installed = if (previous == null) builtIns.installAll(files) else builtIns.readAll()
        val initial = ThemeCatalogState(1, 1, Collections.unmodifiableList(installed.map { theme ->
            ThemeCatalogSlot(theme.ref(), theme.definitionDigest(), theme.definition.themeId, ThemeInstallPhase.ACTIVE)
        }))
        val committed = preferences.edit { values ->
            val existing = ThemeCatalogState.decode(values[THEME_CATALOG_KEY])
            if (existing == null) {
                selection.readFrom(values)?.let { initial.requireSelectable(it.selection) }
                values[THEME_CATALOG_KEY] = Json.encodeToString(initial)
            } else {
                selection.readFrom(values)?.let { existing.requireSelectable(it.selection) }
                for (slot in initial.slots) {
                    if (existing.slots.single { it.ref == slot.ref }.digest != slot.digest) {
                        throw ThemeCatalogException("THEME_BUILTIN_CHANGED")
                    }
                }
            }
        }
        checkNotNull(ThemeCatalogState.decode(committed[THEME_CATALOG_KEY]))
    }

    /** Frozen validated preview only; no URI reopen and no selection change. */
    suspend fun install(preview: ValidatedTheme): ThemeCatalogState = serialized {
        val ref = preview.ref()
        if (BuiltInTheme.entries.any { it.themeId == ref.themeId }) throw ThemeCatalogException("THEME_BUILTIN_RESERVED")
        val digest = preview.definitionDigest()
        // Do not claim ownership of an unlisted, conflicting file through a new journal.
        // Otherwise cancelling that failed import could delete bytes we never installed.
        if (requireState().slots.none { it.ref == ref }) {
            files.find(ref.themeId, ref.revision)?.let {
                if (it.definition != preview.definition) throw ThemeCatalogException("THEME_VERSION_REUSED")
            }
        }
        var reserved: ThemeCatalogSlot? = null
        mutate { catalog, _ ->
            val existing = catalog.slots.singleOrNull { it.ref == ref }
            if (existing != null) {
                if (existing.digest != digest) throw ThemeCatalogException("THEME_VERSION_REUSED")
                if (existing.phase == ThemeInstallPhase.DELETING) throw ThemeCatalogException("THEME_DELETE_PENDING")
                reserved = existing
                catalog
            } else {
                requireHeadroom(catalog)
                if (catalog.slots.size >= THEME_CATALOG_CUSTOM_LIMIT + BuiltInTheme.entries.size) {
                    throw ThemeCatalogException("THEME_CATALOG_LIMIT")
                }
                val slot = ThemeCatalogSlot(ref, digest, UUID.randomUUID().toString(), ThemeInstallPhase.INSTALLING)
                reserved = slot
                catalog.next(catalog.slots + slot)
            }
        }
        val slot = checkNotNull(reserved)
        if (slot.phase == ThemeInstallPhase.ACTIVE) {
            // An idempotent call still detects absent/damaged files; do not silently recreate them.
            verify(slot, files.read(ref.themeId, ref.revision))
            return@serialized requireState()
        }
        files.discardOperation(slot.operationId)
        verify(slot, files.install(preview, slot.operationId))
        finish(slot, slot.copy(phase = ThemeInstallPhase.ACTIVE))
    }

    suspend fun delete(ref: ThemeVersionRef, expectedRevision: Long): ThemeCatalogState = serialized {
        if (BuiltInTheme.entries.any { it.themeId == ref.themeId }) throw ThemeCatalogException("THEME_BUILTIN_RESERVED")
        var deleting: ThemeCatalogSlot? = null
        mutate { catalog, values ->
            if (catalog.revision != expectedRevision) throw ThemeCatalogException("THEME_CATALOG_CONFLICT")
            val slot = catalog.slots.singleOrNull { it.ref == ref } ?: throw ThemeCatalogException("THEME_NOT_AVAILABLE")
            selection.readFrom(values)?.selection?.let {
                if (it.light == ref || it.dark == ref) throw ThemeCatalogException("THEME_IN_USE")
            }
            if (slot.phase == ThemeInstallPhase.DELETING) { deleting = slot; catalog }
            else {
                requireHeadroom(catalog)
                val next = slot.copy(phase = ThemeInstallPhase.DELETING)
                deleting = next
                catalog.next(catalog.slots.map { if (it.ref == ref) next else it })
            }
        }
        val slot = checkNotNull(deleting)
        files.discardOperation(slot.operationId)
        files.removeVersion(ref.themeId, ref.revision)
        finish(slot, null)
    }

    /** Only confirmed journal operations recover; unknown files are never discovered as imports. */
    suspend fun recover(): ThemeCatalogState = serialized {
        for (slot in requireState().slots.filter { it.phase != ThemeInstallPhase.ACTIVE }) {
            currentCoroutineContext().ensureActive()
            files.discardOperation(slot.operationId)
            if (slot.phase == ThemeInstallPhase.DELETING) {
                files.removeVersion(slot.ref.themeId, slot.ref.revision)
                finish(slot, null)
            } else {
                val installed = files.find(slot.ref.themeId, slot.ref.revision)
                if (installed != null) verify(slot, installed)
                finish(slot, if (installed == null) null else slot.copy(phase = ThemeInstallPhase.ACTIVE))
            }
        }
        requireState()
    }

    suspend fun export(ref: ThemeVersionRef): ValidatedTheme = serialized {
        val slot = requireState().slots.singleOrNull { it.ref == ref && it.phase == ThemeInstallPhase.ACTIVE }
            ?: throw ThemeCatalogException("THEME_NOT_AVAILABLE")
        files.read(ref.themeId, ref.revision).also { verify(slot, it) }
    }

    private fun verify(slot: ThemeCatalogSlot, theme: ValidatedTheme) {
        if (slot.ref != theme.ref() || slot.digest != theme.definitionDigest()) throw ThemeCatalogException("THEME_CONTENT_CHANGED")
    }

    private suspend fun finish(expected: ThemeCatalogSlot, replacement: ThemeCatalogSlot?): ThemeCatalogState = mutate { catalog, _ ->
        if (catalog.slots.singleOrNull { it.ref == expected.ref } != expected) throw ThemeCatalogException("THEME_CATALOG_CONFLICT")
        catalog.next(catalog.slots.mapNotNull { if (it.ref == expected.ref) replacement else it })
    }

    private suspend fun mutate(transform: suspend (ThemeCatalogState, Preferences) -> ThemeCatalogState): ThemeCatalogState {
        val committed = preferences.edit { values ->
            val catalog = ThemeCatalogState.decode(values[THEME_CATALOG_KEY]) ?: throw ThemeCatalogException("THEME_CATALOG_UNINITIALIZED")
            val selected = selection.readFrom(values)
            selected?.let { catalog.requireSelectable(it.selection) }
            val next = transform(catalog, values)
            selected?.let { next.requireSelectable(it.selection) }
            if (next != catalog) values[THEME_CATALOG_KEY] = Json.encodeToString(next)
        }
        return checkNotNull(ThemeCatalogState.decode(committed[THEME_CATALOG_KEY]))
    }

    private suspend fun requireState() = state() ?: throw ThemeCatalogException("THEME_CATALOG_UNINITIALIZED")

    private fun requireHeadroom(catalog: ThemeCatalogState) {
        val reservedFinishes = catalog.slots.count { it.phase != ThemeInstallPhase.ACTIVE }
        if (Long.MAX_VALUE - catalog.revision < reservedFinishes + 2L) throw ThemeCatalogException("THEME_CATALOG_EXHAUSTED")
    }

    private suspend fun <T> serialized(action: suspend () -> T): T = mutex.withLock {
        currentCoroutineContext().ensureActive()
        action()
    }

    private companion object { val mutex = Mutex() }
}

internal fun ValidatedTheme.ref() = ThemeVersionRef(definition.themeId, definition.revision)
