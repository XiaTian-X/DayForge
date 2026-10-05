package com.dayforge.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.dayforge.domain.service.AccountIconTransferService
import com.dayforge.domain.service.IconMaterialOutcome
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/** No authority or credentials in WorkRequest data; capture at execution, not scheduling. */
class AccountIconWorker internal constructor(context: Context, params: WorkerParameters,
    private val synchronize: suspend () -> IconMaterialOutcome
) : CoroutineWorker(context, params) {
    constructor(context: Context, params: WorkerParameters) : this(context, params, {
        EntryPointAccessors.fromApplication(context.applicationContext, Dependencies::class.java)
            .materials().synchronize()
    })

    override suspend fun doWork(): Result {
        // Cancellation intentionally escapes: WorkManager stops the actual structured owner.
        val state = synchronize()
        return when (state) {
            IconMaterialOutcome.RETRY -> Result.retry()
            IconMaterialOutcome.ATTENTION -> Result.failure(workDataOf(STATUS to state.name))
            else -> Result.success(workDataOf(STATUS to state.name))
        }
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Dependencies { fun materials(): AccountIconTransferService }

    internal companion object { const val STATUS = "material_status" }
}
