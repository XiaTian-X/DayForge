package com.dayforge.domain.service

import com.dayforge.data.appearance.AccountIconHttp
import javax.inject.Inject
import javax.inject.Singleton

/** Production domain entry; the coordinated v5 scheduler will invoke this, never the legacy v4 sync. */
@Singleton
class AccountIconTransferService @Inject constructor(
    private val icons: AccountIconController, private val http: AccountIconHttp
) {
    internal suspend fun transfer() = icons.transfer(http)
}
