package com.dayforge.domain.service

import com.dayforge.data.appearance.AccountIconHttp
import javax.inject.Inject
import javax.inject.Singleton
import com.dayforge.data.appearance.materialRetryable
import java.io.IOException
import kotlinx.coroutines.CancellationException

/** Independent material domain entry, never invoked through the legacy v4 business sync. */
@Singleton
class AccountIconTransferService @Inject constructor(
    private val icons: AccountIconController, private val http: AccountIconHttp
) {
    internal suspend fun transfer() = icons.transfer(http)
    internal suspend fun refreshCatalog() = icons.refreshCatalog(http)
    internal suspend fun synchronize(): IconMaterialOutcome = try { icons.synchronize(http) }
    catch (error: Exception) {
        if (error is CancellationException) throw error
        if (error is IOException && materialRetryable(error))
            IconMaterialOutcome.RETRY
        else IconMaterialOutcome.ATTENTION
    }
}
