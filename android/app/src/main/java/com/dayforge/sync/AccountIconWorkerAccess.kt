package com.dayforge.sync

import com.dayforge.data.local.DatabaseRuntimeHost
import com.dayforge.data.repository.ReplicaPresentationRepository
import com.dayforge.domain.service.AccountIconTransferService
import com.dayforge.domain.service.IconMaterialOutcome
import javax.inject.Inject
import javax.inject.Singleton

/** Finite worker lifetime. Material dependencies are requested only after the original
 * replica check; the selected business graph cannot close during its HTTP/file cleanup. */
@Singleton
class AccountIconWorkerAccess private constructor(private val host: DatabaseRuntimeHost?,
    private val fixedReplica: ReplicaPresentationRepository?) {
    internal constructor(replica: ReplicaPresentationRepository) : this(null, replica)
    @Inject internal constructor(host: DatabaseRuntimeHost) : this(host, null)
    internal suspend fun synchronize(materials: () -> AccountIconTransferService): IconMaterialOutcome =
        if (host == null) run(requireNotNull(fixedReplica), materials)
        else host.withGraph(DatabaseRuntimeHost.Work.COMMAND) { run(it.replicaPresentationRepository, materials) }
    private suspend fun run(replica: ReplicaPresentationRepository, materials: () -> AccountIconTransferService): IconMaterialOutcome {
        if (!replica.isOrdinaryOpen()) return IconMaterialOutcome.ATTENTION
        return materials().synchronize()
    }
}
