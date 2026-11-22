package com.dayforge.data.history

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.time.DateTimeException
import java.time.LocalDate
import java.time.YearMonth
import java.util.Collections

/** Strict v1 node encoding with opaque payloads. A supplied Ref is a byte commitment, not an authenticated
 * account, current commit, business proof or publication/write permit. Record payloads are opaque.
 * Only a later protected root issuer may bind this representation to a verified business state.
 */
internal object HistoryBlockCodec {
    const val MAGIC = 0x47484231
    const val FORMAT = 1
    const val LEAF_TYPE = 1
    const val BRANCH_TYPE = 2
    const val MAX_NODE_BYTES = 256 * 1024
    const val MAX_RECORDS = 128
    const val MAX_CHILDREN = 16
    const val MAX_DEPTH = 8

    /** Maximum header is 13 fixed prefix bytes + 4 ASCII fields of at most 132 bytes,
     * 1 round tag + 5 year/month bytes + 2 bounds of at most 141 bytes + 16 metadata bytes
     * = 845 bytes. This conservative planner allowance is not measured actual node overhead. */
    const val LEAF_HEADER_UPPER_BOUND = 4096L

    enum class Code { BUDGET, MALFORMED, CONTEXT, HASH_MISMATCH }
    class Rejected(val code: Code) : IllegalArgumentException("G0_HISTORY_BLOCK_$code")

    /** Owns exact bytes. Neither constructor input nor a getter can mutate retained content. */
    class Bytes private constructor(private val owned: ByteArray) {
        val size: Int get() = owned.size
        fun copyOf(): Bytes = Bytes(owned.copyOf())
        fun toByteArray(): ByteArray = owned.copyOf()
        override fun equals(other: Any?): Boolean = other is Bytes && owned.contentEquals(other.owned)
        override fun hashCode(): Int = owned.contentHashCode()
        internal fun writeTo(output: DataOutputStream) = output.write(owned.copyOf())
        internal fun digest(): ByteArray = MessageDigest.getInstance("SHA-256").digest(owned)

        companion object {
            fun copyOf(value: ByteArray): Bytes {
                demand(value.size <= MAX_NODE_BYTES, Code.BUDGET)
                return Bytes(value.copyOf())
            }
        }
    }

    data class Record(
        val key: HistoryBlockPlanner.OrderKey,
        val sourceId: String,
        val payload: Bytes
    )

    sealed interface Node {
        val range: HistoryBlockPlanner.LeafRange
        val height: Int
        val count: Long
    }

    class Leaf(
        override val range: HistoryBlockPlanner.LeafRange,
        records: List<Record>
    ) : Node {
        val records: List<Record> = snapshot(records, MAX_RECORDS)
        override val height: Int = 0
        override val count: Long = this.records.size.toLong()
    }

    class Branch(
        override val range: HistoryBlockPlanner.LeafRange,
        override val height: Int,
        children: List<Ref>
    ) : Node {
        val children: List<Ref> = snapshot(children, MAX_CHILDREN)
        override val count: Long = sum(this.children)
    }

    data class Ref(
        val range: HistoryBlockPlanner.LeafRange,
        val height: Int,
        val count: Long,
        val digest: String
    )
    data class Encoded(val ref: Ref, val bytes: Bytes)

    /** Includes the complete key, source and payload framing; no caller-declared size is trusted. */
    fun recordEncodedSize(record: Record): Long {
        identity(record.key.stableFactId)
        identity(record.sourceId)
        return 20L + record.key.stableFactId.length + record.sourceId.length + record.payload.size
    }

