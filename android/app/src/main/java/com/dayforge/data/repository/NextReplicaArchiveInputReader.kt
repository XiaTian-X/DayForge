package com.dayforge.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.room.withTransaction
import com.dayforge.data.api.NextSyncHttp
import com.dayforge.data.appearance.*
import com.dayforge.data.local.*
import com.dayforge.domain.model.*
import com.dayforge.domain.service.AccountSessionCoordinator
import java.util.Collections
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*

/** Actual current, authority, original-request and configuration uses; not display fallback. */
internal data class ReplicaArchiveIconUse(val source: String, val identity: String, val entityType: String,
    val icon: IconReference, val oneTime: Boolean?)

/** Complete old input witness only. Durable archive/stage/fence/confirmation remain mandatory. */
internal class ReplicaArchiveInput internal constructor(val business: ReplicaBusinessProof,
    val storage: ReplicaStorageProof, val icons: IconRecoveryDatabaseProof,
    val iconFiles: AccountIconRecoverySnapshot, val themes: DeviceThemeRecoverySnapshot,
    uses: List<ReplicaArchiveIconUse>, missingAssets: Set<String>, val manifest: JsonObject) {
    val uses = Collections.unmodifiableList(uses.toList())
    val missingAssets = Collections.unmodifiableSet(missingAssets.toSet())
    val hash = syncPayloadHash(manifest.toString())
    val activationAuthorized: Boolean get() = false
    val hasReview: Boolean get() = business.hasProjectionReview || business.hasUnfinishedTimer ||
        business.localFacts.hasPendingDrafts || business.localFacts.configuration.hasUnsettledWork ||
        business.localFacts.configuration.requests.hasUnsettledRequests || icons.hasUnresolvedTransfers ||
        missingAssets.isNotEmpty() || icons.metadata.installations.any { it.validationProfile == null } ||
        themes.preferences.catalog?.slots.orEmpty().any { it.phase != ThemeInstallPhase.ACTIVE }
}

/** Sequential bounded reads in the owning callback only, never paths or account pixels after it. */
internal class ReplicaArchiveFileAccess internal constructor(private val owner: Job,
    private val authorize: suspend () -> Unit, private val icons: AccountIconRecoveryFileAccess,
    private val themes: ThemeRecoveryFileAccess) {
    @Volatile private var open = true
    internal fun close() { open = false }
    private suspend fun check() {
        currentCoroutineContext().ensureActive()
        check(open && currentCoroutineContext()[Job] === owner) { "SYNC_ARCHIVE_ACCESS_CLOSED" }
        authorize()
        check(open) { "SYNC_ARCHIVE_ACCESS_CLOSED" }
    }
    suspend fun icon(name: String): ByteArray { check(); return icons.read(name).also { check() } }
    suspend fun theme(name: String): ByteArray { check(); return themes.read(name).also { check() } }
}

/**
 * Rebuilds the entire validated old input, not just caller-supplied counts/metadata. File leases
 * precede all short account/Room transactions, in icon -> theme -> account order. Consumer file
 * I/O runs with both file leases but WITHOUT an account/Room lock. Full hashes/authority are
 * checked again; no cross-store atomicity or authorization to switch is claimed. The durable
 * workflow must additionally freeze ALL writers before recording ARCHIVE_VERIFIED.
 */
