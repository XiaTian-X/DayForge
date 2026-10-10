package com.dayforge.domain.service

import com.dayforge.data.api.NextSyncHttpFailure
import com.dayforge.data.api.NextSyncReplyInvalid
import java.io.IOException
import java.util.Collections
import java.util.IdentityHashMap

/** Received HTTP/protocol failures are not loss of connectivity, even inside an I/O wrapper. */
internal fun Throwable?.isSyncTransportFailure(): Boolean {
    var current = this
    var transport = false
    val visited = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
    while (current != null && visited.add(current)) {
        if (current is NextSyncHttpFailure || current is NextSyncReplyInvalid) return false
        if (current is IOException) transport = true
        current = current.cause
    }
    return transport
}
