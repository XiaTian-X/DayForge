package com.dayforge.ui.theme

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dayforge.data.appearance.ThemeCatalogContent
import com.dayforge.data.appearance.ThemeCatalogListing
import com.dayforge.domain.appearance.DeviceCardStyle
import com.dayforge.domain.appearance.DeviceThemeMode
import com.dayforge.domain.appearance.DeviceThemeSelection
import com.dayforge.domain.appearance.ThemeVersionRef
import com.dayforge.domain.service.DeviceThemeController
import com.dayforge.widget.WidgetRefreshScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal data class ThemeRecoveryState(
    val busy: Boolean = false,
    val library: ThemeCatalogListing? = null,
    val light: ThemeVersionRef? = null,
    val dark: ThemeVersionRef? = null,
    val error: Exception? = null
) {
    val canApply: Boolean get() = !busy && valid(light, false) && valid(dark, true)
    private fun valid(ref: ThemeVersionRef?, darkMode: Boolean) = library?.items?.any {
        it.slot.ref == ref && it.content is ThemeCatalogContent.Available &&
            (if (darkMode) it.builtIn?.suitableForDark else it.builtIn?.suitableForLight) != false
    } == true
}

/** Explicit repair choices only. Damaged metadata is never treated as an absent preference. */
@HiltViewModel
class ThemeRecoveryViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val themes: DeviceThemeController
) : ViewModel() {
    private val _state = MutableStateFlow(ThemeRecoveryState())
    internal val state = _state.asStateFlow()

    init { refresh() }

    internal fun refresh() = operate {
        val library = themes.library()
        _state.value = ThemeRecoveryState(
            busy = true,
            library = library,
            light = library.selected?.selection?.light,
            dark = library.selected?.selection?.dark
        )
    }

    internal fun choose(ref: ThemeVersionRef, dark: Boolean) {
        val before = _state.value
        if (before.busy) return
        val item = before.library?.items?.singleOrNull { it.slot.ref == ref } ?: return
        if (item.content !is ThemeCatalogContent.Available ||
            (if (dark) item.builtIn?.suitableForDark else item.builtIn?.suitableForLight) == false) return
        _state.value = if (dark) before.copy(dark = ref, error = null) else before.copy(light = ref, error = null)
    }

    internal fun apply() {
        val snapshot = _state.value
        if (!snapshot.canApply) return
        operate {
            val saved = checkNotNull(snapshot.library).selected
            val selection = DeviceThemeSelection(
                checkNotNull(snapshot.light), checkNotNull(snapshot.dark),
                saved?.selection?.mode ?: DeviceThemeMode.SYSTEM,
                saved?.selection?.cardStyle ?: DeviceCardStyle.FOLLOW_THEME
            )
            themes.select(saved?.revision ?: 0, selection)
            // Observing may have ended with Failed; resume it only after a successful explicit choice.
            themes.retry()
            WidgetRefreshScheduler.request(context)
        }
    }

    /** Caller retains the catalog revision shown in the confirmation; repository rechecks both refs. */
    internal fun delete(ref: ThemeVersionRef, catalogRevision: Long) = operate {
        themes.delete(ref, catalogRevision)
        val library = themes.library()
        _state.value = ThemeRecoveryState(true, library, library.selected?.selection?.light,
            library.selected?.selection?.dark)
        themes.retry()
        // The saved colors may be unchanged, but cold widgets can still be showing the load error.
        WidgetRefreshScheduler.request(context)
    }

    private fun operate(action: suspend () -> Unit) {
        if (_state.value.busy) return
        _state.value = _state.value.copy(busy = true, error = null)
        viewModelScope.launch {
            try { action() }
            catch (error: Exception) {
                if (error is CancellationException) throw error
                _state.value = _state.value.copy(error = error)
            } finally { _state.value = _state.value.copy(busy = false) }
        }
    }
}
