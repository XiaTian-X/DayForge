package com.dayforge.ui.screens.settings

import androidx.lifecycle.SavedStateHandle
import com.dayforge.data.appearance.BuiltInTheme
import com.dayforge.data.appearance.ThemeCatalogException
import com.dayforge.data.appearance.ThemeInstallPhase
import com.dayforge.data.appearance.ValidatedTheme
import com.dayforge.data.appearance.definitionDigest
import com.dayforge.data.appearance.strictAppearanceJson
import com.dayforge.domain.appearance.ThemeColorField
import com.dayforge.domain.appearance.ThemeEditDraft
import com.dayforge.domain.appearance.ThemeVersionRef
import com.dayforge.domain.service.DeviceThemeController
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal data class ThemeEditorState(
    val open: Boolean = false,
    val busy: Boolean = false,
    val draft: ThemeEditDraft? = null,
    val preview: ValidatedTheme? = null,
    val locked: Boolean = false,
    val error: Exception? = null,
    val saved: ThemeVersionRef? = null
)

/** Small bounded inputs only. Source palettes are re-read by immutable identity and digest. */
@Serializable
private data class ThemeEditorInputs(
    val version: Int,
    val source: ThemeVersionRef,
    val sourceDigest: String,
    val catalogRevision: Long,
    val target: ThemeVersionRef,
    val name: String,
    val edits: Map<String, String>,
    val attempted: Boolean = false,
    val candidateDigest: String? = null
)

/** ViewModel-owned, main-thread UI input; no independent scope or automatic file/selection writes. */
internal class ThemeEditorWorkflow(private val themes: DeviceThemeController, private val handle: SavedStateHandle) {
    private val mutex = Mutex()
    private val _state = MutableStateFlow(ThemeEditorState(open = handle.contains(KEY), busy = handle.contains(KEY)))
    val state = _state.asStateFlow()
    private var inputs: ThemeEditorInputs? = null

    suspend fun begin(choice: ThemeChoiceSummary) = action {
        if (handle.contains(KEY)) throw IllegalStateException("THEME_EDIT_ALREADY_OPEN")
        _state.value = ThemeEditorState(open = true, busy = true)
        val catalog = themes.catalog() ?: throw ThemeCatalogException("THEME_CATALOG_UNINITIALIZED")
        if (catalog.revision != choice.catalogRevision) throw ThemeCatalogException("THEME_CATALOG_CONFLICT")
        val source = themes.export(choice.ref)
        if (source.definitionDigest() != choice.item.slot.digest) throw ThemeCatalogException("THEME_CONTENT_CHANGED")
        val target = if (BuiltInTheme.entries.any { it.themeId == choice.ref.themeId }) {
            ThemeVersionRef(UUID.randomUUID().toString(), 1)
        } else {
            val latest = catalog.slots.filter { it.ref.themeId == choice.ref.themeId }.maxOf { it.ref.revision }
            if (latest == Int.MAX_VALUE) throw IllegalStateException("THEME_EDIT_REVISION_EXHAUSTED")
            ThemeVersionRef(choice.ref.themeId, latest + 1)
        }
        val draft = ThemeEditDraft.start(source.definition, target)
        save(ThemeEditorInputs(1, choice.ref, source.definitionDigest(), catalog.revision, target, draft.name, draft.edits))
        _state.value = ThemeEditorState(open = true, busy = true, draft = draft)
    }

    /** Explicitly called by the new ViewModel; corrupt/future saved inputs stay available to discard. */
    suspend fun restore() = action {
        if (!handle.contains(KEY) || inputs != null) return@action
        val raw = handle.get<Any>(KEY) as? String ?: throw IllegalArgumentException("THEME_EDIT_STATE")
        if (raw.length > INPUT_LIMIT) throw IllegalArgumentException("THEME_EDIT_INPUT_LIMIT")
        val checked = strictAppearanceJson(raw.toByteArray(Charsets.UTF_8), INPUT_LIMIT * 4, {}) {
            throw IllegalArgumentException("THEME_EDIT_STATE")
        }
        val saved = Json.decodeFromString<ThemeEditorInputs>(checked)
        require(saved.version == 1 && saved.catalogRevision > 0 && DIGEST.matches(saved.sourceDigest) &&
            (saved.candidateDigest == null || DIGEST.matches(saved.candidateDigest)) &&
            (!saved.attempted || saved.candidateDigest != null)) { "THEME_EDIT_STATE" }
        val builtIn = BuiltInTheme.entries.any { it.themeId == saved.source.themeId }
        require(!BuiltInTheme.entries.any { it.themeId == saved.target.themeId } &&
            if (builtIn) saved.target.themeId != saved.source.themeId && saved.target.revision == 1
            else saved.target.themeId == saved.source.themeId && saved.target.revision > saved.source.revision) {
            "THEME_EDIT_IDENTITY"
        }
        val slot = themes.catalog()?.slots?.singleOrNull { it.ref == saved.target }
        if (slot?.phase == ThemeInstallPhase.DELETING) throw ThemeCatalogException("THEME_DELETE_PENDING")
        // Android may restore a pre-confirmation snapshot captured at onStop. The durable journal,
        // not just the transient attempted flag, decides whether this identity already committed.
        if (slot != null && saved.candidateDigest != null) {
            if (slot.digest != saved.candidateDigest) throw ThemeCatalogException("THEME_VERSION_REUSED")
            if (slot.phase == ThemeInstallPhase.ACTIVE) {
                val committed = themes.export(saved.target)
                if (committed.definitionDigest() != saved.candidateDigest) throw ThemeCatalogException("THEME_VERSION_REUSED")
                complete(saved.target)
                return@action
            }
        }
        val source = themes.export(saved.source)
        if (source.definitionDigest() != saved.sourceDigest) throw ThemeCatalogException("THEME_CONTENT_CHANGED")
        val draft = ThemeEditDraft.restore(source.definition, saved.target, saved.name, saved.edits)
        val locked = saved.attempted || slot != null
        val preview = if (locked) freeze(draft).also {
            val digest = it.definitionDigest()
            if (saved.candidateDigest != null && digest != saved.candidateDigest) throw IllegalArgumentException("THEME_EDIT_STATE")
            if (slot != null && digest != slot.digest) throw ThemeCatalogException("THEME_VERSION_REUSED")
        } else null
        if (slot?.phase == ThemeInstallPhase.ACTIVE) {
            val committed = themes.export(saved.target)
            if (committed.definitionDigest() != preview!!.definitionDigest()) throw ThemeCatalogException("THEME_VERSION_REUSED")
            complete(saved.target)
            return@action
        }
        save(saved.copy(attempted = locked, candidateDigest = preview?.definitionDigest() ?: saved.candidateDigest))
        _state.value = ThemeEditorState(open = true, busy = true, draft = draft, preview = preview, locked = locked)
    }

