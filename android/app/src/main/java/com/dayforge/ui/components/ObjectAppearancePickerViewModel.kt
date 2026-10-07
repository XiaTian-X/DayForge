package com.dayforge.ui.components

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dayforge.data.appearance.AccountIconContext
import com.dayforge.data.local.AccountIconMemory
import com.dayforge.data.repository.ObjectAppearanceAuthority
import com.dayforge.domain.model.IconReference
import com.dayforge.domain.model.ObjectAppearance
import com.dayforge.domain.model.iconAllowed
import com.dayforge.domain.service.AccountIconController
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal data class ObjectIconChoice(val reference: IconReference, val label: String)
internal data class ObjectAppearancePickerState(
    val appearance: ObjectAppearance? = null,
    val choices: List<ObjectIconChoice> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null
)

/** A picker only edits a draft. Its captured authority cannot outlive the parent edit ticket. */
@HiltViewModel
class ObjectAppearancePickerViewModel @Inject constructor(private val icons: AccountIconController) : ViewModel() {
    private val monitor = Any()
    private var stamp = Any()
    private var context: AccountIconContext? = null
    private var open = false
    private var blocked = false
    private var request: Job? = null
    private val mutable = MutableStateFlow(ObjectAppearancePickerState())
    internal val state = mutable.asStateFlow()
    private val invalidator = object : AccountIconMemory.Cache {
        override fun authenticationTransition(blocked: Boolean) = synchronized(monitor) {
            this@ObjectAppearancePickerViewModel.blocked = blocked
            stamp = Any(); context = null
            mutable.value = ObjectAppearancePickerState(error = if (open) "ICON_PICKER_EXPIRED" else null)
        }
    }

    init { icons.registerConsumer(invalidator) }

    internal fun open(appearance: ObjectAppearance, oneTime: Boolean, authority: ObjectAppearanceAuthority?) {
        request?.cancel()
        val expected = synchronized(monitor) {
            open = true; stamp = Any(); context = null
            mutable.value = ObjectAppearancePickerState(appearance = appearance, loading = true)
            stamp
        }
        request = viewModelScope.launch {
            try {
                val ticket = requireNotNull(authority) { "ICON_PICKER_EXPIRED" }
                val captured = icons.capture()
                check(captured.access.session == ticket.session) { "ICON_PICKER_EXPIRED" }
                val catalog = icons.library(captured)
                val roles = catalog.packs.flatMap { it.roles.keys }.distinct().sorted()
                    .map { ObjectIconChoice(IconReference.Role(it), it) }
                    .filter { iconAllowed(it.reference, oneTime) }
                val assets = catalog.packs.flatMap { it.assets }.distinctBy { it.assetId }
                    .filter { iconAllowed(IconReference.Asset(it.assetId), oneTime, it) }
                    .sortedWith(compareBy({ it.name }, { it.assetId }))
                    .map { ObjectIconChoice(IconReference.Asset(it.assetId), it.name) }
                val choices = (listOf(ObjectIconChoice(appearance.icon, referenceLabel(appearance.icon))) + roles + assets)
                    .distinctBy { it.reference }
                icons.publish(captured) { synchronized(monitor) {
                    if (open && !blocked && stamp === expected) {
                        context = captured
                        mutable.value = ObjectAppearancePickerState(appearance, choices)
                    }
                } }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                synchronized(monitor) {
                    if (open && !blocked && stamp === expected)
                        mutable.value = ObjectAppearancePickerState(error = error.message ?: "ICON_PICKER_EXPIRED")
                }
            }
        }
    }

    internal fun close() {
        synchronized(monitor) { open = false; stamp = Any(); context = null; mutable.value = ObjectAppearancePickerState() }
        request?.cancel()
    }

    internal fun choose(reference: IconReference) = synchronized(monitor) {
        val current = mutable.value
        if (open && !blocked && !current.loading && current.choices.any { it.reference == reference })
            mutable.value = current.copy(appearance = current.appearance?.copy(icon = reference))
    }

    internal fun tint(value: String) = synchronized(monitor) {
        if (open && !blocked && !mutable.value.loading && value in setOf("theme", "object"))
            mutable.value = mutable.value.copy(appearance = mutable.value.appearance?.copy(iconTint = value))
    }

    internal fun confirm(onSelected: (ObjectAppearance) -> Unit) {
        val (expected, captured, appearance) = synchronized(monitor) {
            if (!open || blocked || mutable.value.loading) return
            val captured = context ?: return
            val appearance = mutable.value.appearance ?: return
            mutable.value = mutable.value.copy(loading = true)
            Triple(stamp, captured, appearance)
        }
        request = viewModelScope.launch {
            try {
                icons.publish(captured) { synchronized(monitor) {
                    if (open && !blocked && stamp === expected) {
                        onSelected(appearance)
                        mutable.value = mutable.value.copy(loading = false)
                    }
                } }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                synchronized(monitor) {
                    if (open && !blocked && stamp === expected)
                        mutable.value = ObjectAppearancePickerState(error = "ICON_PICKER_EXPIRED")
                }
            }
        }
    }

    private fun referenceLabel(reference: IconReference): String = when (reference) {
        is IconReference.Role -> reference.role
        is IconReference.Asset -> reference.assetId
    }
}
