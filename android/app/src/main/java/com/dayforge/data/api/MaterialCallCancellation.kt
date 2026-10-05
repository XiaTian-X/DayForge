package com.dayforge.data.api

import okhttp3.Call

/** One material request owns both its original call and any synchronous 401 refresh call. */
internal class MaterialCallCancellation {
    private val calls = mutableSetOf<Call>()
    private var cancelled = false
    fun register(call: Call) = synchronized(this) { if (cancelled) call.cancel() else calls.add(call); Unit }
    fun unregister(call: Call) = synchronized(this) { calls.remove(call); Unit }
    fun cancel() {
        val owned = synchronized(this) { cancelled = true; calls.toList().also { calls.clear() } }
        owned.forEach(Call::cancel)
    }
}
