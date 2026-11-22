package com.dayforge.data.repository

import com.dayforge.data.history.HistoryBlockCodec
import com.dayforge.data.local.entity.CountCommandRecoveryRootEntity
import com.dayforge.domain.model.isContractUuid
import kotlinx.serialization.json.*

/** Exactly one mandatory storage root. Mode is authenticated, never guessed from null fields.
 * EMPTY attests initialization only; it is replaced, not retained, when an account is bound.
 */
internal object CountCommandRootCodec {
    const val RETAINED_FORMAT = 3
    const val RETAINED_VALIDATOR = "count-command-recovery/retained-legacy-v3"
    enum class Mode { EMPTY, LEGACY_UNENROLLED, ACCOUNT_BOUND, ACCOUNT_BOUND_RETAINED }
    fun bound(mode: Mode) = mode == Mode.ACCOUNT_BOUND || mode == Mode.ACCOUNT_BOUND_RETAINED
    fun admits(header: Header, commandFormat: Int) = commandFormat == CountCommandRecords.FORMAT ||
        commandFormat == CountCommandRecords.METRIC_FORMAT && header.format in setOf(CountCommandRecords.METRIC_FORMAT, RETAINED_FORMAT)
    enum class Source { NEW_DATABASE, MIGRATION_19_20, AUTHORIZED_CLEAR_REPLACE }
    data class Registration(val previousNamespaceHash: String, val installationId: String) {
        init { require(hashValid(previousNamespaceHash) && isContractUuid(installationId)) }
    }
    data class Header(val mode: Mode, val storageId: String, val datasetId: String, val source: Source, val schemaHash: String,
        val namespace: CountCommandRecords.Namespace?, val catalog: HistoryBlockCodec.Ref?,
        val pending: CountCommandPendingRules.Frontier, val legacyCount: Long?, val legacyHash: String?, val revision: Long,
        val registration: Registration? = null, val format: Int = CountCommandRecords.FORMAT,
        val validator: String = CountCommandRecords.VALIDATOR) {
        init {
            require(format == CountCommandRecords.FORMAT && validator == CountCommandRecords.VALIDATOR ||
                format == CountCommandRecords.METRIC_FORMAT && validator == CountCommandRecords.METRIC_VALIDATOR ||
                format == RETAINED_FORMAT && validator == RETAINED_VALIDATOR)
            require(mode != Mode.LEGACY_UNENROLLED || format == CountCommandRecords.FORMAT)
            require((format == RETAINED_FORMAT) == (mode == Mode.ACCOUNT_BOUND_RETAINED))
            require(isContractUuid(storageId) && isContractUuid(datasetId) && hashValid(schemaHash) && revision > 0)
            when (mode) {
                Mode.EMPTY -> require(namespace == null && registration == null && catalog == null && pending.count == 0L && legacyCount == null && legacyHash == null)
                Mode.LEGACY_UNENROLLED -> require(namespace == null && registration == null && catalog == null && pending.count == 0L &&
                    legacyCount != null && legacyCount > 0 && legacyHash != null && hashValid(legacyHash))
                Mode.ACCOUNT_BOUND, Mode.ACCOUNT_BOUND_RETAINED -> {
                    require(namespace != null && namespace.storage == storageId && namespace.dataset == datasetId && catalog != null)
                    if (mode == Mode.ACCOUNT_BOUND) require(legacyCount == null && legacyHash == null)
                    else require(source == Source.MIGRATION_19_20 && revision >= 2L && legacyCount != null &&
                        legacyCount > 0L && legacyHash != null && hashValid(legacyHash))
                    require(catalog.range == com.dayforge.data.history.HistoryBlockPlanner.LeafRange(CountCommandDirectory.unit(namespace.catalogBinding), null, null) &&
                        catalog.height in 0..HistoryBlockCodec.MAX_DEPTH && catalog.count >= 0 && pending.count <= catalog.count)
                    registration?.let { transition ->
                        require(namespace.server != null && namespace.epoch != null && namespace.device != null)
                        require(transition.previousNamespaceHash in setOf(namespace.copy(server = null, epoch = null, device = null).hash,
                            namespace.copy(server = null, epoch = null).hash))
                    }
                }
            }
        }
        val namespaceHash: String? get() = namespace?.hash
        val accountBinding: String? get() = namespace?.catalogBinding
        fun entity(mac: ByteArray) = CountCommandRecoveryRootEntity(1, mode.name, storageId, datasetId, source.name, namespaceHash,
            namespace?.let { CountCommandRecords.canonical(it.body()) }, accountBinding,
            registration?.previousNamespaceHash, registration?.installationId, format, validator, schemaHash, catalog?.digest, catalog?.height,
            catalog?.count, pending.head, pending.tail, pending.count, legacyCount, legacyHash, revision, mac.copyOf())
    }

