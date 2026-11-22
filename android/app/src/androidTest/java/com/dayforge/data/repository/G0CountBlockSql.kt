package com.dayforge.data.repository

import com.dayforge.data.local.LocalCoreWriteAccess
import com.dayforge.data.local.LocalSyncAccess
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/** Explicit B2 storage profile. Only the sealed owner may write these tables together
 * with the business delta. These rows alone do NOT authorize cold recovery: B2 has no
 * persistent MAC issuer/loader and a reopened owner still runs the original validator. */
internal object G0CountBlockSql {
    const val nodes="g0_count_block_nodes"
    const val root="g0_count_block_root"
    val tables=mapOf(nodes to listOf("digest","body"),
        root to listOf("slot","binding","height","members","digest","revision"))
    const val createNodes="CREATE TABLE g0_count_block_nodes (digest TEXT NOT NULL PRIMARY KEY, body BLOB NOT NULL) WITHOUT ROWID"
    const val createRoot="CREATE TABLE g0_count_block_root (slot INTEGER NOT NULL PRIMARY KEY CHECK(slot=1), binding TEXT NOT NULL, height INTEGER NOT NULL, members INTEGER NOT NULL, digest TEXT NOT NULL, revision INTEGER NOT NULL) WITHOUT ROWID"
    const val insertNode="INSERT OR ABORT INTO g0_count_block_nodes (digest,body) VALUES (?,?)"
    const val insertRoot="INSERT OR ABORT INTO g0_count_block_root (slot,binding,height,members,digest,revision) VALUES (?,?,?,?,?,?)"
    const val updateRoot="UPDATE g0_count_block_root SET binding=?,height=?,members=?,digest=?,revision=? WHERE slot=1 AND binding=? AND digest=? AND revision=?"
    const val readRoot="SELECT * FROM main.g0_count_block_root"
    const val countNodes="SELECT count(*) FROM main.g0_count_block_nodes"
    const val readNode="SELECT typeof(body),CASE WHEN typeof(body)='blob' THEN length(body) ELSE NULL END,CASE WHEN typeof(body)='blob' THEN CASE WHEN length(body)<=CAST(? AS INTEGER) THEN body ELSE NULL END ELSE NULL END FROM main.g0_count_block_nodes WHERE digest=?"
    val writes=mapOf(insertNode to "SV",insertRoot to "ISIISI",updateRoot to "SIISISSI")
    val reads=mapOf(readRoot to "",countNodes to "",readNode to "IS")

    /** Stable existing identity only. Session generation, permissions, date/timezone,
     * selected objects and experiment limits remain runtime qualifications, not leaf IDs.
     * No new server/replica identity or rollback guarantee is introduced by this hash. */
    fun binding(core:LocalCoreWriteAccess,sync:LocalSyncAccess):String {
        require(core.session==sync.session && core.capturedDeviceId==sync.deviceId &&
            core.capabilities==sync.capabilities)
        require(!core.session.serverInstanceId.isNullOrBlank() && !core.session.syncEpoch.isNullOrBlank() &&
            !core.capturedDeviceId.isNullOrBlank())
        val bytes=ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            for(value in listOf("G0-count-incremental-v1/plain-validator-2026-10-21",
                core.session.authentication.userId,core.session.serverInstanceId,core.session.syncEpoch,core.capturedDeviceId)) {
                val text=requireNotNull(value);out.writeInt(text.length);text.forEach {out.writeChar(it.code)}
            }
        }
        return G0CountCertificate.hash(bytes.toByteArray())
    }
}
