package com.dayforge.data.repository

import java.util.Collections

/** The only two additional tables admitted by the P2 experiment. This is not a registry.
 * Creation belongs to the explicitly trusted new-schema/reference path; never repair a
 * missing or changed table while opening an existing database. Mutation capability is
 * separately owned and audited by the count-certificate maintenance profile.
 */
internal object G0CountCertificateSql {
    const val table = "g0_count_certificate"
    val headerColumns: List<String> = Collections.unmodifiableList(listOf(
        "slot", "format", "contextHash", "inputHash", "textHash", "imageBytes", "chunkCount", "imageHash", "mac"
    ))
    const val chunkTable = "g0_count_image_chunks"
    val chunkColumns: List<String> = Collections.unmodifiableList(listOf("chunkIndex", "content"))
    val tables: Map<String, List<String>> = Collections.unmodifiableMap(linkedMapOf(
        table to headerColumns, chunkTable to chunkColumns
    ))
    const val maxChunkBytes = 64 * 1024
    const val create = "CREATE TABLE g0_count_certificate (slot INTEGER NOT NULL PRIMARY KEY CHECK(slot = 1), format INTEGER NOT NULL, contextHash TEXT NOT NULL, inputHash TEXT NOT NULL, textHash TEXT NOT NULL, imageBytes INTEGER NOT NULL, chunkCount INTEGER NOT NULL, imageHash TEXT NOT NULL, mac BLOB NOT NULL) WITHOUT ROWID"
    const val chunkCreate = "CREATE TABLE g0_count_image_chunks (chunkIndex INTEGER NOT NULL PRIMARY KEY CHECK(chunkIndex>=0), content BLOB NOT NULL) WITHOUT ROWID"
    const val select = "SELECT * FROM main.`g0_count_certificate`"
    const val chunkSelect = "SELECT * FROM main.`g0_count_image_chunks`"

    const val deleteHeader = "DELETE FROM g0_count_certificate"
    const val deleteChunks = "DELETE FROM g0_count_image_chunks"
    const val insertHeader = "INSERT OR ABORT INTO g0_count_certificate (slot,format,contextHash,inputHash,textHash,imageBytes,chunkCount,imageHash,mac) VALUES (?,?,?,?,?,?,?,?,?)"
    const val insertChunk = "INSERT OR ABORT INTO g0_count_image_chunks (chunkIndex,content) VALUES (?,?)"
    val writes: Map<String,String> = mapOf(deleteHeader to "",deleteChunks to "",
        insertHeader to "VVVVVVVVV",insertChunk to "VV")

    /** Exact sqlite_master spelling of the six triggers produced by the unchanged
     * NextReplicaFenceSql.install. SQLite stores CREATE TRIGGER without its optional
     * IF NOT EXISTS clause; the remaining original body is preserved byte-for-byte.
     * These are required protections, not optional/ignored schema objects.
     */
    val replicaFenceTriggers: Map<String,String> = Collections.unmodifiableMap(linkedMapOf<String,String>().apply {
        for (name in tables.keys) for (action in listOf("INSERT", "UPDATE", "DELETE")) {
            val trigger = "replica_fence_${name}_${action.lowercase(java.util.Locale.ROOT)}"
            put(trigger, """
                CREATE TRIGGER $trigger
                BEFORE $action ON `$name` WHEN EXISTS(SELECT 1 FROM next_replica_fence WHERE id IS NOT 1 OR mode IS NOT 'closed')
                BEGIN SELECT RAISE(ABORT,'SYNC_REPLICA_SWITCH_FENCED'); END
            """.trimIndent())
        }
    })

    /** Exact tables plus the original replica-fence triggers. No extra index/trigger
     * and no missing, renamed or weakened protection is admitted.
     */
    fun requireSchema(schema: List<List<String?>>) {
        check(schema.all { it.size == 5 && it[0] == "main" }) { "G0_COUNT_CERTIFICATE_SCHEMA" }
        val owned = schema.filter { row -> tables.keys.any { name -> row[2] == name || row[3] == name } }
        val expected = (listOf(listOf("main", "table", table, table, create),
            listOf("main", "table", chunkTable, chunkTable, chunkCreate)) +
            tables.keys.flatMap { name -> replicaFenceTriggers.filterKeys { trigger ->
                trigger.startsWith("replica_fence_${name}_")
            }.map { (trigger,sql) -> listOf("main", "trigger", trigger, name, sql) } })
            .sortedWith(compareBy<List<String>> { it[1] }.thenBy { it[2] })
        check(owned == expected) {
            "G0_COUNT_CERTIFICATE_SCHEMA"
        }
    }
}