    fun body(header: Header): ByteArray = CountCommandRecords.canonical(buildJsonObject {
        put("domain", "DayForge/count-command-recovery-root"); put("format", header.format)
        put("validator", header.validator); put("mode", header.mode.name); put("storage", header.storageId); put("dataset", header.datasetId)
        put("source", header.source.name); put("schema", header.schemaHash); put("revision", header.revision)
        put("namespace", header.namespace?.body() ?: JsonNull)
        put("account_binding", header.accountBinding?.let(::JsonPrimitive) ?: JsonNull)
        put("registration_previous", header.registration?.previousNamespaceHash?.let(::JsonPrimitive) ?: JsonNull)
        put("registration_installation", header.registration?.installationId?.let(::JsonPrimitive) ?: JsonNull)
        put("index", "count-command-v1/catalog/1970-01/open-bounds")
        put("catalog_hash", header.catalog?.digest?.let(::JsonPrimitive) ?: JsonNull)
        put("catalog_height", header.catalog?.height?.let(::JsonPrimitive) ?: JsonNull)
        put("catalog_count", header.catalog?.count?.let(::JsonPrimitive) ?: JsonNull)
        put("pending_head", header.pending.head?.let(::JsonPrimitive) ?: JsonNull)
        put("pending_tail", header.pending.tail?.let(::JsonPrimitive) ?: JsonNull); put("pending_count", header.pending.count)
        if (header.mode == Mode.ACCOUNT_BOUND_RETAINED) {
            put("command_formats", JsonArray(listOf(JsonPrimitive(1), JsonPrimitive(2))))
            put("retained", buildJsonObject {
                put("format", 1); put("kind", "uninterpreted-v19-outcome-native-stream")
                put("table", "count_command_results")
                put("columns", JsonArray(listOf("commandId", "inputHash", "factUuid", "requestId", "resultJson", "state").map(::JsonPrimitive)))
                put("order", "commandId-original-binary"); put("codec", "count-command-unenrolled-native/v1")
                put("count", requireNotNull(header.legacyCount)); put("hash", requireNotNull(header.legacyHash))
                put("origin", buildJsonObject {
                    put("format", CountCommandRecords.FORMAT); put("validator", CountCommandRecords.VALIDATOR)
                    put("mode", Mode.LEGACY_UNENROLLED.name); put("source", Source.MIGRATION_19_20.name); put("revision", 1)
                })
            })
        } else {
            put("legacy_count", header.legacyCount?.let(::JsonPrimitive) ?: JsonNull)
            put("legacy_hash", header.legacyHash?.let(::JsonPrimitive) ?: JsonNull)
        }
    }).toByteArray(Charsets.UTF_8)