internal class NextReplicaArchiveInputReader(private val context: Context,
    private val database: HabitDatabase, private val tokens: TokenManager,
    private val sessions: AccountSessionCoordinator, private val preferences: PreferencesManager,
    private val http: NextSyncHttp, iconDatabase: AccountIconDatabase,
    private val iconMetadata: AccountIconRepository, iconTransfers: AccountIconTransfers,
    iconFiles: AccountIconFiles, appearancePreferences: DataStore<Preferences>,
    themeFiles: ThemeFileRepository, builtIns: BuiltInThemes) {
    init {
        require(tokens.usesPreferences(appearancePreferences) && preferences.usesPreferences(appearancePreferences) &&
            iconMetadata.usesAccess(tokens, sessions) && iconMetadata.usesDatabase(iconDatabase) &&
            iconTransfers.usesMetadata(iconMetadata) && iconTransfers.usesFiles(iconFiles)) { "SYNC_ARCHIVE_STORE_MISMATCH" }
    }
    private val iconDatabaseReader = AccountIconRecoveryDatabase(context, iconDatabase, iconMetadata, iconTransfers)
    private val iconFileReader = AccountIconRecoveryFiles(iconMetadata, iconFiles)
    private val themeReader = DeviceThemeRecoveryReader(appearancePreferences, themeFiles, builtIns)

    suspend fun <T> inspect(captured: LocalSyncAccess, expectedStorageHash: String,
        block: suspend (ReplicaArchiveInput, ReplicaArchiveFileAccess) -> T): T {
        val original = captured.copy(capabilities = Collections.unmodifiableSet(captured.capabilities.toSet()))
        suspend fun authorize() {
            check(tokens.localSyncAccess() == original && tokens.syncAuthenticationSnapshot(original) != null) { "SYNC_ACCOUNT_CHANGED" }
        }
        authorize()
        check(database.isOpen) { "SYNC_ARCHIVE_DATABASE_NOT_OPEN" }
        val iconContext = iconMetadata.capture()
        require(iconContext.access == LocalIconAccess(original.session, requireNotNull(original.deviceId),
            requireNotNull(original.capabilityRevision), "structure.write" in original.capabilities)) { "SYNC_ARCHIVE_SCOPE_CHANGED" }
        // Establish an already-open, valid icon provider BEFORE any file reader can open a lazy DB.
        val originalIcons = iconDatabaseReader.read(iconContext)
        val schema = NextReplicaSchemaValidator(context, database, tokens, sessions).validate(original, expectedStorageHash)
        val requests = NextReplicaRequestProofReader(database, tokens, sessions, preferences, http).read(schema)
        val configuration = NextReplicaConfigProofReader(database, tokens, sessions).read(requests)
        val localFacts = NextReplicaLocalFactProofReader(database, tokens, sessions).read(configuration)
        val businessReader = NextReplicaBusinessProofReader(database, tokens, sessions, preferences, http)
        val business = businessReader.read(localFacts)
        val result = iconFileReader.inspect(iconContext) { files, iconAccess ->
            require(files.metadata == originalIcons.metadata) { "SYNC_ARCHIVE_ICON_INPUT_CHANGED" }
            themeReader.inspect { themes, themeAccess ->
                authorize()
                iconDatabaseReader.revalidate(originalIcons)
                val (storage, uses) = sessions.exclusive { database.withTransaction {
                    authorize()
                    val raw = NextReplicaStorageProof(database).readInTransaction()
                    require(raw.hash == requests.storageHash) { "SYNC_RECOVERY_PREVIEW_CHANGED" }
                    raw to materialUses(business)
                } }
                val assets = originalIcons.metadata.assets.associateBy { it.assetId }
                val missing = uses.mapNotNull { (it.icon as? IconReference.Asset)?.assetId }.filterTo(linkedSetOf()) { it !in assets }
                for (use in uses) if (use.oneTime != null) {
                    val asset = (use.icon as? IconReference.Asset)?.assetId?.let(assets::get)
                    if (use.icon is IconReference.Role || asset != null)
                        require(iconAllowed(use.icon, use.oneTime, asset)) { "SYNC_ARCHIVE_ICON_PURPOSE_INVALID" }
                }
                val input = ReplicaArchiveInput(business, storage, originalIcons, files, themes, uses, missing,
                    manifest(original, business, storage, originalIcons, files, themes, uses, missing))
                val access = ReplicaArchiveFileAccess(requireNotNull(currentCoroutineContext()[Job]), ::authorize, iconAccess, themeAccess)
                try {
                    val value = block(input, access)
                    iconDatabaseReader.revalidate(originalIcons)
                    val current = businessReader.read(localFacts)
                    require(current.records == business.records && current.unconfirmed == business.unconfirmed &&
                        current.projectionGaps == business.projectionGaps && current.hasUnfinishedTimer == business.hasUnfinishedTimer) {
                        "SYNC_RECOVERY_PREVIEW_CHANGED"
                    }
                    authorize()
                    value
                } finally { access.close() }
            }
        }
        authorize()
        return result
    }

    /** All retained originals/authority, including replaced/deleted/unreferenced identities. */
    private suspend fun materialUses(business: ReplicaBusinessProof): List<ReplicaArchiveIconUse> {
        check(database.inTransaction())
        val uses = linkedMapOf<Triple<String, String, String>, ReplicaArchiveIconUse>()
        fun add(source: String, id: String, type: String, appearance: JsonElement, once: Boolean?) {
            val icon = Json.decodeFromJsonElement<ObjectAppearance>(appearance).icon
            val key = Triple(source, id, type)
            require(uses.put(key, ReplicaArchiveIconUse(source, id, type, icon, once)) == null) { "SYNC_ARCHIVE_ICON_USE_DUPLICATE" }
        }
        fun body(source: String, id: String, type: String, body: JsonObject) {
            if (type !in setOf("plan_node", "metric")) return
            val activity = body["activity"] as? JsonObject
            add(source, id, type, body.getValue("appearance"), type == "plan_node" &&
                activity?.get("completion_policy") == JsonPrimitive("one_and_done"))
        }
        for ((key, raw) in business.records) if (key.kind in setOf("plan_node", "metric")) {
            currentCoroutineContext().ensureActive()
            val text = raw.getValue("appearance").jsonObject.getValue("value").jsonPrimitive.content
            val once = key.kind == "plan_node" && raw.getValue("completionPolicy").jsonObject.getValue("value") == JsonPrimitive("one_and_done")
            add("current", key.identity, key.kind, Json.parseToJsonElement(text), once)
        }
        NextAuthorityShadowReader(database).readInTransaction { change ->
            if (change.operation == "upsert") body("authority", change.entityUuid, change.entityType, change.payload)
        }
        val requests = business.localFacts.configuration.requests
        for (key in requests.states.keys.filter { it.kind == NEXT_OPERATION }.sortedBy { it.requestId }) {
            currentCoroutineContext().ensureActive()
            val origin = requireNotNull(database.nextRequestDao().origin(key.kind, key.requestId))
            if (restartProposal(origin.intentJson) == null) {
                val operation = decodeNextOperationIntent(origin.intentJson)
                if (operation.action == "upsert") body("request", key.requestId, operation.entityType, operation.payload)
            }
        }
        for (journal in business.localFacts.configuration.journals) for (id in journal.allocatedAssets.sorted()) {
            val key = Triple("configuration", journal.importId, id)
            require(uses.put(key, ReplicaArchiveIconUse("configuration", journal.importId, id, IconReference.Asset(id), null)) == null)
        }
        return Collections.unmodifiableList(uses.values.toList())
    }

    private fun manifest(original: LocalSyncAccess, business: ReplicaBusinessProof, storage: ReplicaStorageProof, icons: IconRecoveryDatabaseProof,
        files: AccountIconRecoverySnapshot, themes: DeviceThemeRecoverySnapshot,
        uses: List<ReplicaArchiveIconUse>, missing: Set<String>) = buildJsonObject {
        put("format", "dayforge.replica-archive-input"); put("format_version", 1)
        put("account_id", original.session.authentication.userId); put("authentication_generation", original.session.authentication.generation)
        put("server_instance_id", original.session.serverInstanceId); put("sync_epoch", original.session.syncEpoch)
        put("device_id", original.deviceId); put("capability_revision", original.capabilityRevision)
        put("capabilities", JsonArray(original.capabilities.sorted().map(::JsonPrimitive)))
        put("business_hash", storage.hash); put("icon_database_hash", icons.hash)
        put("review", buildJsonObject {
            put("unfinished_timer", business.hasUnfinishedTimer)
            put("unconfirmed", buildJsonArray { for (key in business.unconfirmed.sortedWith(compareBy({ it.kind }, { it.identity })))
                add(buildJsonObject { put("kind", key.kind); put("identity", key.identity) }) })
            put("projection_gaps", buildJsonArray { for (key in business.projectionGaps.sortedWith(compareBy({ it.kind }, { it.identity })))
                add(buildJsonObject { put("kind", key.kind); put("identity", key.identity) }) })
            put("pending_drafts", JsonArray(business.localFacts.prompts.filter { it.pending }.map { JsonPrimitive(it.row.eventUuid) }))
            put("pending_configuration", JsonArray(business.localFacts.configuration.journals.filter { it.hasUnsettledWork }.map { JsonPrimitive(it.importId) }))
            put("request_states", buildJsonArray { for ((key, state) in business.localFacts.configuration.requests.states.entries
                .sortedWith(compareBy({ it.key.kind }, { it.key.requestId }))) add(buildJsonObject {
                put("kind", key.kind); put("request_id", key.requestId); put("state", state.name)
            }) })
            put("pending_icon_transfers", JsonArray(icons.remote.transfers.filter { it.state != "complete" }.map { JsonPrimitive(it.operationId) }))
        })
        put("icon_files", buildJsonArray { for (file in files.files) add(buildJsonObject {
            put("name", file.name); put("blob_hash", file.blobHash); put("operation_id", file.operationId)
            put("temporary", file.temporary); put("byte_length", file.byteLength); put("content_hash", file.contentHash)
        }) })
        put("theme_selection", themes.preferences.selectionRaw?.let(::JsonPrimitive) ?: JsonNull)
        put("theme_catalog", themes.preferences.catalogRaw?.let(::JsonPrimitive) ?: JsonNull)
        put("theme_files", buildJsonArray { for (file in themes.files) add(buildJsonObject {
            put("name", file.name); put("theme_id", file.version.themeId); put("revision", file.version.revision)
            put("operation_id", file.operationId); put("temporary", file.temporary)
            put("byte_length", file.byteLength); put("content_hash", file.contentHash)
        }) })
        put("icon_uses", buildJsonArray { for (use in uses) add(buildJsonObject {
            put("source", use.source); put("identity", use.identity); put("entity_type", use.entityType)
            put("icon", Json.encodeToJsonElement(use.icon)); put("one_time", use.oneTime?.let(::JsonPrimitive) ?: JsonNull)
        }) })
        put("missing_assets", JsonArray(missing.sorted().map(::JsonPrimitive)))
    }
}
