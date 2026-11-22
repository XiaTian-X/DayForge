package com.dayforge.data.repository

import com.dayforge.data.local.CountTrustRuntime
import com.dayforge.data.local.LocalCoreWriteAccess
import java.util.Collections
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*

/** Immutable values from one authenticated snapshot. There is no Context, database, native
 * handle, Job, cursor, journal boundary or maintenance permission in the sealed result.
 * Revalidation authenticates a NEW root first and permits an unrelated ACK to advance it.
 */
internal class CountAddReadSet private constructor(
    private val incarnation: Any,
    private val epoch: Long,
    private val access: LocalCoreWriteAccess,
    private val binding: String,
    private val validator: String,
    private val schemaHash: String,
    private val environment: String,
    private val generation: Long,
    private val revision: Long,
    private val leaves: Map<String, String?>,
    private val selections: List<Selection>
) {
    private data class Selection(val key: String, val table: String,
        val equals: Map<String, Any>, val dateColumn: String?, val first: String?, val last: String?,
        val markerProjection: Boolean, val rows: List<String>) {
        fun where(): String = (equals.keys.map { "`$it`=?" } + if (dateColumn == null) emptyList()
            else listOf("`$dateColumn`>=?", "`$dateColumn`<?")).joinToString(" AND ").ifEmpty { "1=1" }
        fun args(): Array<out Any?> = (equals.values.toList() + listOfNotNull(first, last)).toTypedArray()
        fun image(row: CountTrustRows.Row): String = if (markerProjection)
            JsonObject(row.value.filterKeys { it != "metadataHash" }).toString() else row.value.toString()
    }

    /** A normal committed dependency change returns false, before any business write. Native
     * corruption, a bad Merkle proof or lost connection/account continuity is never a miss.
     * The caller retains original fresh authorization and the actual account/Room boundary.
     */
    suspend fun matchesCurrent(owner: CountTrustRuntime, context: CountTrustKernel.Context): Boolean {
        currentCoroutineContext().ensureActive()
        check(owner.countAddIncarnation === incarnation && owner.generation == epoch && !owner.isRetired &&
            context.access == access && context.header.binding == binding && context.header.validator == validator &&
            context.header.schemaHash == schemaHash && context.environment.toJson().toString() == environment &&
            context.header.generation >= generation && context.header.revision >= revision) {
            "COUNT_ADD_PREPARATION_EXPIRED"
        }
        return context.index.withReadScope {
            // Authenticate all selected native ranges against the current root even when a
            // different leaf already changed. Otherwise corruption could become a cache miss.
            val currentRows = selections.map { selection ->
                context.checked(selection.key, selection.table, selection.where(), selection.args())
                    .map(selection::image)
            }
            val currentLeaves = leaves.keys.associateWith { context.index.get(it)?.toString() }
            currentRows.zip(selections).all { (rows, selection) -> rows == selection.rows } && currentLeaves == leaves
        }
    }

    /** The builder is local to the private preparation phase and is not returned to callers.
     * Selecting a range binds authenticated membership AND fresh native type/value equality.
     */
    class Builder private constructor(private val owner: CountTrustRuntime,
        private val context: CountTrustKernel.Context, private val job: Job) {
        private var open = true
        private val leaves = linkedMapOf<String, String?>()
        private val selections = arrayListOf<Selection>()

        private suspend fun requireOpen() {
            currentCoroutineContext().ensureActive()
            check(open && currentCoroutineContext()[Job] === job) { "COUNT_ADD_FREEZE_EXPIRED" }
            check(context.index.get("format") == JsonPrimitive(CountTrustSeal.VALIDATOR)) { "COUNT_ADD_FREEZE_EXPIRED" }
        }

        suspend fun leaf(key: String): JsonElement? {
            requireOpen()
            val text = context.index.get(key)?.toString()
            if (leaves.containsKey(key)) check(leaves[key] == text) { "COUNT_ADD_FREEZE_CHANGED" }
            else leaves[key] = text
            return text?.let { Json.parseToJsonElement(it) }
        }

        suspend fun rows(key: String, table: String, equals: Map<String, Any>,
            dateColumn: String? = null, first: String? = null, last: String? = null,
            markerProjection: Boolean = false): List<CountTrustRows.Row> {
            requireOpen()
            val layout = context.rows.table(table)
            check(equals.keys.all { it in layout.columns } && equals.values.all { it is String || it is Long || it is Int })
            check((dateColumn == null) == (first == null) && (first == null) == (last == null))
            dateColumn?.let { check(it in layout.columns) }
            if (markerProjection) check(table == "next_challenge_state" &&
                key == CountTrustMembership.key("challenge.id", "1") && equals == mapOf("id" to 1) && dateColumn == null)
            val shape = Selection(key, table, Collections.unmodifiableMap(LinkedHashMap(equals)),
                dateColumn, first, last, markerProjection, emptyList())
            val actual = context.checked(key, table, shape.where(), shape.args())
            selections += shape.copy(rows = Collections.unmodifiableList(actual.map(shape::image)))
            return Collections.unmodifiableList(actual.map { row -> CountTrustRows.Row(row.table.copy(
                columns = Collections.unmodifiableList(row.table.columns.toList()),
                primary = Collections.unmodifiableList(row.table.primary.toList())), row.cells) })
        }

        suspend fun seal(): CountAddReadSet {
            requireOpen(); open = false
            return CountAddReadSet(owner.countAddIncarnation, owner.generation,
                context.access.copy(capabilities = context.access.capabilities?.let { Collections.unmodifiableSet(it.toSet()) }),
                context.header.binding, context.header.validator, context.header.schemaHash,
                context.environment.toJson().toString(), context.header.generation, context.header.revision,
                Collections.unmodifiableMap(LinkedHashMap(leaves)), Collections.unmodifiableList(selections.toList()))
        }

        companion object {
            suspend fun create(owner: CountTrustRuntime, context: CountTrustKernel.Context): Builder =
                Builder(owner, context, requireNotNull(currentCoroutineContext()[Job])).also { it.requireOpen() }
        }
    }
}
