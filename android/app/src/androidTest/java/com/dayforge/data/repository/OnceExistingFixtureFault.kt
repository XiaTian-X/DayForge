package com.dayforge.data.repository

import android.database.Cursor
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteTransactionListener
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.SupportSQLiteStatement
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import com.dayforge.domain.model.isContractUuid
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*

/** Only the eighteen existing ONCE fixture actors. Callers supply typed identities, never SQL,
 * callbacks or native handles. The production controlled factory and its authority stay above
 * this layer. No preflight rejection is counted as a business actor or a native commit failure. */
internal class OnceExistingFixtureFault(
    private val delegateFactory: SupportSQLiteOpenHelper.Factory = FrameworkSQLiteOpenHelperFactory()
) : SupportSQLiteOpenHelper.Factory {
    private enum class Actor(val table: String, val sql: String, val parameters: List<String>) {
        OUTBOX_INSERT("sync_outbox", "INSERT OR ABORT INTO `sync_outbox` (`id`,`operationId`,`recordType`,`entityUuid`,`wireEntityUuid`,`action`,`referenceUuid`,`payloadJson`,`baseRevision`,`basePayloadJson`,`attemptedAt`,`attemptCount`,`lastError`,`errorCode`,`deadLetteredAt`,`createdAt`) VALUES (nullif(?, 0),?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", listOf("id","operationId","recordType","entityUuid","wireEntityUuid","action","referenceUuid","payloadJson","baseRevision","basePayloadJson","attemptedAt","attemptCount","lastError","errorCode","deadLetteredAt","createdAt")),
        FACT_INSERT("completions", "INSERT OR ABORT INTO `completions` (`id`,`habitId`,`date`,`value`,`actualCompletedAt`,`uuid`,`habitUuid`,`createdAt`,`recordedTimezone`,`recordedLocalDate`,`timeMetadataSource`,`oneTimeAction`,`oneTimeExpectedVersion`,`oneTimeExpectedHeadEventUuid`,`oneTimeRevertsEventUuid`) VALUES (nullif(?, 0),?,?,?,?,?,?,?,?,?,?,?,?,?,?)", listOf("id","habitId","date","value","actualCompletedAt","uuid","habitUuid","createdAt","recordedTimezone","recordedLocalDate","timeMetadataSource","oneTimeAction","oneTimeExpectedVersion","oneTimeExpectedHeadEventUuid","oneTimeRevertsEventUuid")),
        PROMPT_INSERT("completion_metric_prompts", "INSERT OR ABORT INTO `completion_metric_prompts` (`eventUuid`,`activityUuid`,`state`,`revision`,`entriesJson`,`recordedAtMillis`,`timezone`) VALUES (?,?,?,?,?,?,?)", listOf("eventUuid","activityUuid","state","revision","entriesJson","recordedAtMillis","timezone")),
        ORIGIN_INSERT("next_request_origins", "INSERT OR ABORT INTO `next_request_origins` (`kind`,`requestId`,`queueId`,`protocol`,`accountId`,`serverInstanceId`,`syncEpoch`,`sourceHash`,`intentJson`) VALUES (?,?,?,?,?,?,?,?,?)", listOf("kind","requestId","queueId","protocol","accountId","serverInstanceId","syncEpoch","sourceHash","intentJson")),
        SUBMISSION_INSERT("local_fact_submissions", "INSERT OR ABORT INTO `local_fact_submissions` (`operationId`,`entityType`,`entityUuid`,`referenceUuid`,`payloadJson`) VALUES (?,?,?,?,?)", listOf("operationId","entityType","entityUuid","referenceUuid","payloadJson")),
        RECEIPT_INSERT("next_acceptances", "INSERT OR ABORT INTO `next_acceptances` (`kind`,`requestId`,`originHash`,`transmissionHash`,`resultHash`,`resultJson`) VALUES (?,?,?,?,?,?)", listOf("kind","requestId","originHash","transmissionHash","resultHash","resultJson")),
        JOURNAL_INSERT("next_transmissions", "INSERT OR ABORT INTO `next_transmissions` (`kind`,`requestId`,`queueId`,`protocol`,`accountId`,`serverInstanceId`,`syncEpoch`,`deviceId`,`wireHash`,`wireBytes`) VALUES (?,?,?,?,?,?,?,?,?,?)", listOf("kind","requestId","queueId","protocol","accountId","serverInstanceId","syncEpoch","deviceId","wireHash","wireBytes")),
        ROUND_INSERT("next_challenge_rounds", "INSERT OR ABORT INTO `next_challenge_rounds` (`roundUuid`,`activityUuid`,`generation`,`accountId`,`serverInstanceId`,`syncEpoch`,`sourceDeviceUuid`,`operationUuid`,`recordJson`,`recordHash`) VALUES (?,?,?,?,?,?,?,?,?,?)", listOf("roundUuid","activityUuid","generation","accountId","serverInstanceId","syncEpoch","sourceDeviceUuid","operationUuid","recordJson","recordHash")),
        QUEUE_DELETE("sync_outbox", "DELETE FROM sync_outbox WHERE id = ?", listOf("id")),
        QUEUE_PREPARE("sync_outbox", "UPDATE sync_outbox SET wireEntityUuid = ?, action = ?, payloadJson = ?, baseRevision = ?, basePayloadJson = ?, attemptedAt = ?, attemptCount = attemptCount + 1, lastError = NULL, errorCode = NULL WHERE id = ?", listOf("wireEntityUuid","action","payloadJson","baseRevision","basePayloadJson","attemptedAt","id")),
        QUEUE_REJECT("sync_outbox", "UPDATE sync_outbox SET errorCode = ?, lastError = ?, deadLetteredAt = ? WHERE id = ?", listOf("errorCode","lastError","deadLetteredAt","id")),
        CURSOR_UPDATE("next_sync_state", "UPDATE OR ABORT `next_sync_state` SET `accountId` = ?,`serverInstanceId` = ?,`syncEpoch` = ?,`deviceId` = ?,`generation` = ?,`cursor` = ?,`bootstrapHash` = ?,`batchHash` = ?,`id` = ?,`challengeContract` = ? WHERE `id` = ?", listOf("accountId","serverInstanceId","syncEpoch","deviceId","generation","cursor","bootstrapHash","batchHash","id","challengeContract","whereId"));
        val normalizedSql = sql.lowercase(Locale.ROOT).filterNot { it.isWhitespace() }
    }
    enum class Kind {
        LOCAL_OUTBOX_ABORT, ACK_QUEUE_ABORT, PREPARE_ABORT, REJECTION_ABORT,
        SECOND_HISTORY_ABORT, FIRST_HISTORY_IGNORE, PROMPT_ABORT, LAST_OBSERVATION_ABORT,
        RECEIPT_ABORT, RECEIPT_IGNORE, QUEUE_IGNORE, RECEIPT_SUBMISSION, RECEIPT_FACT,
        JOURNAL_IGNORE, ORIGIN_IGNORE, SUBMISSION_IGNORE, RECEIPT_PARENT,
        ROUND_IGNORE, ROUND_FACT, RECEIPT_METADATA, RECEIPT_CURSORS, DEFERRED_END,
        ORIGIN_CURSORS, JOURNAL_CURSORS, WIDGET_ORIGIN_ABORT,
        CURSOR_ABORT, CURSOR_IGNORE, CURSOR_METRIC, CURSOR_DAY_UPDATE, CURSOR_DAY_DELETE
    }
    data class Targets(val requestId: String? = null, val activityUuid: String? = null,
        val eventUuid: String? = null, val queueId: Long? = null, val habitId: Long? = null,
        val priorHabitId: Long? = null, val priorFacts: Int? = null, val otherRequestId: String? = null,
        val replacementUuid: String? = null, val metricUuid: String? = null, val priorRequestId: String? = null)
    private data class Cell(val type: Int, val value: Any?)
    private data class Rows(val columns: List<String>, val values: List<List<Cell>>)
    private data class Image(val version: Long, val schemaVersion: Long, val schema: Rows, val tables: Map<String, Rows>)
    private class Attempt(val kind: Kind, val targets: Targets, val native: SupportSQLiteDatabase) {
        val claimed = AtomicBoolean()
        val actors = AtomicInteger()
        val returned = AtomicInteger()
        val marks = AtomicInteger()
        val ends = AtomicInteger()
        val endConstraints = AtomicInteger()
        val error = AtomicReference<SQLiteConstraintException?>()
        val observationFailure = AtomicReference<Throwable?>()
        @Volatile var before: Image? = null
        @Volatile var actorSeen = false
        @Volatile var effectSeen = false
        @Volatile var scalarsKept = false
        @Volatile var warmEqual = false
        @Volatile var reported = false
        @Volatile var cleared = false
        @Volatile var closed = false
    }
    private class Frame(val attempt: Attempt, val native: SupportSQLiteDatabase, val before: Image?) {
        var actor = false
    }
    private val selected = AtomicReference<Attempt?>()
    private val frames = ThreadLocal<Frame?>()
    @Volatile private var opened: SupportSQLiteDatabase? = null

    private fun actor(kind: Kind): Actor = when (kind) {
        Kind.LOCAL_OUTBOX_ABORT, Kind.LAST_OBSERVATION_ABORT -> Actor.OUTBOX_INSERT
        Kind.ACK_QUEUE_ABORT, Kind.QUEUE_IGNORE -> Actor.QUEUE_DELETE
        Kind.PREPARE_ABORT -> Actor.QUEUE_PREPARE
        Kind.REJECTION_ABORT -> Actor.QUEUE_REJECT
        Kind.SECOND_HISTORY_ABORT, Kind.FIRST_HISTORY_IGNORE -> Actor.FACT_INSERT
        Kind.PROMPT_ABORT -> Actor.PROMPT_INSERT
        Kind.ORIGIN_IGNORE, Kind.ORIGIN_CURSORS, Kind.WIDGET_ORIGIN_ABORT -> Actor.ORIGIN_INSERT
        Kind.SUBMISSION_IGNORE -> Actor.SUBMISSION_INSERT
        Kind.JOURNAL_IGNORE, Kind.JOURNAL_CURSORS -> Actor.JOURNAL_INSERT
        Kind.ROUND_IGNORE, Kind.ROUND_FACT -> Actor.ROUND_INSERT
        Kind.CURSOR_ABORT, Kind.CURSOR_IGNORE, Kind.CURSOR_METRIC, Kind.CURSOR_DAY_UPDATE,
        Kind.CURSOR_DAY_DELETE -> Actor.CURSOR_UPDATE
        else -> Actor.RECEIPT_INSERT
    }
    private fun abort(kind: Kind) = kind in setOf(Kind.LOCAL_OUTBOX_ABORT, Kind.ACK_QUEUE_ABORT,
        Kind.PREPARE_ABORT, Kind.REJECTION_ABORT, Kind.SECOND_HISTORY_ABORT, Kind.PROMPT_ABORT,
        Kind.LAST_OBSERVATION_ABORT, Kind.RECEIPT_ABORT, Kind.WIDGET_ORIGIN_ABORT, Kind.CURSOR_ABORT)
    private fun ignore(kind: Kind) = kind in setOf(Kind.FIRST_HISTORY_IGNORE, Kind.RECEIPT_IGNORE,
        Kind.QUEUE_IGNORE, Kind.JOURNAL_IGNORE, Kind.ORIGIN_IGNORE, Kind.SUBMISSION_IGNORE,
        Kind.ROUND_IGNORE, Kind.CURSOR_IGNORE)
    private fun message(kind: Kind) = when (kind) {
        Kind.LOCAL_OUTBOX_ABORT -> "intent write unavailable"
        Kind.ACK_QUEUE_ABORT -> "synthetic acknowledgement failure"
        Kind.PREPARE_ABORT -> "synthetic preparation failure"
        Kind.REJECTION_ABORT -> "synthetic rejection failure"
        Kind.SECOND_HISTORY_ABORT -> "synthetic history failure"
        Kind.PROMPT_ABORT -> "prompt unavailable"
        Kind.LAST_OBSERVATION_ABORT -> "outbox unavailable"
        Kind.RECEIPT_ABORT -> "receipt"
        Kind.WIDGET_ORIGIN_ABORT -> "widget origin"
        Kind.CURSOR_ABORT -> "cursor failed"
        else -> error("Not an ABORT actor")
    }
    private fun normalize(sql: String) = sql.lowercase(Locale.ROOT).filterNot { it.isWhitespace() }
    private fun keys(actor: Actor, bound: Map<Int, Any?>) = actor.parameters.mapIndexed { i, name -> name to bound[i + 1] }.toMap()
    private fun identity(value: Any?) = value as? String
    private fun long(value: Any?) = value as? Long
    private fun matches(a: Attempt, values: Map<String, Any?>): Boolean {
        val t = a.targets
        return when (actor(a.kind)) {
            Actor.OUTBOX_INSERT -> if (a.kind == Kind.LOCAL_OUTBOX_ABORT)
                values["recordType"] == "one_time_completion" && values["operationId"] == t.requestId && values["referenceUuid"] == t.activityUuid
                else values["operationId"] == t.requestId && values["recordType"] == "metric_log"
            Actor.QUEUE_DELETE, Actor.QUEUE_PREPARE, Actor.QUEUE_REJECT -> long(values["id"]) == t.queueId
            Actor.FACT_INSERT -> long(values["habitId"]) == t.habitId &&
                (a.kind != Kind.FIRST_HISTORY_IGNORE || long(values["oneTimeExpectedVersion"]) == 0L)
            Actor.PROMPT_INSERT -> values["activityUuid"] == t.activityUuid
            Actor.SUBMISSION_INSERT -> values["operationId"] == t.requestId
            Actor.ROUND_INSERT -> values["activityUuid"] == t.activityUuid
            Actor.CURSOR_UPDATE -> long(values["whereId"]) == 1L && long(values["id"]) == 1L && long(values["cursor"]) == 21L
            else -> values["kind"] == NEXT_OPERATION && if (a.kind == Kind.WIDGET_ORIGIN_ABORT)
                identity(values["requestId"])?.let(::isContractUuid) == true else values["requestId"] == t.requestId
        }
    }
    private fun observe(a: Attempt, block: () -> Unit) {
        try { block() } catch (failure: Throwable) { a.observationFailure.compareAndSet(null, failure) }
    }
    private fun rows(raw: SupportSQLiteDatabase, sql: String, args: Array<out Any?> = emptyArray()): Rows = raw.query(sql, args).use { c ->
        val columns = c.columnNames.toList()
        val data = buildList { while (c.moveToNext()) add(columns.indices.map { i -> when (val type = c.getType(i)) {
            Cursor.FIELD_TYPE_NULL -> Cell(type, null)
            Cursor.FIELD_TYPE_INTEGER -> Cell(type, c.getLong(i))
            Cursor.FIELD_TYPE_FLOAT -> Cell(type, c.getDouble(i).toBits())
            Cursor.FIELD_TYPE_STRING -> Cell(type, c.getString(i))
            Cursor.FIELD_TYPE_BLOB -> Cell(type, c.getBlob(i).toList())
            else -> error("Unexpected native type")
        } }) }
        Rows(columns, data.sortedBy { row -> row.joinToString("") { val s = it.value.toString(); "${it.type}:${s.length}:$s" } })
    }
    private fun scalar(raw: SupportSQLiteDatabase, sql: String, args: Array<out Any?> = emptyArray()): Long = rows(raw, sql, args).let {
        check(it.values.size == 1 && it.columns.size == 1)
        it.values.single().single().also { c -> check(c.type == Cursor.FIELD_TYPE_INTEGER) }.value as Long
    }
    private fun table(raw: SupportSQLiteDatabase, name: String): Rows {
        check(name.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")))
        return rows(raw, "SELECT * FROM main.`$name`")
    }
    private fun image(raw: SupportSQLiteDatabase): Image {
        val schema = rows(raw, "SELECT type,name,tbl_name,sql FROM main.sqlite_master ORDER BY type,name")
        val names = rows(raw, "SELECT name FROM main.sqlite_master WHERE type='table' ORDER BY name").values.map {
            check(it.single().type == Cursor.FIELD_TYPE_STRING); it.single().value as String
        }
        return Image(scalar(raw, "PRAGMA main.user_version"), scalar(raw, "PRAGMA main.schema_version"), schema,
            names.associateWith { table(raw, it) })
    }
    private fun scalars(raw: SupportSQLiteDatabase) = rows(raw, "SELECT last_insert_rowid(),changes()")
    private fun fullImage(raw: SupportSQLiteDatabase): Image {
        check(!raw.inTransaction()); raw.beginTransaction()
        try { return image(raw) } finally { raw.endTransaction() }
    }
    private fun changed(before: Rows, column: String, value: (Cell) -> Cell): Rows {
        val index = before.columns.indexOf(column); check(index >= 0 && before.values.isNotEmpty())
        return before.copy(values = before.values.map { row -> row.toMutableList().also { it[index] = value(row[index]) } }
            .sortedBy { row -> row.joinToString("") { val s = it.value.toString(); "${it.type}:${s.length}:$s" } })
    }
    private fun str(value: String) = Cell(Cursor.FIELD_TYPE_STRING, value)
    private fun increment(value: Cell): Cell {
        if (value.type == Cursor.FIELD_TYPE_NULL) return value // SQLite NULL + 1 remains NULL.
        check(value.type == Cursor.FIELD_TYPE_INTEGER); return Cell(value.type, Math.addExact(value.value as Long, 1L))
    }

    fun arm(kind: Kind, target: Targets) {
        val raw = requireNotNull(opened)
        check(!raw.inTransaction() && selected.get()?.let { it.reported && it.cleared } != false)
        listOf(target.requestId, target.activityUuid, target.eventUuid, target.otherRequestId,
            target.replacementUuid, target.metricUuid, target.priorRequestId).filterNotNull().forEach { require(isContractUuid(it)) }
        listOf(target.queueId, target.habitId, target.priorHabitId).filterNotNull().forEach { require(it > 0) }
        target.priorFacts?.let { require(it in 1..1000) }
        val a = Attempt(kind, target, raw)
        val t = target
        val condition = when (actor(kind)) {
            Actor.OUTBOX_INSERT -> "NEW.operationId='${requireNotNull(t.requestId)}'"
            Actor.QUEUE_DELETE -> "OLD.id=${requireNotNull(t.queueId)}"
            Actor.QUEUE_PREPARE, Actor.QUEUE_REJECT -> "NEW.id=${requireNotNull(t.queueId)}"
            Actor.FACT_INSERT -> "NEW.habitId=${requireNotNull(t.habitId)}" + if (kind == Kind.FIRST_HISTORY_IGNORE) " AND NEW.oneTimeExpectedVersion=0" else ""
            Actor.PROMPT_INSERT, Actor.ROUND_INSERT -> "NEW.activityUuid='${requireNotNull(t.activityUuid)}'"
            Actor.SUBMISSION_INSERT -> "NEW.operationId='${requireNotNull(t.requestId)}'"
            Actor.CURSOR_UPDATE -> "NEW.id=1"
            else -> if (kind == Kind.WIDGET_ORIGIN_ABORT)
                "NEW.kind='$NEXT_OPERATION' AND EXISTS(SELECT 1 FROM main.sync_outbox q WHERE q.id=NEW.queueId AND q.operationId=NEW.requestId AND q.referenceUuid='${requireNotNull(t.activityUuid)}' AND q.recordType='completion')"
                else "NEW.kind='$NEXT_OPERATION' AND NEW.requestId='${requireNotNull(t.requestId)}'"
        }
        val op = when (actor(kind)) {
            Actor.QUEUE_DELETE -> "DELETE"
            Actor.QUEUE_PREPARE, Actor.QUEUE_REJECT, Actor.CURSOR_UPDATE -> "UPDATE"
            else -> "INSERT"
        }
        val timing = if (abort(kind) || ignore(kind) || kind == Kind.CURSOR_METRIC) "BEFORE" else "AFTER"
        val body = when {
            abort(kind) -> "SELECT RAISE(ABORT, '${message(kind)}');"
            ignore(kind) -> "SELECT RAISE(IGNORE);"
            else -> when (kind) {
                Kind.RECEIPT_SUBMISSION -> "UPDATE local_fact_submissions SET referenceUuid='${requireNotNull(t.replacementUuid)}';"
                Kind.RECEIPT_FACT -> "UPDATE completions SET actualCompletedAt=actualCompletedAt+1;"
                Kind.RECEIPT_PARENT -> "UPDATE next_request_origins SET intentJson='{}' WHERE requestId='${requireNotNull(t.otherRequestId)}';"
                Kind.ROUND_FACT -> "UPDATE completions SET value=99;"
                Kind.RECEIPT_METADATA -> "UPDATE next_challenge_state SET metadataHash='bad';"
                Kind.RECEIPT_CURSORS, Kind.ORIGIN_CURSORS, Kind.JOURNAL_CURSORS -> "UPDATE next_sync_state SET cursor=cursor+1,generation=generation+1; UPDATE next_challenge_state SET cursor=cursor+1,generation=generation+1;"
                Kind.DEFERRED_END -> "INSERT INTO once_existing_child VALUES(1,99);"
                Kind.CURSOR_METRIC -> "UPDATE metrics SET name='late corruption' WHERE uuid='${requireNotNull(t.metricUuid)}';"
                Kind.CURSOR_DAY_UPDATE -> "UPDATE count_days SET targetValue=11;"
                Kind.CURSOR_DAY_DELETE -> "DELETE FROM count_days;"
                else -> error("Unexpected fixed actor")
            }
        }
        if (kind == Kind.DEFERRED_END) {
            raw.execSQL("CREATE TEMP TABLE once_existing_parent(id INTEGER PRIMARY KEY)")
            raw.execSQL("CREATE TEMP TABLE once_existing_child(id INTEGER PRIMARY KEY,parent INTEGER REFERENCES once_existing_parent(id) DEFERRABLE INITIALLY DEFERRED)")
        }
        raw.execSQL("CREATE TEMP TRIGGER once_existing_fault $timing $op ON main.${actor(kind).table} WHEN $condition BEGIN $body END")
        selected.set(a)
    }
    private fun begin(raw: SupportSQLiteDatabase, original: () -> Unit) {
        val outer = !raw.inTransaction(); original()
        val a = selected.get() ?: return
        if (!outer || a.closed || a.cleared || a.claimed.get() || a.native !== raw) return
        var before: Image? = null
        observe(a) { check(frames.get() == null); before = image(raw) }
        frames.set(Frame(a, raw, before))
    }
    private fun previous(a: Attempt, name: String, key: String, value: Any?): Rows {
        val all = requireNotNull(requireNotNull(a.before).tables[name])
        val column = all.columns.indexOf(key); check(column >= 0)
        return all.copy(values = all.values.filter { it[column].value == value })
    }
    private fun beforeActor(a: Attempt, values: Map<String, Any?>, frame: Frame) {
        val raw = a.native; val t = a.targets
        check(raw.inTransaction() && frame.attempt === a && frame.native === raw && frame.before != null)
        val before = requireNotNull(frame.before); a.before = before
        when (actor(a.kind)) {
            Actor.ORIGIN_INSERT, Actor.JOURNAL_INSERT, Actor.RECEIPT_INSERT -> {
                check(values["kind"] == NEXT_OPERATION)
                if (a.kind == Kind.WIDGET_ORIGIN_ABORT) {
                    check(isContractUuid(requireNotNull(identity(values["requestId"]))))
                    check(scalar(raw, "SELECT count(*) FROM main.sync_outbox q JOIN main.completions c ON c.uuid=q.entityUuid JOIN main.habits h ON h.id=c.habitId WHERE q.id=? AND q.operationId=? AND q.referenceUuid=? AND q.recordType='completion' AND h.uuid=?",
                        arrayOf(values["queueId"], values["requestId"], t.activityUuid, t.activityUuid)) == 1L)
                    check(scalar(raw, "SELECT count(*) FROM main.count_days d JOIN main.habits h ON h.id=d.habitId WHERE h.uuid=?", arrayOf(t.activityUuid)) > 0L)
                } else check(values["requestId"] == t.requestId)
                if (actor(a.kind) != Actor.ORIGIN_INSERT) check(scalar(raw,
                    "SELECT count(*) FROM main.next_request_origins WHERE kind=? AND requestId=?", arrayOf(NEXT_OPERATION, t.requestId)) == 1L)
            }
            Actor.QUEUE_DELETE, Actor.QUEUE_PREPARE, Actor.QUEUE_REJECT -> check(scalar(raw,
                "SELECT count(*) FROM main.sync_outbox WHERE id=? AND operationId=?", arrayOf<Any?>(t.queueId, requireNotNull(t.requestId))) == 1L)
            Actor.OUTBOX_INSERT -> check(isContractUuid(requireNotNull(identity(values["entityUuid"]))))
            else -> Unit
        }
        when (a.kind) {
            Kind.SECOND_HISTORY_ABORT -> {
                check(previous(a, "completions", "habitId", t.priorHabitId).values.isEmpty())
                check(previous(a, "completions", "habitId", t.habitId).values.isEmpty())
                check(scalar(raw, "SELECT count(*) FROM main.completions WHERE habitId=?", arrayOf(requireNotNull(t.priorHabitId))) == requireNotNull(t.priorFacts).toLong())
                check(scalar(raw, "SELECT count(*) FROM main.completions WHERE habitId=?", arrayOf(t.habitId)) == 0L)
            }
            Kind.FIRST_HISTORY_IGNORE -> check(scalar(raw, "SELECT count(*) FROM main.completions WHERE habitId=?", arrayOf(t.habitId)) == 0L)
            Kind.LOCAL_OUTBOX_ABORT -> {
                check(previous(a, "completions", "uuid", values["entityUuid"]).values.isEmpty())
                check(scalar(raw, "SELECT count(*) FROM main.completions WHERE uuid=?", arrayOf(values["entityUuid"])) == 1L)
            }
            Kind.PROMPT_ABORT -> {
                check(previous(a, "completions", "uuid", values["eventUuid"]).values.isEmpty())
                check(scalar(raw, "SELECT count(*) FROM main.completions WHERE uuid=? AND habitUuid=?", arrayOf(values["eventUuid"], t.activityUuid)) == 1L)
                check(scalar(raw, "SELECT count(*) FROM main.local_fact_submissions WHERE entityUuid=?", arrayOf(values["eventUuid"])) == 1L)
                check(scalar(raw, "SELECT count(*) FROM main.sync_outbox WHERE entityUuid=?", arrayOf(values["eventUuid"])) == 1L)
            }
            Kind.LAST_OBSERVATION_ABORT -> {
                check(previous(a, "metric_logs", "uuid", values["entityUuid"]).values.isEmpty())
                check(previous(a, "sync_outbox", "operationId", t.priorRequestId).values.isEmpty())
                check(scalar(raw, "SELECT count(*) FROM main.metric_logs WHERE uuid=?", arrayOf(values["entityUuid"])) == 1L)
                check(scalar(raw, "SELECT count(*) FROM main.sync_outbox q JOIN main.metric_logs m ON m.uuid=q.entityUuid WHERE q.operationId=?", arrayOf(requireNotNull(t.priorRequestId))) == 1L)
                check(table(raw, "metric_logs").values.size > requireNotNull(before.tables["metric_logs"]).values.size)
            }
            Kind.ACK_QUEUE_ABORT -> check(scalar(raw, "SELECT count(*) FROM main.sync_entity_state WHERE entityType='activity_event' AND entityUuid=?", arrayOf(requireNotNull(t.eventUuid))) == 1L)
            Kind.PREPARE_ABORT -> {
                check(previous(a, "one_time_transmissions", "operationId", t.requestId).values.isEmpty())
                check(scalar(raw, "SELECT count(*) FROM main.one_time_transmissions WHERE operationId=?", arrayOf(t.requestId)) == 1L)
            }
            Kind.REJECTION_ABORT -> check(scalar(raw, "SELECT count(*) FROM main.one_time_transmissions WHERE operationId=? AND rejectionJson IS NOT NULL", arrayOf(t.requestId)) == 1L)
            Kind.CURSOR_DAY_UPDATE, Kind.CURSOR_DAY_DELETE -> {
                val days = table(raw, "count_days")
                check(days.values.isNotEmpty() && days == before.tables["count_days"])
                check(scalar(raw, "SELECT count(*) FROM main.count_days d JOIN main.habits h ON h.id=d.habitId WHERE h.uuid=?", arrayOf(requireNotNull(t.activityUuid))) > 0)
            }
            else -> Unit
        }
        a.actorSeen = true
    }
    private fun effectTables(a: Attempt) = when (a.kind) {
        Kind.RECEIPT_SUBMISSION -> listOf("local_fact_submissions")
        Kind.RECEIPT_FACT, Kind.ROUND_FACT -> listOf("completions")
        Kind.RECEIPT_PARENT -> listOf("next_request_origins")
        Kind.RECEIPT_METADATA -> listOf("next_challenge_state")
        Kind.RECEIPT_CURSORS, Kind.ORIGIN_CURSORS, Kind.JOURNAL_CURSORS -> listOf("next_sync_state", "next_challenge_state")
        Kind.CURSOR_METRIC -> listOf("metrics")
        Kind.CURSOR_DAY_UPDATE, Kind.CURSOR_DAY_DELETE -> listOf("count_days")
        else -> emptyList()
    }
    private fun afterActor(a: Attempt, values: Map<String, Any?>, effectBefore: Map<String, Rows>) {
        val raw = a.native; val t = a.targets
        check(raw.inTransaction())
        if (ignore(a.kind)) {
            check(scalar(raw, "SELECT changes()") == 0L)
            val absent = when (actor(a.kind)) {
                Actor.FACT_INSERT -> scalar(raw, "SELECT count(*) FROM main.completions WHERE uuid=?", arrayOf(values["uuid"])) == 0L
                Actor.ORIGIN_INSERT -> scalar(raw, "SELECT count(*) FROM main.next_request_origins WHERE kind=? AND requestId=?", arrayOf(NEXT_OPERATION, t.requestId)) == 0L
                Actor.SUBMISSION_INSERT -> scalar(raw, "SELECT count(*) FROM main.local_fact_submissions WHERE operationId=?", arrayOf(t.requestId)) == 0L
                Actor.RECEIPT_INSERT -> scalar(raw, "SELECT count(*) FROM main.next_acceptances WHERE kind=? AND requestId=?", arrayOf(NEXT_OPERATION, t.requestId)) == 0L
                Actor.JOURNAL_INSERT -> scalar(raw, "SELECT count(*) FROM main.next_transmissions WHERE kind=? AND requestId=?", arrayOf(NEXT_OPERATION, t.requestId)) == 0L
                Actor.ROUND_INSERT -> scalar(raw, "SELECT count(*) FROM main.next_challenge_rounds WHERE roundUuid=?", arrayOf(values["roundUuid"])) == 0L
                Actor.QUEUE_DELETE -> scalar(raw, "SELECT count(*) FROM main.sync_outbox WHERE id=?", arrayOf(t.queueId)) == 1L
                Actor.CURSOR_UPDATE -> scalar(raw, "SELECT cursor FROM main.next_sync_state WHERE id=1") == 20L
                else -> false
            }
            check(absent)
        } else if (a.kind == Kind.DEFERRED_END) {
            check(scalar(raw, "PRAGMA foreign_keys") == 1L)
            check(scalar(raw, "SELECT count(*) FROM temp.once_existing_child WHERE id=1 AND parent=99") == 1L)
            raw.query("PRAGMA temp.foreign_key_check(once_existing_child)").use {
                check(it.moveToFirst() && it.getString(0) == "once_existing_child" && it.getLong(1) == 1L &&
                    it.getString(2) == "once_existing_parent" && !it.moveToNext())
            }
        } else for ((name, old) in effectBefore) {
            val expected = when (a.kind) {
                Kind.RECEIPT_SUBMISSION -> changed(old, "referenceUuid") { str(requireNotNull(t.replacementUuid)) }
                Kind.RECEIPT_FACT -> changed(old, "actualCompletedAt", ::increment)
                Kind.ROUND_FACT -> changed(old, "value") { Cell(Cursor.FIELD_TYPE_INTEGER, 99L) }
                Kind.RECEIPT_METADATA -> changed(old, "metadataHash") { str("bad") }
                Kind.RECEIPT_CURSORS, Kind.ORIGIN_CURSORS, Kind.JOURNAL_CURSORS -> changed(changed(old, "cursor", ::increment), "generation", ::increment)
                Kind.CURSOR_DAY_UPDATE -> changed(old, "targetValue") { Cell(Cursor.FIELD_TYPE_INTEGER, 11L) }
                Kind.CURSOR_DAY_DELETE -> old.copy(values = emptyList())
                Kind.RECEIPT_PARENT, Kind.CURSOR_METRIC -> {
                    val key = if (a.kind == Kind.RECEIPT_PARENT) "requestId" else "uuid"
                    val target = if (a.kind == Kind.RECEIPT_PARENT) t.otherRequestId else t.metricUuid
                    val field = if (a.kind == Kind.RECEIPT_PARENT) "intentJson" else "name"
                    val value = if (a.kind == Kind.RECEIPT_PARENT) "{}" else "late corruption"
                    val k = old.columns.indexOf(key); val f = old.columns.indexOf(field)
                    check(k >= 0 && f >= 0 && old.values.count { it[k].value == target } == 1)
                    old.copy(values = old.values.map { row -> if (row[k].value != target) row else row.toMutableList().also { it[f] = str(value) } }
                        .sortedBy { row -> row.joinToString("") { val s = it.value.toString(); "${it.type}:${s.length}:$s" } })
                }
                else -> error("Unexpected effect")
            }
            check(expected != old && table(raw, name) == expected)
        }
        a.effectSeen = true
    }
    private fun <T> execute(raw: SupportSQLiteDatabase, which: Actor, bound: Map<Int, Any?>, original: () -> T): T {
        val a = selected.get()
        if (a == null || a.closed || a.cleared || a.native !== raw || actor(a.kind) != which) return original()
        val values = keys(which, bound)
        if (!matches(a, values)) return original()
        a.actors.incrementAndGet()
        if (!a.claimed.compareAndSet(false, true)) { observe(a) { error("Fixed actor repeated") }; return original() }
        val frame = frames.get()
        var old = emptyMap<String, Rows>()
        observe(a) {
            val actual = requireNotNull(frame)
            beforeActor(a, values, actual); actual.actor = true
            old = effectTables(a).associateWith { table(raw, it) }
        }
        try {
            val result = original()
            a.returned.incrementAndGet()
            observe(a) {
                val saved = scalars(raw)
                afterActor(a, values, old)
                check(scalars(raw) == saved); a.scalarsKept = true
            }
            return result
        } catch (failure: SQLiteConstraintException) {
            if (abort(a.kind)) a.error.compareAndSet(null, failure)
            throw failure
        }
    }
    fun requireFailure(failure: Throwable) {
        val a = requireNotNull(selected.get())
        assertEquals(1, a.actors.get()); assertTrue(a.claimed.get()); assertTrue(a.actorSeen)
        assertEquals(if (abort(a.kind)) 0 else 1, a.returned.get())
        assertEquals(1, a.ends.get()); assertNull("Fault observations preserve the original outcome", a.observationFailure.get())
        if (abort(a.kind) || a.kind == Kind.DEFERRED_END) {
            val native = requireNotNull(a.error.get())
            assertTrue(generateSequence(failure) { it.cause }.any { it === native })
            assertTrue(native.message.orEmpty().contains(if (a.kind == Kind.DEFERRED_END) "FOREIGN KEY constraint failed" else message(a.kind)))
            if (a.kind == Kind.DEFERRED_END) { assertTrue(a.marks.get() > 0); assertEquals(1, a.endConstraints.get()) }
            else assertEquals(0, a.marks.get())
        } else {
            val early = setOf("COMMAND_PRIMARY_READ_EXPIRED", "COMMAND_PRIMARY_BINDING_CHANGED", "COMMAND_OLD_COUNT_SCHEMA_INVALID", "COUNT_TRUST_SCHEMA_CHANGED")
            assertFalse(generateSequence(failure) { it.cause }.any { it.message in early })
        }
        if (!abort(a.kind)) { assertTrue(a.effectSeen); assertTrue(a.scalarsKept) }
        if (a.kind != Kind.DEFERRED_END) assertTrue("Exact MAIN rollback after returned native END", a.warmEqual)
        a.reported = true
    }
    fun requireColdRollback() {
        val a = requireNotNull(selected.get()); val raw = requireNotNull(opened)
        assertTrue(a.reported); assertTrue(a.closed); assertNotSame(a.native, raw); assertTrue(raw.isOpen)
        assertFalse(raw.inTransaction()); assertEquals(0L, scalar(raw, "SELECT count(*) FROM temp.sqlite_master WHERE name IN ('once_existing_fault','once_existing_parent','once_existing_child')"))
        assertTrue("New physical owner matches the fault transaction's complete MAIN preimage", fullImage(raw) == requireNotNull(a.before))
    }
    fun clear() {
        val a = requireNotNull(selected.get()); check(a.reported && !a.cleared)
        val raw = requireNotNull(opened); check(!raw.inTransaction())
        val before = fullImage(raw)
        raw.execSQL("DROP TRIGGER IF EXISTS temp.once_existing_fault")
        raw.execSQL("DROP TABLE IF EXISTS temp.once_existing_child")
        raw.execSQL("DROP TABLE IF EXISTS temp.once_existing_parent")
        check(fullImage(raw) == before)
        a.cleared = true
    }
    private fun closeOwner(raw: SupportSQLiteDatabase?, original: () -> Unit) {
        selected.get()?.takeIf { it.native === raw }?.closed = true
        original()
    }
    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
        check(configuration.context.packageName == "com.dayforge.testbed")
        var native: SupportSQLiteDatabase? = null; var wrapped: SupportSQLiteDatabase? = null
        val monitor = Any()
        fun wrap(raw: SupportSQLiteDatabase): SupportSQLiteDatabase = synchronized(monitor) {
            if (native !== raw) {
                native = raw; opened = raw
                wrapped = object : SupportSQLiteDatabase by raw {
                    override fun close() = closeOwner(raw) { raw.close() }
                    override fun beginTransaction() = begin(raw) { raw.beginTransaction() }
                    override fun beginTransactionNonExclusive() = begin(raw) { raw.beginTransactionNonExclusive() }
                    override fun beginTransactionReadOnly() = begin(raw) { raw.beginTransactionReadOnly() }
                    override fun beginTransactionWithListener(transactionListener: SQLiteTransactionListener) = begin(raw) { raw.beginTransactionWithListener(transactionListener) }
                    override fun beginTransactionWithListenerNonExclusive(transactionListener: SQLiteTransactionListener) = begin(raw) { raw.beginTransactionWithListenerNonExclusive(transactionListener) }
                    override fun beginTransactionWithListenerReadOnly(transactionListener: SQLiteTransactionListener) = begin(raw) { raw.beginTransactionWithListenerReadOnly(transactionListener) }
                    override fun compileStatement(sql: String): SupportSQLiteStatement {
                        val statement = raw.compileStatement(sql)
                        val normalized = normalize(sql)
                        val which = Actor.entries.singleOrNull { it.normalizedSql == normalized } ?: return statement
                        val observed = which.parameters.mapIndexedNotNull { i, name -> if (name in setOf(
                            "id", "whereId", "operationId", "referenceUuid", "entityUuid", "recordType", "habitId", "uuid",
                            "oneTimeExpectedVersion", "eventUuid", "activityUuid", "roundUuid", "requestId", "kind", "queueId", "cursor")) i + 1 else null }.toSet()
                        return object : SupportSQLiteStatement by statement {
                            private val bound = hashMapOf<Int, Any?>()
                            override fun clearBindings() { bound.clear(); statement.clearBindings() }
                            override fun bindNull(index: Int) { if (index in observed) bound[index] = null; statement.bindNull(index) }
                            override fun bindLong(index: Int, value: Long) { if (index in observed) bound[index] = value; statement.bindLong(index, value) }
                            override fun bindString(index: Int, value: String) { if (index in observed) bound[index] = value; statement.bindString(index, value) }
                            override fun bindDouble(index: Int, value: Double) { if (index in observed) bound[index] = value; statement.bindDouble(index, value) }
                            override fun bindBlob(index: Int, value: ByteArray) { if (index in observed) bound[index] = Unit; statement.bindBlob(index, value) }
                            override fun execute() = execute(raw, which, bound.toMap()) { statement.execute() }
                            override fun executeInsert() = execute(raw, which, bound.toMap()) { statement.executeInsert() }
                            override fun executeUpdateDelete() = execute(raw, which, bound.toMap()) { statement.executeUpdateDelete() }
                        }
                    }
                    override fun setTransactionSuccessful() {
                        raw.setTransactionSuccessful()
                        frames.get()?.takeIf { it.native === raw && it.actor }?.attempt?.marks?.incrementAndGet()
                    }
                    override fun endTransaction() {
                        val frame = frames.get()?.takeIf { it.native === raw }
                        var returned = false
                        try { raw.endTransaction(); returned = true }
                        catch (failure: SQLiteConstraintException) {
                            if (frame?.actor == true && frame.attempt.kind == Kind.DEFERRED_END) {
                                frame.attempt.error.compareAndSet(null, failure); frame.attempt.endConstraints.incrementAndGet()
                            }
                            throw failure
                        } finally {
                            if (frame != null) observe(frame.attempt) {
                                if (!raw.inTransaction()) {
                                    try { if (frame.actor) {
                                        val a = frame.attempt; a.ends.incrementAndGet()
                                        if (a.kind != Kind.DEFERRED_END) { check(returned); a.warmEqual = fullImage(raw) == requireNotNull(a.before) }
                                    } } finally { frames.remove() }
                                }
                            }
                        }
                    }
                }
            }
            requireNotNull(wrapped)
        }
        val parent = configuration.callback
        val callback = object : SupportSQLiteOpenHelper.Callback(parent.version) {
            override fun onConfigure(db: SupportSQLiteDatabase) = parent.onConfigure(wrap(db))
            override fun onCreate(db: SupportSQLiteDatabase) = parent.onCreate(wrap(db))
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = parent.onUpgrade(wrap(db), oldVersion, newVersion)
            override fun onDowngrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = parent.onDowngrade(wrap(db), oldVersion, newVersion)
            override fun onOpen(db: SupportSQLiteDatabase) = parent.onOpen(wrap(db))
            override fun onCorruption(db: SupportSQLiteDatabase) = parent.onCorruption(wrap(db))
        }
        val helper = delegateFactory.create(SupportSQLiteOpenHelper.Configuration.builder(configuration.context)
            .name(configuration.name).callback(callback).noBackupDirectory(configuration.useNoBackupDirectory).build())
        return object : SupportSQLiteOpenHelper by helper {
            override fun close() = closeOwner(native) { helper.close() }
            override val writableDatabase get() = wrap(helper.writableDatabase)
            override val readableDatabase get() = wrap(helper.readableDatabase)
        }
    }
}
