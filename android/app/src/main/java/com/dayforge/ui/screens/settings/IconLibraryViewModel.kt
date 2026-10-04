package com.dayforge.ui.screens.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dayforge.data.appearance.AccountIconCatalog
import com.dayforge.data.appearance.AccountIconContext
import com.dayforge.data.appearance.IconPackVersion
import com.dayforge.data.local.AccountIconMemory
import com.dayforge.domain.model.IconPack
import com.dayforge.domain.service.AccountIconController
import com.dayforge.domain.service.IconPackSource
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

internal data class IconLibraryState(
    val context: AccountIconContext? = null,
    val catalog: AccountIconCatalog? = null,
    val source: IconPackSource? = null,
    val loading: Boolean = false,
    val busy: Boolean = false,
    val picking: Boolean = false,
    val error: String? = null,
    val installed: Boolean = false
)

/** Page state is deliberately not restored from a Bundle: previews and picker authority expire. */
@HiltViewModel
class IconLibraryViewModel @Inject constructor(internal val icons: AccountIconController) : ViewModel() {
    private val monitor = Any()
    private var open = false
    private var blocked = false
    private var stamp = Any()
    private var page = Any()
    private var failure: Pair<com.dayforge.data.local.LocalIconAccess, String>? = null
    private data class Picker(val context: AccountIconContext, val stamp: Any)
    // Keep an invalidated outstanding request until its result arrives; never rebind that URI.
    private var picker: Picker? = null
    private var operation: Job? = null
    private val reload = MutableStateFlow(stamp)
    private val mutable = MutableStateFlow(IconLibraryState())
    internal val state = mutable.asStateFlow()
    private val invalidator = object : AccountIconMemory.Cache {
        override fun authenticationTransition(blocked: Boolean) = synchronized(monitor) {
            this@IconLibraryViewModel.blocked = blocked
            invalidate()
        }
    }

    init {
        icons.registerConsumer(invalidator)
        viewModelScope.launch {
            reload.collectLatest { request ->
                if (!synchronized(monitor) { open && !blocked && stamp === request }) return@collectLatest
                put(request) { IconLibraryState(loading = true, picking = picker != null) }
                try {
                    val context = icons.capture()
                    val catalog = icons.library(context)
                    icons.publish(context) { put(request) {
                        IconLibraryState(context, catalog, picking = picker != null,
                            error = failure?.takeIf { it.first == context.access }?.second)
                    } }
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    put(request) { IconLibraryState(error = error.code(), picking = picker != null) }
                }
            }
        }
    }

    private fun invalidate() {
        stamp = Any()
        failure = null
        mutable.value = IconLibraryState()
        reload.value = stamp
    }
    internal fun openPage() = synchronized(monitor) { open = true; page = Any(); invalidate() }
    internal fun closePage() {
        synchronized(monitor) { open = false; page = Any(); invalidate() }
        operation?.cancel()
    }
    internal fun refresh() = synchronized(monitor) {
        if (operation?.isActive != true && picker == null) invalidate()
    }
    private fun put(request: Any, update: (IconLibraryState) -> IconLibraryState) = synchronized(monitor) {
        if (open && !blocked && stamp === request) mutable.value = update(mutable.value)
    }
    private fun Exception.code() = message?.takeIf { Regex("ICON_[A-Z_]{1,64}").matches(it) } ?: "ICON_LIBRARY_FAILED"

    /** Must succeed BEFORE launch. Callback without this live ticket fails closed, including death. */
    internal fun beginPicker(): Boolean = synchronized(monitor) {
        val context = mutable.value.context
        if (!open || blocked || context == null || picker != null || operation?.isActive == true) false
        else {
            picker = Picker(context, stamp)
            mutable.value = mutable.value.copy(picking = true, error = null, installed = false)
            true
        }
    }
    internal fun pickerResult(uri: Uri?) {
        val ticket = synchronized(monitor) {
            val current = picker
            picker = null
            mutable.value = mutable.value.copy(picking = false)
            current
        }
        if (ticket == null) {
            synchronized(monitor) { if (open && !blocked) mutable.value = mutable.value.copy(error = "ICON_PICKER_EXPIRED") }
            return
        }
        if (!synchronized(monitor) { open && !blocked && stamp === ticket.stamp }) {
            synchronized(monitor) { if (open && !blocked) mutable.value = mutable.value.copy(error = "ICON_PICKER_EXPIRED") }
            return
        }
        if (uri == null) {
            return
        }
        act(ticket.context, ticket.stamp) {
            val preview = icons.preview(ticket.context, uri)
            icons.publish(ticket.context) { put(ticket.stamp) {
                it.copy(source = IconPackSource(ticket.context, preview.manifest, preview), installed = false)
            } }
        }
    }
    internal fun pickerUnavailable() {
        pickerResult(null)
        synchronized(monitor) { if (open && !blocked) mutable.value = mutable.value.copy(error = "ICON_PICKER_UNAVAILABLE") }
    }
    internal fun inspect(pack: IconPack) = synchronized(monitor) {
        val current = mutable.value
        if (!open || blocked || current.busy || current.picking) return@synchronized
        if (current.catalog?.packs?.contains(pack) == true && current.context != null)
            mutable.value = current.copy(source = IconPackSource(current.context, pack), installed = false, error = null)
    }
    internal fun dismissPreview() = synchronized(monitor) {
        if (!mutable.value.busy) mutable.value = mutable.value.copy(source = null, error = null, installed = false)
    }
    internal fun install() {
        val (request, current) = synchronized(monitor) { stamp to mutable.value }
        val preview = current.source?.preview ?: return
        act(preview.context, request) {
            icons.install(preview)
            val catalog = icons.library(preview.context)
            icons.publish(preview.context) { put(request) { it.copy(catalog = catalog, installed = true) } }
        }
    }
    internal fun select(version: IconPackVersion?) {
        val (request, current) = synchronized(monitor) { stamp to mutable.value }
        val context = current.context ?: return
        val catalog = current.catalog ?: return
        act(context, request) { icons.select(context, catalog.selection.generation, version) }
    }
    private fun act(context: AccountIconContext, request: Any, action: suspend () -> Unit) {
        synchronized(monitor) {
            if (!open || blocked || stamp !== request || operation?.isActive == true) return
            val ownerPage = page
            failure = null
            mutable.value = mutable.value.copy(busy = true, error = null)
            operation = viewModelScope.launch {
                try { action() }
                catch (error: Exception) {
                    if (error is CancellationException) throw error
                    // A selection transition can change the page stamp without changing ownership.
                    // Reauthorize, then attach failure only to the same current account's fresh state.
                    try { icons.publish(context) { synchronized(monitor) {
                        if (open && !blocked && page === ownerPage) {
                            failure = context.access to error.code()
                            mutable.value = mutable.value.copy(error = error.code())
                        }
                    } } } catch (authorization: Exception) {
                        if (authorization is CancellationException) throw authorization
                    }
                } finally { put(request) { it.copy(busy = false) } }
            }
        }
    }
    override fun onCleared() { closePage() }
}
