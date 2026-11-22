package com.dayforge.data.repository

import androidx.sqlite.db.SupportSQLiteDatabase
import com.dayforge.data.local.CountCommandV20Schema
import com.dayforge.data.local.LocalCoreWriteAccess

/** The v20 command format owns a different physical node table. This authenticated,
 * fixed-layout declaration lets COUNT maintenance keep its original single-pool mark.
 * It is not a command recovery grant and cannot make an unverified command row readable.
 * CommandDirectory loads and stores only count_command_nodes; none of the v1 command
 * codecs interprets a CountTrustIndex hash as an external node reference.
 */
internal object CountCommandGcOwnership {
    data class Anchor(val layout: String, val storage: String, val dataset: String,
        val mode: CountCommandRootCodec.Mode, val accountBinding: String?)

    data class Checked(val header: CountCommandRootCodec.Header, val anchor: Anchor)

    fun read(sql: SupportSQLiteDatabase, storage: String, schemaHash: String,
        access: LocalCoreWriteAccess, lifetime: () -> Unit): Checked {
        lifetime()
        require(sql.version == CountCommandV20Schema.VERSION && sql.inTransaction()) {
            "COUNT_GC_COMMAND_LAYOUT_INVALID"
        }
        // Scalar native type/size checks precede reading the mandatory root or its MAC.
        // This never creates a key, substitutes EMPTY or drops a malformed primary root.
        val header = CountCommandSeal().authenticate(CountCommandNativeRows(sql, lifetime).root(),
            storage, schemaHash) { CountCommandRootAnchor.read(sql).hash }.header
        when (header.mode) {
            CountCommandRootCodec.Mode.EMPTY, CountCommandRootCodec.Mode.LEGACY_UNENROLLED -> {
                for (table in listOf("count_command_intents", "count_command_status", "count_command_nodes")) {
                    sql.query("SELECT 1 FROM main.`$table` LIMIT 1").use {
                        require(!it.moveToFirst()) { "COMMAND_INITIAL_ANCHOR_CONTENT_CHANGED" }
                    }
                }
                if (header.mode == CountCommandRootCodec.Mode.EMPTY) {
                    sql.query("SELECT 1 FROM main.count_command_results LIMIT 1").use {
                        require(!it.moveToFirst()) { "COMMAND_INITIAL_ANCHOR_CONTENT_CHANGED" }
                    }
                }
                // Unenrolled old results require their original complete enrollment proof.
                // The collector's caller defers this mode; it never repeats an unbounded
                // legacy-result fingerprint in each bounded maintenance slice.
            }
            CountCommandRootCodec.Mode.ACCOUNT_BOUND, CountCommandRootCodec.Mode.ACCOUNT_BOUND_RETAINED -> {
                val stored = requireNotNull(header.namespace)
                val current = CountCommandRecords.Namespace(access.session.authentication.userId,
                    access.session.serverInstanceId, access.session.syncEpoch, access.capturedDeviceId,
                    storage, header.datasetId)
                require(stored == current || stored.permitsInitialRegistration(current)) {
                    "COUNT_GC_COMMAND_NAMESPACE_CHANGED"
                }
            }
        }
        lifetime()
        // Mutable catalog/status revisions are intentionally not part of the COUNT root
        // set. Every slice still authenticates the latest primary header. T0/T2 cannot
        // add references to the COUNT pool under this closed, versioned layout.
        return Checked(header, Anchor(CountCommandV20Schema.COUNT_NODE_OWNERSHIP, storage,
            header.datasetId, header.mode, header.accountBinding))
    }
}
