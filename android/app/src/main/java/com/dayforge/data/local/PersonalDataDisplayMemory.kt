package com.dayforge.data.local

/** Publication fence, not credentials. Callbacks only clear memory; never reenter this registry. */
internal class PersonalDataDisplayMemory {
    interface Listener { fun transition(blocked: Boolean) }
    private val listeners = mutableListOf<java.lang.ref.WeakReference<Listener>>()
    private var transitions = 0
    private var generation = 0L
    internal class Stamp internal constructor(internal val owner: PersonalDataDisplayMemory, internal val generation: Long)

    /** Memory invalidation only. A stamp never replaces fresh account/decryption checks. */
    @Synchronized internal fun capture(): Stamp? = if (transitions == 0) Stamp(this, generation) else null
    @Synchronized internal fun isCurrent(stamp: Stamp): Boolean =
        transitions == 0 && stamp.owner === this && stamp.generation == generation
    /** The entire callback is a short memory-only check/assignment; no SQL, suspension or I/O. */
    @Synchronized internal fun publish(stamp: Stamp, assignment: () -> Boolean): Boolean =
        if (isCurrent(stamp)) assignment() else false

    @Synchronized fun register(listener: Listener) {
        listeners.removeAll { it.get() == null }
        listeners.add(java.lang.ref.WeakReference(listener))
        if (transitions > 0) listener.transition(true)
    }
    @Synchronized fun begin() { generation = Math.addExact(generation, 1L); transitions++; notifyListeners() }
    @Synchronized fun end() { check(transitions > 0); transitions--; notifyListeners() }
    private fun notifyListeners() {
        listeners.removeAll { it.get() == null }
        listeners.forEach { it.get()?.transition(transitions > 0) }
    }
}
