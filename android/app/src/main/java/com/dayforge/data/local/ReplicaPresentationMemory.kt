package com.dayforge.data.local

/** Separate from icon selection: ordinary refreshes/cursor/appearance writes must not reset navigation. */
internal class ReplicaPresentationMemory {
    interface Cache { fun transition(blocked: Boolean) }
    private val caches = mutableListOf<java.lang.ref.WeakReference<Cache>>()
    private var transitions = 0

    @Synchronized fun register(cache: Cache) {
        caches.removeAll { it.get() == null }
        caches.add(java.lang.ref.WeakReference(cache))
        if (transitions > 0) cache.transition(true)
    }
    @Synchronized fun begin() { transitions++; notifyCaches() }
    @Synchronized fun end() { check(transitions > 0); transitions--; notifyCaches() }
    private fun notifyCaches() {
        caches.removeAll { it.get() == null }
        // Callbacks are short memory-only operations and may NOT reenter this registry.
        caches.forEach { it.get()?.transition(transitions > 0) }
    }
}

internal data class ReplicaPresentationWitness(val pointerPresent: Boolean, val stage: ReplicaSwitchStage?,
    val history: List<ReplicaSwitchStage>)
