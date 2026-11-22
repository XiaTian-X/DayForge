package com.dayforge.data.repository

import com.dayforge.data.history.HistoryBlockCodec
import java.util.Collections

/** Fixed U -> retained-bound conversion. The only old content commitment is copied from
 * the authenticated initial v19 root; observed native values never choose its replacement. */
internal class CountCommandRetainedEnrollmentPlan private constructor(
    private val original: CountCommandSeal.Authenticated,
    val header: CountCommandRootCodec.Header,
    blocks: Map<String, HistoryBlockCodec.Bytes>
) {
    val blocks: Map<String, HistoryBlockCodec.Bytes> = Collections.unmodifiableMap(LinkedHashMap(blocks))
    fun headerForSigning(old: CountCommandSeal.Authenticated): CountCommandRootCodec.Header {
        require(old.header == original.header && old.native().mac.contentEquals(original.native().mac))
        CountCommandRootCodec.requireRetainedEnrollment(old.header, header)
        return header
    }
    companion object {
        fun create(old: CountCommandSeal.Authenticated, namespace: CountCommandRecords.Namespace): CountCommandRetainedEnrollmentPlan {
            val before = old.header
            require(before.mode == CountCommandRootCodec.Mode.LEGACY_UNENROLLED &&
                before.source == CountCommandRootCodec.Source.MIGRATION_19_20 && before.revision == 1L &&
                namespace.storage == before.storageId && namespace.dataset == before.datasetId)
            val built = CountCommandDirectory.empty(namespace.catalogBinding)
            val fresh = before.copy(mode = CountCommandRootCodec.Mode.ACCOUNT_BOUND_RETAINED,
                namespace = namespace, catalog = built.root, revision = 2L,
                format = CountCommandRootCodec.RETAINED_FORMAT, validator = CountCommandRootCodec.RETAINED_VALIDATOR)
            CountCommandRootCodec.requireRetainedEnrollment(before, fresh)
            return CountCommandRetainedEnrollmentPlan(old, fresh, built.blocks)
        }
    }
}
