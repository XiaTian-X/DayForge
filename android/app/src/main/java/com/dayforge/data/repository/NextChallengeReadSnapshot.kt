package com.dayforge.data.repository

import com.dayforge.data.api.dto.ChallengeMetadata
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.LocalSyncAccess
import com.dayforge.data.local.entity.NextSyncStateEntity
import java.util.Collections

/** Explicit proof hand-off inside ONE SELECT-only phase. Cannot be constructed from supplied
 * metadata, borrowed across a write/phase/Job/DB, or used as current credentials. Capturing always
 * performs the original complete raw-row + chain/hash audit. Writer final audit captures anew.
 */
internal class NextChallengeReadSnapshot private constructor(
    private val owner: NextEvidenceReadSession,
    private val database: HabitDatabase,
    private val access: LocalSyncAccess,
    private val state: NextSyncStateEntity,
    private val metadata: ChallengeMetadata
) {
    suspend fun borrow(database: HabitDatabase, access: LocalSyncAccess): Pair<NextSyncStateEntity, ChallengeMetadata> {
        check(this.database === database && this.access == access &&
            NextEvidenceReadSession.current(database) === owner) { "CHALLENGE_READ_SNAPSHOT_EXPIRED" }
        return state to metadata
    }

    companion object {
        suspend fun capture(database: HabitDatabase, access: LocalSyncAccess): NextChallengeReadSnapshot {
            val owner = NextEvidenceReadSession.current(database)
            val (state, metadata) = NextChallengeStore(database).activeInTransaction(access)
            // Do not expose mutable backing collections as a way to forge a validated proof.
            val immutable = metadata.copy(
                checkpoints = Collections.unmodifiableList(metadata.checkpoints.map { checkpoint ->
                    checkpoint.copy(records = Collections.unmodifiableList(checkpoint.records.toList()))
                }),
                births = Collections.unmodifiableList(metadata.births.toList()))
            check(NextEvidenceReadSession.current(database) === owner)
            return NextChallengeReadSnapshot(owner, database,
                access.copy(capabilities = Collections.unmodifiableSet(access.capabilities.toSet())), state, immutable)
        }
    }
}