    fun rename(value: String) = edit { it.withName(value) }
    fun color(field: ThemeColorField, value: String) = edit { it.withColor(field, value) }
    fun reset(field: ThemeColorField) = edit { it.reset(field) }

    private fun edit(transform: (ThemeEditDraft) -> ThemeEditDraft) {
        val current = _state.value
        val saved = inputs ?: return
        if (current.busy || current.preview != null || saved.attempted) return
        try {
            val draft = transform(current.draft ?: return)
            save(saved.copy(name = draft.name, edits = draft.edits, candidateDigest = null))
            _state.value = current.copy(draft = draft, error = null)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            _state.value = current.copy(error = error)
        }
    }

    suspend fun prepare() = action {
        val draft = _state.value.draft ?: throw IllegalStateException("THEME_EDIT_STATE")
        val saved = inputs ?: throw IllegalStateException("THEME_EDIT_STATE")
        if (saved.attempted) return@action
        val preview = freeze(draft)
        save(saved.copy(candidateDigest = preview.definitionDigest()))
        _state.value = _state.value.copy(preview = preview, error = null)
    }

    /** A confirmed uncertain save locks this exact candidate; retries cannot create another identity. */
    suspend fun confirm(preview: ValidatedTheme) = action {
        if (_state.value.preview !== preview) return@action
        val saved = inputs ?: throw IllegalStateException("THEME_EDIT_STATE")
        if (preview.definitionDigest() != saved.candidateDigest) throw IllegalArgumentException("THEME_EDIT_STATE")
        save(saved.copy(attempted = true))
        _state.value = _state.value.copy(locked = true)
        themes.install(preview, saved.catalogRevision)
        complete(saved.target)
    }

    fun backToEdit() {
        if (!_state.value.busy && inputs?.attempted == false) _state.value = _state.value.copy(preview = null, error = null)
    }

    /** Discards only transient input; an already-confirmed file/journal is not deleted or rolled back. */
    fun cancel() {
        if (_state.value.busy) return
        handle.remove<String>(KEY)
        inputs = null
        _state.value = ThemeEditorState()
    }

    fun dismissResult() { if (!_state.value.busy) _state.value = _state.value.copy(saved = null) }

    private fun save(value: ThemeEditorInputs) {
        val raw = Json.encodeToString(value)
        require(raw.length <= INPUT_LIMIT) { "THEME_EDIT_INPUT_LIMIT" }
        handle[KEY] = raw
        inputs = value
    }

    private fun complete(ref: ThemeVersionRef) {
        handle.remove<String>(KEY)
        inputs = null
        _state.value = ThemeEditorState(saved = ref)
    }

    private suspend fun freeze(draft: ThemeEditDraft): ValidatedTheme =
        ValidatedTheme.read { Json.encodeToString(draft.definition()).byteInputStream(Charsets.UTF_8) }

    private suspend fun action(block: suspend () -> Unit) = mutex.withLock {
        _state.value = _state.value.copy(busy = true, error = null)
        try { block() }
        catch (error: Exception) {
            if (error is CancellationException) throw error
            _state.value = _state.value.copy(open = true, error = error)
        } finally { _state.value = _state.value.copy(busy = false) }
    }

    companion object {
        internal const val KEY = "theme-editor-inputs-v1"
        private const val INPUT_LIMIT = 16_384
        private val DIGEST = Regex("[a-f0-9]{64}")
    }
}
