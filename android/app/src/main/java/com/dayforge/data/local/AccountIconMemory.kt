package com.dayforge.data.local

import java.lang.ref.WeakReference
import javax.inject.Inject
import javax.inject.Singleton

/** Only credential-free memory invalidation; never opens a database or touches files. */
@Singleton
class AccountIconMemory @Inject constructor() {
    internal interface Cache {
        /** Short, non-suspending memory operation. Must not call back into this registry. */
        fun authenticationTransition(blocked: Boolean)
    }

    private val caches = mutableListOf<WeakReference<Cache>>()
    private var transitions = 0

    @Synchronized internal fun register(cache: Cache) {
        caches.removeAll { it.get() == null }
        caches.add(WeakReference(cache))
        if (transitions > 0) cache.authenticationTransition(true)
    }

    @Synchronized internal fun beginTransition() {
        transitions++
        notifyCaches()
    }

    @Synchronized internal fun endTransition() {
        check(transitions > 0)
        transitions--
        notifyCaches()
    }

    private fun notifyCaches() {
        val iterator = caches.iterator()
        while (iterator.hasNext()) {
            val cache = iterator.next().get()
            if (cache == null) iterator.remove() else cache.authenticationTransition(transitions > 0)
        }
    }
}
