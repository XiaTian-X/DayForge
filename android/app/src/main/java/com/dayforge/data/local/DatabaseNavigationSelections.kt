package com.dayforge.data.local

import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** In-process navigation transfer only. Route keys never reconstruct a source after process
 * death; target operations still validate the original selection with withSelection.
 * Entry owners release their own key when removed, not keys owned by another destination.
 */
@Singleton
internal class DatabaseNavigationSelections @Inject constructor(private val host: DatabaseRuntimeHost) {
    private val monitor = Any()
    private val values = mutableMapOf<String, DatabaseObjectSelection>()
    private val listener = object : DatabaseRuntimeHost.MemoryListener {
        override fun transition(blocked: Boolean) {
            if (blocked) synchronized(monitor) { values.clear() }
        }
    }
    private val registration = host.observeMemory(listener)

    internal fun issue(selection: DatabaseObjectSelection): String {
        check(selection.publication.belongsTo(host)) { "DATABASE_EPOCH_INPUT_REQUIRED" }
        val key = UUID.randomUUID().toString()
        check(selection.publication.publish {
            synchronized(monitor) { check(values.put(key, selection) == null) }
        }) { "DATABASE_EPOCH_INPUT_EXPIRED" }
        return key
    }

    internal fun resolve(key: String?, kind: DatabaseObjectSelection.Kind, id: Long): DatabaseObjectSelection {
        val original = synchronized(monitor) { key?.let(values::get) }
        check(original != null && original.kind == kind && original.id == id && original.publication.belongsTo(host)) {
            "DATABASE_EPOCH_INPUT_REQUIRED"
        }
        check(original.publication.isCurrentInMemory()) { "DATABASE_EPOCH_INPUT_EXPIRED" }
        return original
    }

    internal fun resolveParent(key: String?, uuid: String): DatabaseObjectSelection {
        val original = synchronized(monitor) { key?.let(values::get) }
        check(original != null && original.kind == DatabaseObjectSelection.Kind.HABIT && original.uuid == uuid &&
            original.publication.belongsTo(host)) { "DATABASE_EPOCH_INPUT_REQUIRED" }
        check(original.publication.isCurrentInMemory()) { "DATABASE_EPOCH_INPUT_EXPIRED" }
        return original
    }

    internal fun release(key: String?) {
        if (key != null) synchronized(monitor) { values.remove(key) }
    }
}
