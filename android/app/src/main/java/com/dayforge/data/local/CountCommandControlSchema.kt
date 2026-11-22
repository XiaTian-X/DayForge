package com.dayforge.data.local

/** Frozen v19 declarations remain untouched. Only the exact nullable v20 anchor column and
 * matching fence predicate are reversible here; no other token or old byte is normalized.
 */
internal object CountCommandControlSchema {
    const val TABLE = "count_trust_control"
    const val GUARD = "replica_fence_count_trust_control_update"
    const val COLUMN = "commandRootHash"
    private const val INSERTED_PREDICATE = " AND NEW.commandRootHash IS OLD.commandRootHash"
    private val columnTokens = listOf("word:commandroothash", "word:text", "word:default", "word:null")
    private val oldGuard: String get() = NextReplicaFenceSql.localTrustDefinitions().getValue(GUARD)
    private val oldTable: String get() = CountTrustSchema.ownedDefinitions().getValue(TABLE).second
    private fun withoutIfNotExists(tokens: List<String>): List<String> =
        if (tokens.drop(2).take(3) == listOf("word:if", "word:not", "word:exists")) tokens.take(2) + tokens.drop(5) else tokens

    val declaredTable: String get() = oldTable.replace("`dirty` INTEGER NOT NULL", "`dirty` INTEGER NOT NULL, `commandRootHash` TEXT DEFAULT NULL")
    val declaredGuard: String get() = upgradeGuard(oldGuard)

    fun previousTable(actual: String): String {
        val spans = ReplicaSchemaSql.tokenSpans(actual)
        val tokens = spans.map { it.value }
        val clauses = ReplicaSchemaSql.table(tokens, TABLE)
        require(clauses.count { it == columnTokens } == 1 &&
            clauses.filterNot { it == columnTokens } == ReplicaSchemaSql.table(ReplicaSchemaSql.tokens(oldTable), TABLE)) {
            "COMMAND_CONTROL_SCHEMA_CHANGED"
        }
        val starts = tokens.indices.filter { tokens.drop(it).take(columnTokens.size) == columnTokens }
        require(starts.size == 1)
        val at = starts.single()
        require(at > 0 && tokens[at - 1] == ",") { "COMMAND_CONTROL_COLUMN_POSITION" }
        val previous = actual.removeRange(spans[at - 1].start, spans[at + columnTokens.size - 1].end)
        require(CountTrustSchema.matchesControlDefinition(previous)) { "COMMAND_CONTROL_OLD_SCHEMA_CHANGED" }
        return previous
    }
    fun matchesTable(actual: String): Boolean = try { previousTable(actual); true }
        catch (_: IllegalArgumentException) { false }
        catch (_: IllegalStateException) { false }

    fun upgradeGuard(original: String): String {
        val spans = ReplicaSchemaSql.tokenSpans(original)
        require(withoutIfNotExists(spans.map { it.value }) == withoutIfNotExists(ReplicaSchemaSql.tokens(oldGuard))) {
            "COMMAND_CONTROL_OLD_GUARD_CHANGED"
        }
        val needle = listOf("word:new", ".", "word:dirty", "=", "word:1")
        val starts = spans.indices.filter { at -> spans.drop(at).take(needle.size).map { it.value } == needle }
        require(starts.size == 1)
        val end = spans[starts.single() + needle.size - 1].end
        return original.substring(0, end) + INSERTED_PREDICATE + original.substring(end)
    }
    fun previousGuard(actual: String): String {
        // The trusted migrator inserts exactly these bytes; they are also used for new files.
        // Comparing tokens first prevents removing text embedded in a different predicate.
        val expected = withoutIfNotExists(ReplicaSchemaSql.tokens(declaredGuard))
        require(withoutIfNotExists(ReplicaSchemaSql.tokens(actual)) == expected) { "COMMAND_CONTROL_GUARD_CHANGED" }
        require(actual.indexOf(INSERTED_PREDICATE) >= 0 && actual.indexOf(INSERTED_PREDICATE) == actual.lastIndexOf(INSERTED_PREDICATE)) {
            "COMMAND_CONTROL_GUARD_BYTES_CHANGED"
        }
        return actual.replace(INSERTED_PREDICATE, "").also {
            require(withoutIfNotExists(ReplicaSchemaSql.tokens(it)) == withoutIfNotExists(ReplicaSchemaSql.tokens(oldGuard)))
        }
    }
    fun matchesGuard(actual: String): Boolean = try { previousGuard(actual); true }
        catch (_: IllegalArgumentException) { false }
        catch (_: IllegalStateException) { false }
}