    fun fromEntity(row: CountCommandRecoveryRootEntity): Header {
        require(row.id == 1 && row.mac.size == 32 &&
            (row.format == CountCommandRecords.FORMAT && row.validator == CountCommandRecords.VALIDATOR ||
                row.format == CountCommandRecords.METRIC_FORMAT && row.validator == CountCommandRecords.METRIC_VALIDATOR ||
                row.format == RETAINED_FORMAT && row.validator == RETAINED_VALIDATOR))
        val namespace = row.namespaceJson?.let { CountCommandRecords.Namespace.fromJson(CountCommandRecords.parse(it.toByteArray(Charsets.UTF_8), 2048, {})) }
        require(row.namespaceHash == namespace?.hash && row.accountBindingHash == namespace?.catalogBinding &&
            row.namespaceJson == namespace?.let { CountCommandRecords.canonical(it.body()) })
        val registration = row.registrationPreviousHash?.let { Registration(it, requireNotNull(row.registrationInstallationId)) }
        require((row.registrationPreviousHash == null) == (row.registrationInstallationId == null))
        val count = listOf(row.catalogHash, row.catalogHeight, row.catalogCount).count { it != null }
        require(count == 0 || count == 3)
        val catalog = row.catalogHash?.let { digest ->
            require(hashValid(digest))
            HistoryBlockCodec.Ref(com.dayforge.data.history.HistoryBlockPlanner.LeafRange(
                CountCommandDirectory.unit(requireNotNull(namespace).catalogBinding), null, null), requireNotNull(row.catalogHeight), requireNotNull(row.catalogCount), digest)
        }
        return Header(Mode.valueOf(row.mode), row.storageId, row.datasetId, Source.valueOf(row.initializationSource), row.schemaHash,
            namespace, catalog, CountCommandPendingRules.Frontier(row.pendingHeadId, row.pendingTailId, row.pendingCount),
            row.legacyCount, row.legacyHash, row.revision, registration, row.format, row.validator)
    }

    fun requireNext(old: Header, fresh: Header) {
        require(old.format == fresh.format && old.validator == fresh.validator) { "COMMAND_FORMAT_TRANSITION_REQUIRES_PLAN" }
        require(old.storageId == fresh.storageId && old.datasetId == fresh.datasetId && old.source == fresh.source && old.schemaHash == fresh.schemaHash &&
            fresh.revision == Math.addExact(old.revision, 1L))
        when (old.mode) {
            Mode.EMPTY -> require(fresh.mode == Mode.ACCOUNT_BOUND && requireNotNull(fresh.catalog).count == 0L && fresh.pending.count == 0L && fresh.registration == null)
            Mode.LEGACY_UNENROLLED -> error("COMMAND_LEGACY_NOT_ENROLLED")
            Mode.ACCOUNT_BOUND, Mode.ACCOUNT_BOUND_RETAINED -> {
                require(fresh.mode == old.mode && old.accountBinding == fresh.accountBinding &&
                    old.legacyCount == fresh.legacyCount && old.legacyHash == fresh.legacyHash)
                if (old.namespace == fresh.namespace) require(old.registration == fresh.registration &&
                    requireNotNull(fresh.catalog).count >= requireNotNull(old.catalog).count)
                else require(requireNotNull(old.namespace).permitsInitialRegistration(requireNotNull(fresh.namespace)) && old.registration == null &&
                    fresh.registration?.previousNamespaceHash == old.namespaceHash && fresh.catalog == old.catalog && fresh.pending == old.pending)
            }
        }
    }
    /** One closed initial v19 commitment becomes a current new-command namespace. No old
     * outcome is interpreted, attributed, consumed, or admitted as an execution reference. */
    internal fun requireRetainedEnrollment(old: Header, fresh: Header) {
        require(old.format == CountCommandRecords.FORMAT && old.validator == CountCommandRecords.VALIDATOR &&
            old.mode == Mode.LEGACY_UNENROLLED && old.source == Source.MIGRATION_19_20 && old.revision == 1L)
        require(fresh == old.copy(mode = Mode.ACCOUNT_BOUND_RETAINED, format = RETAINED_FORMAT,
            validator = RETAINED_VALIDATOR, namespace = requireNotNull(fresh.namespace),
            catalog = requireNotNull(fresh.catalog), revision = 2L) && requireNotNull(fresh.catalog).count == 0L)
    }
    /** Only the dedicated bound-root upgrade owner may request this transition. */
    internal fun requireMetricUpgrade(old: Header, fresh: Header) {
        require(old.mode == Mode.ACCOUNT_BOUND && old.format == CountCommandRecords.FORMAT &&
            old.validator == CountCommandRecords.VALIDATOR)
        require(fresh == old.copy(format = CountCommandRecords.METRIC_FORMAT,
            validator = CountCommandRecords.METRIC_VALIDATOR, revision = Math.addExact(old.revision, 1))) {
            "COMMAND_METRIC_ROOT_UPGRADE_CHANGED"
        }
    }
    private fun hashValid(value: String) = value.length == 64 && value.all { it in "0123456789abcdef" }
}
