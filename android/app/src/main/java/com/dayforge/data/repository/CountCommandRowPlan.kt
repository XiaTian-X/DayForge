package com.dayforge.data.repository

import java.util.Collections
import kotlinx.serialization.json.*

/** Pure native rows independently derived from frozen intent/rules/result. Native SQL must
 * first prove exact SQLite types and compare every column before constructing a Row. These
 * values are plans, not a journal-derived list of allowed writes and not a signing capability.
 */
internal object CountCommandRowPlan {
    enum class Table(val sqlName: String, columns: List<String>) {
        INTENT("count_command_intents", listOf("commandId", "namespaceHash", "inputHash", "format", "action", "intentJson")),
        STATUS("count_command_status", listOf("commandId", "inputHash", "revision", "execution", "delivery",
            "parentCommandId", "childCommandId", "previousPendingId", "nextPendingId", "stateJson", "businessGenerationAtAdmission")),
        OUTCOME("count_command_results", listOf("commandId", "inputHash", "factUuid", "requestId", "resultJson", "state"));
        val columns: List<String> = Collections.unmodifiableList(columns.toList())
    }
    sealed interface Cell {
        data object Null : Cell
        data class Text(val value: String) : Cell
        data class Integer(val value: Long) : Cell
    }
    class Row(val table: Table, cells: List<Cell>) {
        val cells: List<Cell> = Collections.unmodifiableList(cells.toList())
        init { require(this.cells.size == table.columns.size && this.cells.first() is Cell.Text) }
        val utf8Bytes: Long get() = cells.sumOf { when (it) { is Cell.Text -> it.value.toByteArray(Charsets.UTF_8).size.toLong(); is Cell.Integer -> 20L; Cell.Null -> 0L } }
        val commandId: String get() = (cells.first() as Cell.Text).value
        val hash: String get() = CountCommandRecords.hash("native-row", buildJsonObject {
            put("table", table.sqlName)
            put("columns", JsonArray(table.columns.map(::JsonPrimitive)))
            put("cells", JsonArray(cells.map { cell -> when (cell) {
                Cell.Null -> buildJsonArray { add("null") }
                is Cell.Integer -> buildJsonArray { add("integer"); add(cell.value.toString()) }
                is Cell.Text -> buildJsonArray { add("text"); add(cell.value) }
            } }))
        }.toString())
        override fun equals(other: Any?): Boolean = other is Row && table == other.table && cells == other.cells
        override fun hashCode(): Int = 31 * table.hashCode() + cells.hashCode()
    }
    data class Mutation(val before: Row?, val after: Row) {
        init { require(before == null || before.table == after.table && before.commandId == after.commandId) }
    }
    /** Owned pure values, never a cached native read or authentication result. The ordinary
     * constructor/member path retains no projection. The dedicated T2 native reader opts into
     * value projection only AFTER executing the same complete native/codec checks. */
    class Aggregate private constructor(val intent: CountCommandRecords.Intent, val status: CountCommandRecords.Status,
        val outcome: CountCommandOutcome?, previous: Aggregate?, projectStatus: Boolean) {
        constructor(intent: CountCommandRecords.Intent, status: CountCommandRecords.Status,
            outcome: CountCommandOutcome?) : this(intent, status, outcome, null, false)

        init {
            if (previous == null) CountCommandTransitionRules.requireAggregate(intent, status, outcome)
            else {
                require(previous.intent === intent && previous.outcome === outcome)
                CountCommandTransitionRules.requireStatusWithSameResult(previous, status)
            }
        }
        private class Image(val row: Row) {
            val hash: String by lazy(LazyThreadSafetyMode.PUBLICATION) { row.hash }
            val bytes: Long by lazy(LazyThreadSafetyMode.PUBLICATION) { row.utf8Bytes }
        }
        private class ImmutableImages(originalIntent: CountCommandRecords.Intent, originalOutcome: CountCommandOutcome?) {
            val intent: Image by lazy(LazyThreadSafetyMode.PUBLICATION) { Image(CountCommandRowPlan.intent(originalIntent)) }
            val outcome: Image? by lazy(LazyThreadSafetyMode.PUBLICATION) { originalOutcome?.let { Image(CountCommandRowPlan.outcome(it)) } }
        }
        private class Projection(val immutable: ImmutableImages, intent: CountCommandRecords.Intent,
            status: CountCommandRecords.Status) {
            val state: Image by lazy(LazyThreadSafetyMode.PUBLICATION) { Image(CountCommandRowPlan.status(status)) }
            val catalog: CountCommandRecords.CatalogMember by lazy(LazyThreadSafetyMode.PUBLICATION) {
                val intentImage = immutable.intent
                val intentHash = intentImage.hash
                val outcomeImage = immutable.outcome
                val outcomeHash = outcomeImage?.hash
                val statusImage = state
                val statusHash = statusImage.hash
                CountCommandRecords.CatalogMember(intent.commandId, intent.inputHash,
                    intentHash, outcomeHash, statusHash, intentImage.bytes, statusImage.bytes, outcomeImage?.bytes)
            }
        }
        private val projection: Projection? = if (projectStatus)
            Projection(previous?.projection?.immutable ?: ImmutableImages(intent, outcome), intent, status) else null

        fun member(): CountCommandRecords.CatalogMember {
            projection?.let { return it.catalog }
            val intentRow = CountCommandRowPlan.intent(intent)
            val intentHash = intentRow.hash
            val outcomeRow = outcome?.let { CountCommandRowPlan.outcome(it) }
            val outcomeHash = outcomeRow?.hash
            val statusRow = CountCommandRowPlan.status(status)
            val statusHash = statusRow.hash
            return CountCommandRecords.CatalogMember(intent.commandId, intent.inputHash,
                intentHash, outcomeHash, statusHash, intentRow.utf8Bytes, statusRow.utf8Bytes, outcomeRow?.utf8Bytes)
        }
        internal fun intentImage(): Row = projection?.immutable?.intent?.row ?: CountCommandRowPlan.intent(intent)
        internal fun statusImage(): Row = projection?.state?.row ?: CountCommandRowPlan.status(status)
        internal fun outcomeImage(): Row? = if (projection == null) outcome?.let { CountCommandRowPlan.outcome(it) }
            else projection.immutable.outcome?.row

        /** Only Status varies; this cannot change immutable input/result or grant authority. */
        internal fun withStatus(next: CountCommandRecords.Status): Aggregate = Aggregate(intent, next, outcome, this, true)

        companion object {
            /** Same full Aggregate validation. This factory only owns pure row projections;
             * catalog/native binding still belongs to the dedicated NativeRows issuer. */
            internal fun forStatusChanges(intent: CountCommandRecords.Intent, status: CountCommandRecords.Status,
                outcome: CountCommandOutcome?): Aggregate = Aggregate(intent, status, outcome, null, true)
        }
    }

