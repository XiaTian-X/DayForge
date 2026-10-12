package com.dayforge.data.local

import java.util.UUID

/** Contract-shaped journal sentinels, NOT authenticated candidate/input/archive verification. */
internal suspend fun TokenManager.replicaStageFixture(sourceHash: String = "a".repeat(64)): ReplicaSwitchStage {
    val installation = getOrCreateInstallationId()
    val access = requireNotNull(localSyncAccess())
    return ReplicaSwitchStage(UUID.randomUUID().toString(), access.session.authentication.userId,
        requireNotNull(access.session.serverInstanceId), requireNotNull(access.session.syncEpoch), UUID.randomUUID().toString(),
        access.session.authentication.generation, requireNotNull(access.deviceId), requireNotNull(access.capabilityRevision),
        access.capabilities.sorted(), installation, sourceHash, "b".repeat(64), "c".repeat(64), "d".repeat(64), 20)
}
