package com.dayforge.data.repository

import android.database.Cursor
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.LocalDataSession
import com.dayforge.data.local.entity.CompletionEntity
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.model.HabitType
import com.dayforge.domain.model.ChallengeRoundHead
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.serialization.json.*

/** Ordinary immutable inputs only, never retained UI authority or a certificate. Locator
 * metadata is re-authenticated by T0 under its real fresh account transaction; a stale or
 * damaged preliminary locator cannot authorize a write or migrate an old intent to a dataset.
 */
internal object CountCommandInputs {
    internal suspend fun locator(database: HabitDatabase, session: LocalDataSession, device: String?): CountCommandRecords.Namespace =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        database.openHelper.writableDatabase.query("SELECT storageId,datasetId FROM count_command_recovery_roots WHERE id=1 LIMIT 2").use { c ->
            require(c.moveToFirst() && c.getType(0) == Cursor.FIELD_TYPE_STRING && c.getType(1) == Cursor.FIELD_TYPE_STRING)
            CountCommandRecords.Namespace(session.authentication.userId, session.serverInstanceId, session.syncEpoch,
                device, c.getString(0), c.getString(1)).also { _ -> require(!c.moveToNext()) }
        }

    }

    suspend fun add(database: HabitDatabase, commandId: String, habit: HabitEntity, authority: ObjectEditAuthority,
        occurredAt: Instant, zone: ZoneId, quantity: Int, widgetClaim: WidgetFactClaim? = null): CountCommandRecords.Intent {
        require(habit.habitType == HabitType.COUNTING && habit.appearance != null && habit.completionPolicy == "recurring" &&
            authority.type == "plan_node" && authority.uuid == habit.uuid && authority.original == NextStructureMapper.writePlan(habit) &&
            authority.completionDeviceCaptured)
        return encode(commandId, locator(database, authority.session, authority.capturedDeviceId), authority.session,
            CountCommandRecords.Action.COUNT_ADD, CountCommandRecords.Capture.EXECUTION_INPUT, buildJsonObject {
                put("habit_uuid", habit.uuid); put("habit_id", habit.id); put("widget_claim", widget(widgetClaim)); put("quantity", quantity); put("occurred_at", occurredAt.toString())
                put("timezone", zone.id); put("local_date", occurredAt.atZone(zone).toLocalDate().toString())
                put("plan", authority.original); put("round", round(authority.rounds, habit.uuid))
                put("reserved_fact_uuid", UUID.randomUUID().toString())
            })
    }

    suspend fun undo(database: HabitDatabase, commandId: String, habit: HabitEntity, authority: ObjectEditAuthority,
        target: CompletionEntity, widgetClaim: WidgetFactClaim? = null): CountCommandRecords.Intent {
        require(habit.habitType == HabitType.COUNTING && habit.appearance != null && habit.completionPolicy == "recurring" &&
            authority.type == "plan_node" && authority.uuid == habit.uuid && authority.original == NextStructureMapper.writePlan(habit) &&
            authority.completionDeviceCaptured && target.habitId == habit.id && target.habitUuid == habit.uuid)
        return encode(commandId, locator(database, authority.session, authority.capturedDeviceId), authority.session,
            CountCommandRecords.Action.COUNT_UNDO, CountCommandRecords.Capture.EXECUTION_INPUT, buildJsonObject {
                put("habit_uuid", habit.uuid); put("habit_id", habit.id); put("widget_claim", widget(widgetClaim)); put("target_fact_uuid", target.uuid)
                put("target_native", CountCommandProducerPlan.frozenCompletion(target))
                put("plan", authority.original); put("round", round(authority.rounds, habit.uuid))
                put("reserved_revert_uuid", UUID.randomUUID().toString()); put("capture_policy", "original-delete-trigger-and-producer/v1")
            })
    }

    /** The Service has already made its original no-target decision. This freezes that
     * historical decision, not a command to select another target at T1 or recovery. No
     * completion/revert UUID, wire timestamp or timezone is fabricated for this branch.
     */
    suspend fun noCurrentCount(database: HabitDatabase, commandId: String, habit: HabitEntity,
        authority: ObjectEditAuthority, observed: com.dayforge.domain.model.CountSummary): CountCommandRecords.Intent {
        require(habit.habitType == HabitType.COUNTING && habit.appearance != null &&
            authority.uuid == habit.uuid && authority.original == NextStructureMapper.writePlan(habit))
        require(observed.todayQuantity <= 0L || observed.todayLatestCompletionId == null)
        val dependency = buildJsonObject {
            put("habit_uuid", habit.uuid); put("plan", authority.original); put("date", observed.today.toString())
            put("quantity", observed.todayQuantity); put("latest_id", observed.todayLatestCompletionId?.let(::JsonPrimitive) ?: JsonNull)
            put("round", head(observed.roundHead)); put("policy", observed.todayPolicy?.toJson() ?: JsonNull)
        }
        return encode(commandId, locator(database, authority.session, authority.capturedDeviceId), authority.session,
            CountCommandRecords.Action.COUNT_UNDO, CountCommandRecords.Capture.VERIFIED_NO_EFFECT, buildJsonObject {
                put("habit_uuid", habit.uuid); put("requested_completion_id", JsonNull)
                put("reason", CountCommandRecords.NoEffectReason.NO_CURRENT_COUNT.name)
                put("selector", "service-original-today-latest/v1"); put("selection", dependency)
                put("dependency_hash", CountCommandRecords.hash("no-effect-selection", CountCommandRecords.canonical(dependency)))
            })
    }

    /** Frozen only after the original explicit-ID DAO lookup returned absent. No fact,
     * revert, business time, current target, or replacement ID is invented for this branch.
     */
    suspend fun explicitTargetAbsent(database: HabitDatabase, commandId: String, requestedId: Long,
        identity: com.dayforge.domain.model.CountActionIdentity): CountCommandRecords.Intent {
        require(requestedId > 0)
        val selection = buildJsonObject { put("requested_completion_id", requestedId); put("present", false) }
        return encode(commandId, locator(database, identity.session, identity.deviceId), identity.session,
            CountCommandRecords.Action.COUNT_UNDO, CountCommandRecords.Capture.VERIFIED_NO_EFFECT, buildJsonObject {
                put("habit_uuid", identity.habitUuid); put("requested_completion_id", requestedId)
                put("reason", CountCommandRecords.NoEffectReason.EXPLICIT_TARGET_ABSENT.name)
                put("selector", "repository-explicit-id/v1"); put("selection", selection)
                put("dependency_hash", CountCommandRecords.hash("no-effect-selection", CountCommandRecords.canonical(selection)))
            })
    }

    suspend fun metrics(database: HabitDatabase, commandId: String, parent: CountCommandReceipt,
        authority: ObjectEditAuthority, device: String?, habit: HabitEntity, source: WidgetFactClaim,
        values: List<MetricValueDraft>, expected: List<com.dayforge.data.local.entity.MetricEntity>,
        links: List<com.dayforge.data.local.entity.HabitMetricLinkEntity>, recordedAt: Long,
        timezone: String): CountCommandRecords.Intent {
        require(habit.appearance != null && habit.habitType == HabitType.COUNTING && habit.completionPolicy == "recurring" &&
            source.habitUuid == habit.uuid && source.session() == authority.session && source.completionUuid != null)
        val result = parent.committed.outcome
        require(result.action == CountCommandRecords.Action.COUNT_ADD && result.kind == CountCommandOutcome.Kind.APPLIED &&
            result.primaryFactUuid == source.completionUuid && result.effects.single().objectUuid == habit.uuid)
        require(values.isNotEmpty() && values.map { it.metricId }.distinct().size == values.size &&
            expected.map { it.id }.toSet() == values.map { it.metricId }.toSet() &&
            expected.all { it.appearance != null } && links.size == values.size)
        return encode(commandId, locator(database, authority.session, device), authority.session,
            CountCommandRecords.Action.LINKED_METRICS, CountCommandRecords.Capture.EXECUTION_INPUT, buildJsonObject {
                put("parent_command_id", parent.commandId); put("habit_uuid", habit.uuid)
                put("source_fact_uuid", requireNotNull(source.completionUuid)); put("source_claim", widget(source)); put("recorded_at", recordedAt); put("timezone", timezone)
                put("metrics", JsonArray(values.map { value ->
                    val metric = expected.single { it.id == value.metricId }
                    val link = links.single { it.metricId == value.metricId }
                    require(value.value.isFinite() && link.habitId == habit.id && link.habitUuid == habit.uuid &&
                        link.metricUuid == metric.uuid && link.isActive && link.promptOnComplete)
                    buildJsonObject {
                        put("metric_uuid", metric.uuid); put("metric_id", metric.id)
                        put("value_bits", value.value.toRawBits().toULong().toString(16).padStart(16, '0')); put("note", value.note)
                        put("definition", NextStructureMapper.writeMetric(metric)); put("link", metricLink(link))
                        put("reserved_fact_uuid", UUID.randomUUID().toString())
                    }
                }))
            })
    }

    /** Exactly the original prompt predicates, not a new full-row equality requirement for
     * unrelated coefficients/display flags/timestamps that the old writer did not freeze.
     */
    fun metricLink(link: com.dayforge.data.local.entity.HabitMetricLinkEntity): JsonObject = buildJsonObject {
        put("habit_id", link.habitId); put("habit_uuid", link.habitUuid); put("metric_id", link.metricId); put("metric_uuid", link.metricUuid)
        put("active", link.isActive); put("prompt_on_complete", link.promptOnComplete)
    }

    /** Only the original operation's selected activity is frozen. Fresh T1 retains the
     * COMPLETE original global snapshot/footer; this descriptor never substitutes for it.
     */
    fun round(scope: NextRoundWriteScope?, activity: String): JsonElement = scope?.let {
        val initial = it.pendingInitials[activity]; val restart = it.pendingRestarts[activity]
        val selected = restart?.head ?: it.completionRounds.head(activity) ?: initial?.head
        buildJsonObject {
            put("profile", "rounds/v1"); put("head", head(selected))
            put("initial", initial?.let { value -> buildJsonObject {
                put("operation_id", value.operationId); put("origin_hash", value.originHash)
                put("source_hash", value.sourceHash); put("head", head(value.head))
            } } ?: JsonNull)
            put("restart", restart?.let { value -> Json.encodeToJsonElement(NextRestartReference.serializer(), value) } ?: JsonNull)
        }
    } ?: JsonNull

    fun requireOriginalSelection(intent: CountCommandRecords.Intent, authority: ObjectEditAuthority) {
        val input = intent.body().getValue("input").jsonObject
        require(CountCommandRecords.uuid(input, "habit_uuid") == authority.uuid &&
            CountCommandRecords.canonical(input.getValue("plan")) == CountCommandRecords.canonical(authority.original) &&
            CountCommandRecords.canonical(input.getValue("round")) == CountCommandRecords.canonical(round(authority.rounds, authority.uuid))) {
            "COMMAND_ORIGINAL_SELECTION_CHANGED"
        }
    }
    fun restoredWidget(input: JsonObject, key: String = "widget_claim"): WidgetFactClaim? =
        input.getValue(key).takeUnless { it == JsonNull }?.let { WidgetFactClaim.decode(CountCommandRecords.canonical(it)) }
    private fun widget(claim: WidgetFactClaim?): JsonElement = claim?.let { Json.parseToJsonElement(it.encode()) } ?: JsonNull
    private fun head(value: ChallengeRoundHead?): JsonElement = value?.let { Json.encodeToJsonElement(ChallengeRoundHead.serializer(), it) } ?: JsonNull
    private fun encode(commandId: String, namespace: CountCommandRecords.Namespace, session: LocalDataSession,
        action: CountCommandRecords.Action, capture: CountCommandRecords.Capture, input: JsonObject): CountCommandRecords.Intent {
        val bytes = CountCommandRecords.canonical(buildJsonObject {
            put("format", CountCommandRecords.FORMAT); put("command_id", commandId); put("namespace", namespace.body())
            put("original_auth_generation", session.authentication.generation); put("action", action.name); put("capture", capture.name); put("input", input)
        }).toByteArray(Charsets.UTF_8)
        return CountCommandRecords.Intent.decode(bytes, bytes.size, {})
    }
}