    fun intent(value: CountCommandRecords.Intent) = Row(Table.INTENT, listOf(Cell.Text(value.commandId),
        Cell.Text(value.namespace.hash), Cell.Text(value.inputHash), Cell.Integer(value.format.toLong()),
        Cell.Text(value.action.name), Cell.Text(value.text())))
    fun status(value: CountCommandRecords.Status) = Row(Table.STATUS, listOf(Cell.Text(value.commandId), Cell.Text(value.inputHash),
        Cell.Integer(value.revision), Cell.Text(value.execution.name), Cell.Text(value.delivery.name),
        text(value.parent), text(value.child), text(value.previous), text(value.next),
        Cell.Text(CountCommandRecords.canonical(value.body())), Cell.Integer(value.businessGenerationAtAdmission)))
    fun outcome(value: CountCommandOutcome): Row = value.row().let { Row(Table.OUTCOME, listOf(Cell.Text(it.commandId),
        Cell.Text(it.inputHash), Cell.Text(it.factUuid), text(it.requestId), Cell.Text(it.resultJson), Cell.Text(it.state))) }

    /** A single registration or execution/delivery batch. Old state is independently proven
     * against the current root; new state is generated by typed lifecycle/list rules. One native
     * mutation per row makes extra same-value UPDATEs observable to the private primary journal.
     */
    class Batch internal constructor(before: List<Aggregate>, after: List<Aggregate>) {
        val before: List<Aggregate> = Collections.unmodifiableList(before.toList())
        val after: List<Aggregate> = Collections.unmodifiableList(after.toList())
        val mutations: List<Mutation>
        val catalogEdits: List<Pair<CountCommandRecords.CatalogMember?, CountCommandRecords.CatalogMember>>
        init {
            require(this.after.isNotEmpty() && (this.before + this.after).map { it.intent.namespace.catalogBinding }.distinct().size == 1)
            require(this.before.map { it.intent.commandId }.distinct().size == this.before.size)
            require(this.after.map { it.intent.commandId }.distinct().size == this.after.size)
            val old = this.before.associateBy { it.intent.commandId }
            require(this.after.map { it.intent.commandId }.containsAll(old.keys)) // No command deletion/GC.
            val next = arrayListOf<Mutation>()
            val members = arrayListOf<Pair<CountCommandRecords.CatalogMember?, CountCommandRecords.CatalogMember>>()
            for (a in this.after) {
                val b = old[a.intent.commandId]
                if (b == null) {
                    require(a.status.execution == CountCommandRecords.Execution.PREPARED && a.status.revision == 1L && a.outcome == null)
                    next += Mutation(null, intent(a.intent)); next += Mutation(null, status(a.status))
                } else {
                    require(b.intentImage() == a.intentImage()) { "COMMAND_INTENT_IMMUTABLE" }
                    require(a.status.revision == Math.addExact(b.status.revision, 1L) &&
                        a.status.businessGenerationAtAdmission == b.status.businessGenerationAtAdmission) { "COMMAND_ADMISSION_BASELINE_IMMUTABLE" }
                    next += Mutation(b.statusImage(), a.statusImage())
                    if (b.outcome != null) require(a.outcome != null && b.outcomeImage() == a.outcomeImage()) { "COMMAND_OUTCOME_IMMUTABLE" }
                    else if (a.outcome != null) next += Mutation(null, outcome(a.outcome))
                }
                members += b?.member() to a.member()
            }
            mutations = Collections.unmodifiableList(next)
            catalogEdits = Collections.unmodifiableList(members)
        }
    }

    private fun text(value: String?): Cell = value?.let { Cell.Text(it) } ?: Cell.Null
}
