package com.dayforge.data.repository

import com.dayforge.data.api.dto.validateNextSyncOperation
import com.dayforge.domain.model.isContractUuid
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/** Narrow absence prerequisite, not an execution/recovery ticket. Cold issuance checks the
 * same complete identity domains and fate→origin relation as original NoEffects. Warm use
 * is conditional on controlled whole-journal maintenance; unknown recovery still uses the
 * original full NoEffects, lease, primary catalog and actual-END protocol. */
internal object MetricCommandAbsenceEvidence {
    const val KEY = "metric-display/command-absence-v1"
    val tables = listOf("local_fact_submissions", "next_transmissions", "next_acceptances", "next_rejections", "next_abandoned_requests")
    private val fates = setOf("next_transmissions", "next_acceptances", "next_rejections", "next_abandoned_requests")
    fun memberships(row: CountTrustRows.Row): Map<String, String> = if (row.table.name == "local_fact_submissions")
        mapOf(CountTrustMembership.key("metricSubmission.entity", row.text("entityType"), row.text("entityUuid")) to row.name) else emptyMap()

    suspend fun issue(rows: List<CountTrustRows.Row>, poll: () -> Unit = {}): JsonElement = JsonPrimitive(try {
        val origins = hashSetOf<Pair<String, String>>()
        for (row in rows) { poll(); when (row.table.name) {
            "completions", "metric_logs" -> check(isContractUuid(row.text("uuid")))
            "sync_entity_state", "next_challenge_births", "local_fact_submissions" -> {
                row.text("entityType"); check(isContractUuid(row.text("entityUuid")))
            }
            "sync_outbox" -> if (row.text("recordType") in setOf("completion", "metric_log")) {
                check(isContractUuid(row.text("entityUuid")))
                row.nullableText("wireEntityUuid")?.let { check(isContractUuid(it)) }
            }
            "next_request_origins" -> { checkOrigin(row); check(origins.add(row.text("kind") to row.text("requestId"))) }
        } }
        for (row in rows) if (row.table.name in fates) { poll()
            check(isContractUuid(row.text("requestId")) && (row.text("kind") to row.text("requestId")) in origins)
        }
        true
    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
      catch (_: IllegalArgumentException) { false }
      catch (_: IllegalStateException) { false })

    /** Called after the original owner proved the complete native effect list. Never repairs
     * a false/missing certificate. Conservative unsupported origin replacement loses reuse. */
    suspend fun after(previous: JsonElement?, changes: List<Pair<CountTrustRows.Row?, CountTrustRows.Row?>>,
        native: CountTrustRows): JsonElement {
        if (previous != JsonPrimitive(true)) return JsonPrimitive(false)
        return JsonPrimitive(try {
            for ((old, fresh) in changes) {
                val table = (old ?: requireNotNull(fresh)).table.name
                if (table == "next_request_origins") {
                    if (old != null) return JsonPrimitive(false)
                    checkOrigin(requireNotNull(fresh))
                }
                if (table in fates && fresh != null) {
                    val kind = fresh.text("kind"); val request = fresh.text("requestId")
                    check(isContractUuid(request))
                    val origin = native.read("next_request_origins", "kind=? AND requestId=?", arrayOf(kind, request)).single()
                    checkOrigin(origin)
                }
                if (fresh != null) when (table) {
                    "completions", "metric_logs" -> check(isContractUuid(fresh.text("uuid")))
                    "sync_entity_state", "next_challenge_births", "local_fact_submissions" -> {
                        fresh.text("entityType"); check(isContractUuid(fresh.text("entityUuid")))
                    }
                    "sync_outbox" -> if (fresh.text("recordType") in setOf("completion", "metric_log")) {
                        check(isContractUuid(fresh.text("entityUuid")))
                        fresh.nullableText("wireEntityUuid")?.let { check(isContractUuid(it)) }
                    }
                }
            }
            true
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
          catch (_: IllegalArgumentException) { false }
          catch (_: IllegalStateException) { false })
    }
    private suspend fun checkOrigin(row: CountTrustRows.Row) {
        val request = row.text("requestId"); check(isContractUuid(request))
        when (row.text("kind")) {
            NEXT_OPERATION -> {
                val text = row.text("intentJson")
                val restart = restartProposal(text)
                if (restart != null) check(restart.operationId == request)
                else decodeNextOperationIntent(text).let { check(it.operationId == request); validateNextSyncOperation(it) }
            }
            NEXT_TIMER -> check(decodeNextTimerIntent(row.text("intentJson")).command.commandId == request)
            else -> error("METRIC_ABSENCE_ORIGIN_KIND")
        }
    }
}
