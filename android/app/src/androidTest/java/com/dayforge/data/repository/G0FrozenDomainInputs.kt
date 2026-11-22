package com.dayforge.data.repository

import android.database.Cursor
import com.dayforge.data.local.HabitDatabase
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.OutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.Collections
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Data only. A capture is neither a business certificate nor permission to publish or write.
 * The original validators and fresh authority checks still run in the same coldRead scope.
 * No SQL handle, cursor, mutable byte array or authorization object escapes in this value.
 */
internal class G0FrozenDomainInputs private constructor(
    val schema: List<List<String?>>,
    val userVersion: Long,
    val tables: Map<String, Table>,
    val scannedRows: Long,
    val encodedBytes: Long,
    private val contentDigest: ByteArray,
    val countCertificateScope: Boolean,
    private val textContentDigest: ByteArray?
) {
    class Cell internal constructor(val type: Int, val number: Long?, bytes: ByteArray?,
        val text: String? = null) {
        private val content = bytes?.copyOf()
        internal val hasBytes: Boolean get() = content != null
        fun bytes(): ByteArray? = content?.copyOf()
        internal fun encode(out: DataOutputStream) {
            out.writeInt(type)
            when (type) {
                Cursor.FIELD_TYPE_NULL -> Unit
                Cursor.FIELD_TYPE_INTEGER, Cursor.FIELD_TYPE_FLOAT -> out.writeLong(requireNotNull(number))
                Cursor.FIELD_TYPE_STRING, Cursor.FIELD_TYPE_BLOB -> {
                    val value = requireNotNull(content)
                    out.writeInt(value.size)
                    out.write(value)
                }
                else -> error("G0_FROZEN_TYPE")
            }
        }
        /** Additional semantic observation; never replace the original native encoding.
         * UTF-16 code units preserve the actual String, including isolated surrogates.
         * writeUTF/toByteArray(UTF_8) would use a different or lossy representation.
         */
        internal fun encodeWithText(out: DataOutputStream, checkpoint: () -> Unit) {
            encode(out)
            if (type == Cursor.FIELD_TYPE_STRING) {
                val observed = requireNotNull(text)
                out.writeInt(observed.length)
                observed.forEachIndexed { index, value ->
                    if ((index and 1023) == 0) checkpoint()
                    out.writeChar(value.code)
                }
            }
        }
    }
    class Row internal constructor(cells: List<Cell>) {
        val cells: List<Cell> = immutable(cells)
    }
    class Table internal constructor(columns: List<String>, rows: List<Row>) {
        val columns: List<String> = immutable(columns)
        val rows: List<Row> = immutable(rows)
    }
    class BudgetExceeded : IllegalStateException("G0_FROZEN_BUDGET")

    fun digest(): ByteArray = contentDigest.copyOf()
    val digestHex: String get() = hex(contentDigest)
    /** Null on the legacy path. Neither digest is a business-validation certificate. */
    fun textDigest(): ByteArray? = textContentDigest?.copyOf()
    val textDigestHex: String? get() = textContentDigest?.let(::hex)

    companion object {
        const val DEFAULT_MAX_BYTES = 32L * 1024 * 1024
        private fun <T> immutable(values: List<T>): List<T> =
            Collections.unmodifiableList(ArrayList(values))
        private val sink = object : OutputStream() {
            override fun write(b: Int) = Unit
            override fun write(b: ByteArray, off: Int, len: Int) = Unit
        }
        private fun stream(digest: MessageDigest) =
            DataOutputStream(BufferedOutputStream(DigestOutputStream(sink, digest), 4096))
        private fun text(out: DataOutputStream, value: String?) {
            if (value == null) out.writeInt(-1)
            else {
                val bytes = value.toByteArray(Charsets.UTF_8)
                out.writeInt(bytes.size)
                out.write(bytes)
            }
        }
        private fun hex(bytes: ByteArray): String {
            val chars = "0123456789abcdef"
            val result = CharArray(bytes.size * 2)
            bytes.forEachIndexed { i, b ->
                val value = b.toInt() and 255
                result[i * 2] = chars[value ushr 4]
                result[i * 2 + 1] = chars[value and 15]
            }
            return String(result)
        }

        /**
         * Enumerates the complete existing native-content contract exactly once.
         * maxBytes bounds retained native encoding, separate UTF-16 String observations and
         * row hashes, not CursorWindow/JVM heap. The additional String never enters the old
         * native digest. The count-certificate scope adds a separate String observation root.
         * Both roots include the complete declared schema, but exclude certificate table DATA
         * to avoid self-reference. Those cells are still captured and charged in full.
         * Budget failure aborts the entire cold scope; fallback must start outside that scope.
         */
        suspend fun capture(
            db: HabitDatabase,
            gate: G0DomainSqlGate,
            expectedSchema: List<List<String?>>,
            maxBytes: Long = DEFAULT_MAX_BYTES,
            countCertificateScope: Boolean = false
        ): G0FrozenDomainInputs = try {
            gate.requireColdRead(db)
            require(maxBytes > 0)
            var used = 0L
            fun charge(bytes: Long) {
                if (bytes < 0 || bytes > maxBytes - used) throw BudgetExceeded()
                used += bytes
            }
            fun chargeText(value: String?) = charge(4L + (value?.toByteArray(Charsets.UTF_8)?.size ?: 0))
            val expected = expectedSchema.filter { it[0] == "main" }.map { immutable(it) }
            val sql = db.openHelper.writableDatabase
            val schema = sql.query(G0PersistentCheckpoint.SCHEMA_SQL).use { cursor ->
                val result = mutableListOf<List<String?>>()
                while (cursor.moveToNext()) {
                    currentCoroutineContext().ensureActive()
                    val row = listOf("main") + (0..3).map { if (cursor.isNull(it)) null else cursor.getString(it) }
                    charge(4)
                    row.forEach { chargeText(it) }
                    result += immutable(row)
                }
                immutable(result)
            }
            check(schema == expected) { "G0_CHECKPOINT_SCHEMA" }
            val protectedCatalog = G0PersistentCheckpoint.columns
            val catalog = if (countCertificateScope) {
                G0CountCertificateSql.requireSchema(schema)
                check(G0CountCertificateSql.tables.keys.none { it in protectedCatalog }) { "G0_COUNT_CERTIFICATE_SCOPE" }
                protectedCatalog + G0CountCertificateSql.tables
            } else protectedCatalog
            check(schema.filter { it[1] == "table" }.map { it[2] }.toSet() == catalog.keys) {
                "G0_CHECKPOINT_TABLES"
            }
            sql.query(G0PersistentCheckpoint.INTEGRITY_SQL).use {
                check(it.moveToFirst() && it.getString(0) == "ok" && !it.moveToNext()) { "G0_CHECKPOINT_INTEGRITY" }
            }
            val version = sql.query(G0PersistentCheckpoint.VERSION_SQL).use {
                check(it.moveToFirst())
                val value = it.getLong(0)
                check(!it.moveToNext())
                value
            }
            val digest = MessageDigest.getInstance("SHA-256")
            val rowDigest = MessageDigest.getInstance("SHA-256")
            val out = stream(digest)
            val rowOut = stream(rowDigest)
            val textDigest = if (countCertificateScope) MessageDigest.getInstance("SHA-256") else null
            val textRowDigest = if (countCertificateScope) MessageDigest.getInstance("SHA-256") else null
            val textOut = textDigest?.let(::stream)
            val textRowOut = textRowDigest?.let(::stream)
            chargeText("G0-full-native-content-v1")
            charge(4 + 8 + 4)
            text(out, "G0-full-native-content-v1")
            out.writeInt(schema.size)
            schema.forEach { row -> out.writeInt(row.size); row.forEach { text(out, it) } }
            out.writeLong(version)
            out.writeInt(protectedCatalog.size)
            textOut?.let { semantic ->
                chargeText("G0-count-certificate-text-input-v1")
                charge(4 + 8 + 4)
                text(semantic, "G0-count-certificate-text-input-v1")
                semantic.writeInt(schema.size)
                schema.forEach { row -> semantic.writeInt(row.size); row.forEach { text(semantic, it) } }
                semantic.writeLong(version)
                semantic.writeInt(protectedCatalog.size)
            }
            val tables = linkedMapOf<String, Table>()
            var scanned = 0L
            for ((table, fields) in catalog) {
                currentCoroutineContext().ensureActive()
                gate.requireColdRead(db)
                val isProtected = table in protectedCatalog
                chargeText(table)
                charge(4 + 4)
                fields.forEach { chargeText(it) }
                if (isProtected) {
                    text(out, table)
                    out.writeInt(fields.size)
                    fields.forEach { text(out, it) }
                    textOut?.let { semantic ->
                        text(semantic, table)
                        semantic.writeInt(fields.size)
                        fields.forEach { text(semantic, it) }
                    }
                }
                val rows = mutableListOf<Row>()
                val hashes = mutableListOf<String>()
                val textHashes = if (isProtected && countCertificateScope) mutableListOf<String>() else null
                val select = when {
                    countCertificateScope && table == G0CountCertificateSql.table -> G0CountCertificateSql.select
                    countCertificateScope && table == G0CountCertificateSql.chunkTable -> G0CountCertificateSql.chunkSelect
                    else -> G0PersistentCheckpoint.select(table)
                }
                sql.query(select).use { cursor ->
                    check(cursor.columnNames.toList() == fields) { "G0_CHECKPOINT_COLUMNS" }
                    while (cursor.moveToNext()) {
                        currentCoroutineContext().ensureActive()
                        charge(4L + 4L + if (isProtected) 64L else 0L)
                        if (textHashes != null) charge(4 + 64)
                        val cells = fields.indices.map { index ->
                            val type = cursor.getType(index)
                            charge(4)
                            when (type) {
                                Cursor.FIELD_TYPE_NULL -> Cell(type, null, null)
                                Cursor.FIELD_TYPE_INTEGER -> {
                                    charge(8)
                                    Cell(type, cursor.getLong(index), null)
                                }
                                Cursor.FIELD_TYPE_FLOAT -> {
                                    charge(8)
                                    Cell(type, java.lang.Double.doubleToRawLongBits(cursor.getDouble(index)), null)
                                }
                                Cursor.FIELD_TYPE_STRING -> {
                                    // These are distinct observations of the SAME cursor cell.
                                    // Raw bytes retain the old complete-content contract; String
                                    // retains the old index's semantics and is never decoded from bytes.
                                    val bytes = cursor.getBlob(index)
                                    charge(4L + bytes.size)
                                    val text = cursor.getString(index)
                                    charge(4L + text.length.toLong() * 2)
                                    Cell(type, null, bytes, text)
                                }
                                Cursor.FIELD_TYPE_BLOB -> {
                                    val bytes = cursor.getBlob(index)
                                    charge(4L + bytes.size)
                                    Cell(type, null, bytes)
                                }
                                else -> error("G0_CHECKPOINT_TYPE")
                            }
                        }
                        val row = Row(cells)
                        if (isProtected) {
                            rowOut.writeInt(cells.size)
                            cells.forEach { it.encode(rowOut) }
                            rowOut.flush()
                            hashes += hex(rowDigest.digest())
                            textHashes?.let { semanticHashes ->
                                val semanticRow = requireNotNull(textRowOut)
                                val caller = currentCoroutineContext()
                                semanticRow.writeInt(cells.size)
                                cells.forEach { it.encodeWithText(semanticRow) { caller.ensureActive() } }
                                semanticRow.flush()
                                semanticHashes += hex(requireNotNull(textRowDigest).digest())
                            }
                        }
                        rows += row
                        scanned++
                    }
                }
                if (isProtected) {
                    out.writeInt(hashes.size)
                    hashes.sort()
                    hashes.forEach { text(out, it) }
                    textHashes?.let { semanticHashes ->
                        val semantic = requireNotNull(textOut)
                        semantic.writeInt(semanticHashes.size)
                        semanticHashes.sort()
                        semanticHashes.forEach { text(semantic, it) }
                    }
                }
                tables[table] = Table(fields, rows)
            }
            out.flush()
            textOut?.flush()
            gate.requireColdRead(db)
            currentCoroutineContext().ensureActive()
            G0FrozenDomainInputs(schema, version, Collections.unmodifiableMap(tables), scanned, used,
                digest.digest(), countCertificateScope, textDigest?.digest())
        } catch (failure: Throwable) {
            gate.failColdRead(failure)
        }
    }
}
