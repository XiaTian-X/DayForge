package com.dayforge.ui.navigation

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.navigation.NavBackStackEntry
import com.dayforge.data.local.DatabaseNavigationSelections
import com.dayforge.data.local.DatabaseObjectSelection
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import android.widget.Toast
import com.dayforge.R

/** Opaque in-process transfer, owned by the authenticated navigation graph. Restoring a
 * numeric route after process death does not reconstruct permission for that old object. */
@HiltViewModel
internal class DatabaseNavigationViewModel @Inject constructor(
    private val selections: DatabaseNavigationSelections,
    @param:ApplicationContext private val context: Context
) : ViewModel() {
    private val issued = mutableSetOf<String>()

    fun route(selection: DatabaseObjectSelection, build: (Long, String?) -> String): String {
        val key = selections.issue(selection)
        issued += key
        return build(selection.id, key)
    }

    fun navigate(selection: DatabaseObjectSelection, build: (Long, String?) -> String, navigate: (String) -> Unit) {
        try { navigate(route(selection, build)) }
        catch (error: IllegalStateException) {
            if (error.message !in setOf("DATABASE_EPOCH_INPUT_REQUIRED", "DATABASE_EPOCH_INPUT_EXPIRED", "DATABASE_EPOCH_SELECTION_CHANGED")) throw error
            Toast.makeText(context, context.getString(R.string.database_view_expired), Toast.LENGTH_SHORT).show()
        }
    }

    fun creationParent(selection: DatabaseObjectSelection): String = selections.issue(selection).also { issued += it }
    fun navigateChild(selection: DatabaseObjectSelection, navigate: (String, String) -> Unit) {
        try { navigate(selection.uuid, creationParent(selection)) }
        catch (error: IllegalStateException) {
            if (error.message !in setOf("DATABASE_EPOCH_INPUT_REQUIRED", "DATABASE_EPOCH_INPUT_EXPIRED", "DATABASE_EPOCH_SELECTION_CHANGED")) throw error
            Toast.makeText(context, context.getString(R.string.database_view_expired), Toast.LENGTH_SHORT).show()
        }
    }
    fun parent(entry: NavBackStackEntry, uuid: String): DatabaseObjectSelection? {
        val key = entry.arguments?.getString("parentSource")
        retainUntilEntryDestroyed(entry, key)
        return try { selections.resolveParent(key, uuid) } catch (_: IllegalStateException) { null }
    }

    fun target(entry: NavBackStackEntry, kind: DatabaseObjectSelection.Kind, id: Long): DatabaseObjectSelection? {
        val key = entry.arguments?.getString("source")
        retainUntilEntryDestroyed(entry, key)
        // Only the opaque originating key is consulted. An absent/expired key reaches the
        // target's existing explicit INPUT_REQUIRED path; never read current ID to bless it.
        return try { selections.resolve(key, kind, id) }
        catch (_: IllegalStateException) { null }
    }

    private fun retainUntilEntryDestroyed(entry: NavBackStackEntry, key: String?) {
        val observer = object : LifecycleEventObserver {
            override fun onStateChanged(source: androidx.lifecycle.LifecycleOwner, event: Lifecycle.Event) {
                if (event == Lifecycle.Event.ON_DESTROY) {
                    selections.release(key); issued.remove(key)
                    source.lifecycle.removeObserver(this)
                }
            }
        }
        entry.lifecycle.addObserver(observer)
    }

    override fun onCleared() {
        issued.forEach(selections::release)
        issued.clear()
    }
}
