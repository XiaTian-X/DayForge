package com.dayforge.data.local

/** Publication fence, not credentials. Callbacks only clear memory; never reenter this registry. */
internal class PersonalDataDisplayMemory {
    interface Listener { fun transition(blocked: Boolean) }
    private val listeners = mutableListOf<java.lang.ref.WeakReference<Listener>>()
    private var transitions = 0

    @Synchronized fun register(listener: Listener) {
        listeners.removeAll { it.get() == null }
        listeners.add(java.lang.ref.WeakReference(listener))
        if (transitions > 0) listener.transition(true)
    }
    @Synchronized fun begin() { transitions++; notifyListeners() }
    @Synchronized fun end() { check(transitions > 0); transitions--; notifyListeners() }
    private fun notifyListeners() {
        listeners.removeAll { it.get() == null }
        listeners.forEach { it.get()?.transition(transitions > 0) }
    }
}
