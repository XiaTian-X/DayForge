package com.dayforge.data.local

import java.lang.ref.WeakReference

/** Non-creating lifecycle inventory. It supplies no database/permission and cannot replace,
 * reset or reactivate an owner. Test resources use identity differences to close only Hosts
 * actually created in that test after its caller jobs have completed.
 */
internal object DatabaseRuntimeHosts {
    private val hosts = mutableListOf<WeakReference<DatabaseRuntimeHost>>()
    @Synchronized fun created(host: DatabaseRuntimeHost) {
        hosts.removeAll { it.get() == null }
        hosts += WeakReference(host)
    }
    @Synchronized fun snapshot(): List<DatabaseRuntimeHost> {
        hosts.removeAll { it.get() == null }
        return hosts.mapNotNull { it.get() }
    }
}
