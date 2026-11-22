package com.dayforge.data.local

import com.dayforge.data.repository.BusinessSyncCompletion
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex

/** Application identity, never replaced with a Room graph. Original v5 lock order is unchanged. */
@Singleton
class DatabaseSyncCoordination @Inject constructor() {
    internal val business = Mutex()
    internal val next = Mutex()
    internal val completed = MutableStateFlow(BusinessSyncCompletion(null, 0))
}
