package com.dayforge.data.repository

import androidx.sqlite.db.SupportSQLiteDatabase
import android.database.Cursor
import java.util.Collections
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** A private index of a verified baseline, NOT a validator or authorization cache. The owner
 * must run the original validators before construction. Incremental changes are staged against
 * exact OLD cells, then promoted only after a confirmed commit on the same sealed connection.
 * Invalidation includes both old/new membership and negative name/queue dependencies.
 */
internal class G0DomainProofIndex private constructor(
    private val rows: MutableMap<Key, List<G0DomainJournal.Cell>>
) {
    data class Key(val table: String, val values: List<G0DomainJournal.Cell>)
    /** Pure captured data. This type cannot issue witnesses, accept writes or claim that the
     * original validators ran. The cold owner must separately validate and confirm its END
     * before any later, separately controlled installation can use these rows.
     */
    class StagedBaseline private constructor(
        internal val input:G0FrozenDomainInputs,
        private val rows:Map<Key,List<G0DomainJournal.Cell>>,
        private val keys:Map<String,List<Key>>,
        val tableCounts:Map<String,Int>
    ) {
        private var activated=false
        fun row(key:Key):List<G0DomainJournal.Cell>? = rows[key]
        fun members(table:String):List<Key> = keys.getValue(table)
        @Synchronized fun activate(end:G0RoomColdOwnerFactory.KnownEnd):G0DomainProofIndex {
            check(!activated) {"G0_FROZEN_INDEX_ALREADY_ACTIVATED"}
            end.claimSnapshot(input)
            activated=true
            return G0DomainProofIndex(rows.toMutableMap())
        }

        companion object {
            internal suspend fun capture(input:G0FrozenDomainInputs):StagedBaseline {
                val protectedCatalog=G0PersistentCheckpoint.columns
                val catalog=if(input.countCertificateScope) {
                    G0CountCertificateSql.requireSchema(input.schema)
                    check(G0CountCertificateSql.tables.keys.none {it in protectedCatalog}) {"G0_FROZEN_INDEX_TABLES"}
                    check(input.textDigest()!=null) {"G0_FROZEN_INDEX_TEXT_ROOT"}
                    protectedCatalog+G0CountCertificateSql.tables
                } else {
                    check(input.textDigest()==null) {"G0_FROZEN_INDEX_TEXT_ROOT"}
                    protectedCatalog
                }
                check(input.tables.keys==catalog.keys) {"G0_FROZEN_INDEX_TABLES"}
                check(G0DomainSqlCatalog.primaryKeys.keys==G0DomainSqlCatalog.columns.keys) {"G0_FROZEN_INDEX_KEYS"}
                check(input.schema.all {it.size==5 && it[0]=="main"}) {"G0_FROZEN_INDEX_SCHEMA"}
                val declared=input.schema.filter {it[1]=="table"}.map {it[2]}
                check(declared.size==catalog.size && declared.toSet()==catalog.keys) {"G0_FROZEN_INDEX_SCHEMA"}
                val indexed=linkedMapOf<Key,List<G0DomainJournal.Cell>>()
                val members=G0DomainSqlCatalog.columns.keys.associateWith {mutableListOf<Key>()}
                val counts=linkedMapOf<String,Int>()
                for((table,columns) in catalog) {
                    currentCoroutineContext().ensureActive()
                    val source=input.tables.getValue(table)
                    check(source.columns==columns) {"G0_FROZEN_INDEX_COLUMNS"}
                    if(table in G0DomainSqlCatalog.columns) {
                        val primary=G0DomainSqlCatalog.primaryKeys.getValue(table)
                        check(primary.isNotEmpty() && primary.distinct().size==primary.size &&
                            primary.all {it in columns}) {"G0_FROZEN_INDEX_KEYS"}
                    }
                    counts[table]=source.rows.size
                    for(sourceRow in source.rows) {
                        currentCoroutineContext().ensureActive()
                        check(sourceRow.cells.size==columns.size) {"G0_FROZEN_INDEX_COLUMNS"}
                        if(input.countCertificateScope && table in G0CountCertificateSql.tables) {
                            // Capture contains both complete optional tables. Check native cell
                            // shape, but do not hydrate its potentially large image into boxed
                            // index cells, issue witnesses for it, or treat MAC/format as proven.
                            // Packet semantics belong to the separate bounded certificate reader.
                            sourceRow.cells.forEach { cell ->
                                when(cell.type) {
                                    Cursor.FIELD_TYPE_NULL->check(cell.number==null && !cell.hasBytes && cell.text==null)
                                    Cursor.FIELD_TYPE_INTEGER,Cursor.FIELD_TYPE_FLOAT->
                                        check(cell.number!=null && !cell.hasBytes && cell.text==null)
                                    Cursor.FIELD_TYPE_STRING->check(cell.number==null && cell.hasBytes && cell.text!=null)
                                    Cursor.FIELD_TYPE_BLOB->check(cell.number==null && cell.hasBytes && cell.text==null)
                                    else->error("G0_FROZEN_INDEX_CELL")
                                }
                            }
                            continue
                        }
                        val row=sourceRow.cells.map { cell ->
                            val value=when(cell.type) {
                                Cursor.FIELD_TYPE_NULL->{
                                    check(cell.number==null && !cell.hasBytes && cell.text==null) {"G0_FROZEN_INDEX_CELL"}
                                    null
                                }
                                Cursor.FIELD_TYPE_INTEGER,Cursor.FIELD_TYPE_FLOAT->{
                                    check(cell.number!=null && !cell.hasBytes && cell.text==null) {"G0_FROZEN_INDEX_CELL"}
                                    cell.number
                                }
                                Cursor.FIELD_TYPE_STRING->{
                                    check(cell.number==null && cell.hasBytes && cell.text!=null) {"G0_FROZEN_INDEX_CELL"}
                                    cell.text
                                }
                                Cursor.FIELD_TYPE_BLOB->{
                                    check(cell.number==null && cell.hasBytes && cell.text==null) {"G0_FROZEN_INDEX_CELL"}
                                    immutable(requireNotNull(cell.bytes()).toList())
                                }
                                else->error("G0_FROZEN_INDEX_CELL")
                            }
                            G0DomainJournal.Cell(cell.type,value)
                        }
                        if(table in G0DomainSqlCatalog.columns) {
                            val stable=immutable(row)
                            val key=key(table,stable).let {Key(it.table,immutable(it.values))}
                            check(key !in indexed) {"G0_FROZEN_INDEX_DUPLICATE_KEY"}
                            indexed[key]=stable
                            members.getValue(table).add(key)
                        }
                    }
                }
                check(counts.values.sumOf {it.toLong()}==input.scannedRows) {"G0_FROZEN_INDEX_MEMBERS"}
                return StagedBaseline(input,Collections.unmodifiableMap(indexed),
                    Collections.unmodifiableMap(members.mapValues {immutable(it.value)}),
                    Collections.unmodifiableMap(counts))
            }
            private fun <T> immutable(values:List<T>):List<T> =
                Collections.unmodifiableList(ArrayList(values))
        }
    }
    sealed interface Dependency {
        data class Row(val key: Key) : Dependency
        data class Table(val table: String) : Dependency
        data class Range(val table: String, val column: String, val value: G0DomainJournal.Cell) : Dependency
    }
    class Witness internal constructor(internal val owner: G0DomainProofIndex,
        internal val versions: Map<Dependency, Long>)
    class Advancement internal constructor(internal val owner: G0DomainProofIndex,
        internal val revision: Long, internal val changes: List<G0DomainJournal.Change>,
        internal val patches: Map<Key, List<G0DomainJournal.Cell>?>, internal val affected: Set<Dependency>)
    private var revision = 0L
    private val versions = mutableMapOf<Dependency, Long>()
    private var valid = true
    fun invalidate() { valid = false }
    fun witness(dependencies: Set<Dependency>): Witness {
        check(valid && dependencies.isNotEmpty())
        return Witness(this, dependencies.associateWith { versions[it] ?: 0L })
    }
    fun isCurrent(witness: Witness): Boolean {
        check(valid && witness.owner === this) { "G0_DOMAIN_CERTIFICATE_INVALID" }
        return witness.versions.all { (key, value) -> (versions[key] ?: 0L) == value }
    }
    fun requireCurrent(witness: Witness) {
        check(isCurrent(witness)) { "G0_DOMAIN_DEPENDENCY_CHANGED" }
    }
    fun row(key: Key): List<G0DomainJournal.Cell>? { check(valid); return rows[key]?.toList() }
    fun prepare(changes: List<G0DomainJournal.Change>): Advancement {
        check(valid)
        val patches = linkedMapOf<Key, List<G0DomainJournal.Cell>?>()
        val affected = linkedSetOf<Dependency>()
        fun current(key: Key) = if (key in patches) patches[key] else rows[key]
        for (change in changes) {
            fun affect(row: List<G0DomainJournal.Cell>) {
                affected += Dependency.Row(key(change.table, row))
                affected += Dependency.Table(change.table)
                // Fixed complete per-column membership. Cost is columns in changed rows, not
                // total history. No caller can forget to include a moved row's old range.
                G0DomainSqlCatalog.columns.getValue(change.table).forEachIndexed { i, column ->
                    affected += Dependency.Range(change.table, column, row[i])
                }
            }
            change.old?.let { old ->
                val key = key(change.table, old)
                check(current(key) == old) { "G0_DOMAIN_OLD_ROW_MISMATCH" }
                affect(old); patches[key] = null
            }
            change.new?.let { new ->
                val key = key(change.table, new)
                check(current(key) == null) { "G0_DOMAIN_DUPLICATE_INSERT" }
                affect(new); patches[key] = new.toList()
            }
        }
        return Advancement(this, revision, changes.toList(), patches.toMap(), affected.toSet())
    }
    fun committed(prepared: Advancement, actual: List<G0DomainJournal.Change>) {
        check(valid && prepared.owner === this && prepared.revision == revision && prepared.changes == actual) { "G0_DOMAIN_COMMIT_CERTIFICATE_CHANGED" }
        prepared.patches.forEach { (key, row) -> if (row == null) rows.remove(key) else rows[key] = row }
        if (actual.isNotEmpty()) revision++
        prepared.affected.forEach { versions[it] = revision }
    }
    companion object {
        /** Builds data only. It deliberately returns no usable G0DomainProofIndex. */
        suspend fun stageFrozen(input:G0FrozenDomainInputs):StagedBaseline = StagedBaseline.capture(input)

        fun key(table: String, row: List<G0DomainJournal.Cell>): Key {
            val columns = G0DomainSqlCatalog.columns.getValue(table)
            check(row.size == columns.size)
            return Key(table, G0DomainSqlCatalog.primaryKeys.getValue(table).map { row[columns.indexOf(it)] })
        }
        suspend fun captureVerifiedBaseline(sql: SupportSQLiteDatabase): G0DomainProofIndex {
            check(sql.inTransaction())
            val rows = linkedMapOf<Key, List<G0DomainJournal.Cell>>()
            for ((table, columns) in G0DomainSqlCatalog.columns) {
                sql.query("SELECT * FROM main.`$table`").use { c ->
                    check(c.columnNames.toList() == columns)
                    while (c.moveToNext()) {
                        currentCoroutineContext().ensureActive()
                        val row = columns.indices.map { i -> G0DomainJournal.Cell(c.getType(i), when(c.getType(i)) {
                            Cursor.FIELD_TYPE_NULL -> null
                            Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                            Cursor.FIELD_TYPE_FLOAT -> java.lang.Double.doubleToRawLongBits(c.getDouble(i))
                            Cursor.FIELD_TYPE_STRING -> c.getString(i)
                            Cursor.FIELD_TYPE_BLOB -> c.getBlob(i).toList()
                            else -> error("G0_DOMAIN_CELL_TYPE")
                        }) }
                        check(rows.put(key(table, row), row) == null)
                    }
                }
            }
            return G0DomainProofIndex(rows)
        }
    }
}