    fun hash(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
    fun hash(bytes: Bytes): String = hex(bytes.digest())

    fun encode(node: Node): Encoded {
        validate(node)
        val writer = Writer()
        writer.int(MAGIC)
        writer.int(FORMAT)
        writer.int(HistoryBlockPlanner.KEY_ENCODING_VERSION)
        writer.byte(if (node is Leaf) LEAF_TYPE else BRANCH_TYPE)
        writer.unit(node.range.unit)
        writer.bound(node.range.lowerInclusive)
        writer.bound(node.range.upperExclusive)
        writer.int(node.height)
        writer.long(node.count)
        when (node) {
            is Leaf -> {
                writer.int(node.records.size)
                demand(writer.size.toLong() <= LEAF_HEADER_UPPER_BOUND)
                for (record in node.records) {
                    writer.key(record.key)
                    writer.ascii(record.sourceId)
                    writer.int(record.payload.size)
                    writer.bytes(record.payload)
                }
            }
            is Branch -> {
                writer.int(node.children.size)
                for (child in node.children) {
                    writer.bound(child.range.lowerInclusive)
                    writer.bound(child.range.upperExclusive)
                    writer.int(child.height)
                    writer.long(child.count)
                    writer.digest(child.digest)
                }
            }
        }
        val bytes = writer.finish()
        return Encoded(Ref(node.range, node.height, node.count, hash(bytes)), bytes)
    }

    /** Limit and freeze before hashing; parse only that same owned image. Unvisited nodes are
     * not checked. Cancellation and unexpected errors propagate; only explicit malformed/budget
     * outcomes use Rejected, and no partially decoded Node is returned. */
    fun decode(expected: Ref, raw: ByteArray, checkCancelled: () -> Unit = {}): Node {
        checkCancelled()
        validateRef(expected)
        demand(raw.size <= MAX_NODE_BYTES, Code.BUDGET)
        val owned = raw.copyOf()
        checkCancelled()
        demand(hash(owned) == expected.digest, Code.HASH_MISMATCH)
        checkCancelled()
        val reader = Reader(owned, checkCancelled)
        demand(reader.int() == MAGIC)
        demand(reader.int() == FORMAT)
        demand(reader.int() == HistoryBlockPlanner.KEY_ENCODING_VERSION)
        val type = reader.byte()
        demand(type == LEAF_TYPE || type == BRANCH_TYPE)
        val unit = reader.unit()
        val range = HistoryBlockPlanner.LeafRange(unit, reader.bound(), reader.bound())
        val height = reader.int()
        val declaredCount = reader.long()
        val count = reader.int()
        val node: Node = when (type) {
            LEAF_TYPE -> {
                demand(height == 0)
                reader.group(count, MAX_RECORDS, MIN_RECORD_BYTES)
                val records = ArrayList<Record>(count)
                repeat(count) {
                    checkCancelled()
                    records += Record(reader.key(), reader.ascii(), reader.payload())
                }
                Leaf(range, records)
            }
            BRANCH_TYPE -> {
                demand(height in 1..MAX_DEPTH)
                reader.group(count, MAX_CHILDREN, MIN_CHILD_BYTES)
                demand(count > 0)
                val children = ArrayList<Ref>(count)
                repeat(count) {
                    checkCancelled()
                    val childRange = HistoryBlockPlanner.LeafRange(unit, reader.bound(), reader.bound())
                    children += Ref(childRange, reader.int(), reader.long(), reader.digest())
                }
                Branch(range, height, children)
            }
            else -> throw Rejected(Code.MALFORMED)
        }
        reader.end()
        validate(node)
        demand(declaredCount == node.count)
        demand(node.range == expected.range && node.height == expected.height && node.count == expected.count,
            Code.CONTEXT)
        checkCancelled()
        return node
    }

    private fun validate(node: Node) {
        validRange(node.range)
        when (node) {
            is Leaf -> {
                var previous: HistoryBlockPlanner.OrderKey? = null
                val identities = HashSet<String>()
                var recordBytes = 0L
                for (record in node.records) {
                    validKey(record.key, node.range.unit.month)
                    demand(contains(node.range, record.key))
                    demand(previous == null || previous < record.key)
                    demand(identities.add(record.key.stableFactId))
                    val size = recordEncodedSize(record)
                    demand(size <= MAX_NODE_BYTES - recordBytes, Code.BUDGET)
                    recordBytes += size
                    previous = record.key
                }
            }
            is Branch -> {
                demand(node.height in 1..MAX_DEPTH && node.children.isNotEmpty())
                var next = node.range.lowerInclusive
                for ((index, child) in node.children.withIndex()) {
                    validateRef(child)
                    demand(child.range.unit == node.range.unit)
                    demand(child.height == node.height - 1)
                    demand(child.range.lowerInclusive == next)
                    demand(child.range.upperExclusive != null || index == node.children.lastIndex)
                    next = child.range.upperExclusive
                }
                demand(next == node.range.upperExclusive)
            }
        }
        demand(node.count in 0..maximumCount(node.height))
    }

    private fun validateRef(ref: Ref) {
        validRange(ref.range)
        demand(ref.height in 0..MAX_DEPTH)
        demand(ref.count in 0..maximumCount(ref.height))
        demand(ref.digest.length == 64 && ref.digest.all { it in HEX })
    }

    private fun validRange(range: HistoryBlockPlanner.LeafRange) {
        val partition = range.unit.partition
        identity(partition.scope.localBindingId)
        identity(partition.domain)
        identity(partition.objectId)
        partition.roundId?.let(::identity)
        range.lowerInclusive?.let { validKey(it, range.unit.month) }
        range.upperExclusive?.let { validKey(it, range.unit.month) }
        demand(range.lowerInclusive == null || range.upperExclusive == null || range.lowerInclusive < range.upperExclusive)
    }

    private fun validKey(key: HistoryBlockPlanner.OrderKey, month: YearMonth) {
        identity(key.stableFactId)
        demand(YearMonth.from(key.recordedBusinessDate) == month)
    }

    private fun contains(range: HistoryBlockPlanner.LeafRange, key: HistoryBlockPlanner.OrderKey): Boolean =
        (range.lowerInclusive == null || key >= range.lowerInclusive) &&
            (range.upperExclusive == null || key < range.upperExclusive)

    private fun identity(value: String) = demand(
        value.length in 1..HistoryBlockPlanner.MAX_ID_BYTES && value.all { it.code in 0x21..0x7e })

    private fun maximumCount(height: Int): Long {
        demand(height in 0..MAX_DEPTH)
        var count = MAX_RECORDS.toLong()
        repeat(height) { count *= MAX_CHILDREN }
        return count
    }

    private fun sum(children: List<Ref>): Long {
        var result = 0L
        for (child in children) {
            demand(child.count >= 0L && child.count <= Long.MAX_VALUE - result)
            result += child.count
        }
        return result
    }

    /** The captured size caps work before indexing. A broken/shortened caller collection may
     * fail the call; subsequent validation and serialization never reread that borrowed list. */
    private fun <T> snapshot(input: List<T>, maximum: Int): List<T> {
        val size = input.size
        demand(size in 0..maximum, Code.BUDGET)
        val owned = ArrayList<T>(size)
        for (index in 0 until size) owned += input[index]
        return Collections.unmodifiableList(owned)
    }

    private class Writer {
        private val buffer = ByteArrayOutputStream(4096)
        private val output = DataOutputStream(buffer)
        val size: Int get() = buffer.size()
        private fun reserve(amount: Int) = demand(amount >= 0 && amount <= MAX_NODE_BYTES - size, Code.BUDGET)
        fun byte(value: Int) { reserve(1); output.writeByte(value) }
        fun int(value: Int) { reserve(4); output.writeInt(value) }
        fun long(value: Long) { reserve(8); output.writeLong(value) }
        fun ascii(value: String) {
            identity(value)
            int(value.length)
            reserve(value.length)
            for (char in value) output.writeByte(char.code)
        }
        fun unit(unit: HistoryBlockPlanner.UnitKey) {
            val partition = unit.partition
            ascii(partition.scope.localBindingId)
            ascii(partition.domain)
            ascii(partition.objectId)
            val round = partition.roundId
            byte(if (round == null) 0 else 1)
            if (round != null) ascii(round)
            int(unit.month.year)
            byte(unit.month.monthValue)
        }
        fun key(key: HistoryBlockPlanner.OrderKey) {
            long(key.recordedBusinessDate.toEpochDay())
            ascii(key.stableFactId)
        }
        fun bound(value: HistoryBlockPlanner.OrderKey?) {
            byte(if (value == null) 0 else 1)
            if (value != null) key(value)
        }
        fun bytes(value: Bytes) { reserve(value.size); value.writeTo(output) }
        fun digest(value: String) {
            demand(value.length == 64 && value.all { it in HEX })
            reserve(32)
            for (index in value.indices step 2) {
                output.writeByte((HEX.indexOf(value[index]) shl 4) or HEX.indexOf(value[index + 1]))
            }
        }
        fun finish(): Bytes = Bytes.copyOf(buffer.toByteArray())
    }

    private class Reader(private val bytes: ByteArray, private val checkCancelled: () -> Unit) {
        private var offset = 0
        private fun need(amount: Int) {
            checkCancelled()
            demand(amount >= 0 && amount <= bytes.size - offset)
        }
        fun byte(): Int { need(1); return bytes[offset++].toInt() and 255 }
        fun int(): Int {
            need(4)
            return (byte() shl 24) or (byte() shl 16) or (byte() shl 8) or byte()
        }
        fun long(): Long {
            need(8)
            var result = 0L
            repeat(8) { result = (result shl 8) or byte().toLong() }
            return result
        }
        fun ascii(): String {
            val length = int()
            demand(length in 1..HistoryBlockPlanner.MAX_ID_BYTES)
            need(length)
            val chars = CharArray(length)
            for (index in chars.indices) {
                val value = byte()
                demand(value in 0x21..0x7e)
                chars[index] = value.toChar()
            }
            return chars.concatToString()
        }
        fun unit(): HistoryBlockPlanner.UnitKey {
            val scope = HistoryBlockPlanner.Scope(ascii())
            val domain = ascii()
            val objectId = ascii()
            val round = when (byte()) {
                0 -> null
                1 -> ascii()
                else -> throw Rejected(Code.MALFORMED)
            }
            val year = int()
            val month = byte()
            val value = try { YearMonth.of(year, month) } catch (_: DateTimeException) {
                throw Rejected(Code.MALFORMED)
            }
            return HistoryBlockPlanner.UnitKey(HistoryBlockPlanner.Partition(scope, domain, objectId, round), value)
        }
        fun key(): HistoryBlockPlanner.OrderKey {
            val date = try { LocalDate.ofEpochDay(long()) } catch (_: DateTimeException) {
                throw Rejected(Code.MALFORMED)
            }
            return HistoryBlockPlanner.OrderKey(date, ascii())
        }
        fun bound(): HistoryBlockPlanner.OrderKey? = when (byte()) {
            0 -> null
            1 -> key()
            else -> throw Rejected(Code.MALFORMED)
        }
        fun payload(): Bytes {
            val length = int()
            demand(length >= 0)
            demand(length <= MAX_NODE_BYTES, Code.BUDGET)
            need(length)
            return Bytes.copyOf(bytes.copyOfRange(offset, offset + length)).also { offset += length }
        }
        fun digest(): String {
            need(32)
            return hex(bytes.copyOfRange(offset, offset + 32)).also { offset += 32 }
        }
        fun group(count: Int, maximum: Int, minimumMemberBytes: Int) {
            demand(count >= 0)
            demand(count <= maximum, Code.BUDGET)
            demand(count <= (bytes.size - offset) / minimumMemberBytes)
        }
        fun end() = demand(offset == bytes.size)
    }

    private fun hex(bytes: ByteArray): String = buildString(bytes.size * 2) {
        for (byte in bytes) {
            val value = byte.toInt() and 255
            append(HEX[value ushr 4])
            append(HEX[value and 15])
        }
    }
    private fun demand(condition: Boolean, code: Code = Code.MALFORMED) {
        if (!condition) throw Rejected(code)
    }
    private const val MIN_RECORD_BYTES = 22
    private const val MIN_CHILD_BYTES = 46
    private const val HEX = "0123456789abcdef"
}
