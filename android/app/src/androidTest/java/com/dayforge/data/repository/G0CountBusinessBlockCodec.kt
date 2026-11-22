package com.dayforge.data.repository

import com.dayforge.data.history.HistoryBlockCodec

import com.dayforge.data.local.entity.CompletionEntity
import com.dayforge.data.local.entity.CountDayEntity
import com.dayforge.data.local.entity.NextAcceptanceEntity
import com.dayforge.data.local.entity.NextRequestOriginEntity
import com.dayforge.data.local.entity.NextTransmissionEntity
import com.dayforge.data.local.entity.SyncEntityStateEntity
import com.dayforge.data.local.entity.SyncOutboxEntity
import com.dayforge.data.history.HistoryBlockCodec.Bytes
import com.dayforge.data.history.HistoryBlockCodec.Code
import com.dayforge.data.history.HistoryBlockCodec.Rejected
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/** Complete typed COUNT representation only. Encoding/decoding does not validate business facts,
 * issue a root, authenticate an account, or grant read/write/publication authority. An unavailable
 * representation is a candidate miss, never a new product limit or reason to reject valid facts.
 * Strings preserve exact JVM UTF-16 code units, including NUL and lone surrogate code units.
 */
internal object G0CountBusinessBlockCodec {
    const val FORMAT_VERSION = 1
    // One payload must still fit a leaf with its header and two maximum-length record keys.
    const val MAX_PAYLOAD_BYTES = HistoryBlockCodec.MAX_NODE_BYTES - 4096 - 512
    private val MAGIC = byteArrayOf(0x44, 0x46, 0x47, 0x30, 0x43, 0x42, 0x4c, 0x4b) // DFG0CBLK

    /** Owns the only mutable entity descendant before it can escape this constructor. A caller
     * receives a fresh transmission byte buffer on every getter; other entities contain only
     * immutable scalar/string fields. This value remains data, not verification evidence.
     */
    class Source(
        val origin: NextRequestOriginEntity,
        val originHash: String,
        transmission: NextTransmissionEntity? = null,
        val transmissionHash: String? = null,
        val receipt: NextAcceptanceEntity? = null,
        val pending: SyncOutboxEntity? = null,
        val shadow: SyncEntityStateEntity? = null
    ) {
        private val ownedTransmission: NextTransmissionEntity? = transmission?.let {
            demand(it.wireBytes.size <= MAX_PAYLOAD_BYTES, Code.BUDGET)
            it.copy(wireBytes = it.wireBytes.copyOf())
        }
        val transmission: NextTransmissionEntity?
            get() = ownedTransmission?.let { it.copy(wireBytes = it.wireBytes.copyOf()) }
    }

    fun fact(value: CompletionEntity): Bytes = encode { writeFact(it, value) }
    fun fact(value: Bytes): CompletionEntity = decode(value, ::readFact)
    fun day(value: CountDayEntity): Bytes = encode { writeDay(it, value) }
    fun day(value: Bytes): CountDayEntity = decode(value, ::readDay)
    fun source(value: Source): Bytes = encode { writeSource(it, value) }
    fun source(value: Bytes): Source = decode(value, ::readSource)

    private fun encode(body: (Writer) -> Unit): Bytes {
        val writer = Writer()
        writer.raw(MAGIC)
        writer.rawInt(FORMAT_VERSION)
        body(writer)
        return Bytes.copyOf(writer.finish())
    }

    private fun <T> decode(bytes: Bytes, body: (Reader) -> T): T {
        demand(bytes.size <= MAX_PAYLOAD_BYTES, Code.BUDGET)
        val reader = Reader(bytes.toByteArray())
        demand(reader.raw(MAGIC.size).contentEquals(MAGIC))
        demand(reader.rawInt() == FORMAT_VERSION)
        val result = body(reader)
        reader.end()
        return result
    }

    private fun writeFact(w: Writer, f: CompletionEntity) {
        w.record(FACT, 15)
        w.long(f.id); w.long(f.habitId); w.long(f.date); w.int(f.value); w.longOrNull(f.actualCompletedAt)
        w.string(f.uuid); w.stringOrNull(f.habitUuid); w.long(f.createdAt); w.string(f.recordedTimezone)
        w.string(f.recordedLocalDate); w.string(f.timeMetadataSource); w.stringOrNull(f.oneTimeAction)
        w.intOrNull(f.oneTimeExpectedVersion); w.stringOrNull(f.oneTimeExpectedHeadEventUuid)
        w.stringOrNull(f.oneTimeRevertsEventUuid)
    }

    private fun readFact(r: Reader): CompletionEntity {
        r.record(FACT, 15)
        return CompletionEntity(id = r.long(), habitId = r.long(), date = r.long(), value = r.int(),
            actualCompletedAt = r.longOrNull(), uuid = r.string(), habitUuid = r.stringOrNull(),
            createdAt = r.long(), recordedTimezone = r.string(), recordedLocalDate = r.string(),
            timeMetadataSource = r.string(), oneTimeAction = r.stringOrNull(),
            oneTimeExpectedVersion = r.intOrNull(), oneTimeExpectedHeadEventUuid = r.stringOrNull(),
            oneTimeRevertsEventUuid = r.stringOrNull())
    }

    private fun writeDay(w: Writer, d: CountDayEntity) {
        w.record(DAY, 14)
        w.long(d.habitId); w.string(d.habitUuid); w.string(d.localDate); w.int(d.targetValue)
        w.bool(d.isCountdown); w.string(d.firstEventUuid); w.stringOrNull(d.originRequestId)
        w.stringOrNull(d.originHash); w.stringOrNull(d.planPredecessorId)
        w.stringOrNull(d.planPredecessorOriginHash); w.stringOrNull(d.planPredecessorDependencyHash)
        w.longOrNull(d.planQueueWatermark); w.stringOrNull(d.capturedDeviceId); w.string(d.originKind)
    }

    private fun readDay(r: Reader): CountDayEntity {
        r.record(DAY, 14)
        return CountDayEntity(habitId = r.long(), habitUuid = r.string(), localDate = r.string(),
            targetValue = r.int(), isCountdown = r.bool(), firstEventUuid = r.string(),
            originRequestId = r.stringOrNull(), originHash = r.stringOrNull(),
            planPredecessorId = r.stringOrNull(), planPredecessorOriginHash = r.stringOrNull(),
            planPredecessorDependencyHash = r.stringOrNull(), planQueueWatermark = r.longOrNull(),
            capturedDeviceId = r.stringOrNull(), originKind = r.string())
    }

    private fun writeSource(w: Writer, s: Source) {
        w.record(SOURCE, 7)
        writeOrigin(w, s.origin); w.string(s.originHash)
        val transmission = s.transmission
        if (transmission == null) w.nil() else writeTransmission(w, transmission)
        w.stringOrNull(s.transmissionHash)
        if (s.receipt == null) w.nil() else writeReceipt(w, s.receipt)
        if (s.pending == null) w.nil() else writeQueue(w, s.pending)
        if (s.shadow == null) w.nil() else writeShadow(w, s.shadow)
    }

    private fun readSource(r: Reader): Source {
        r.record(SOURCE, 7)
        return Source(origin = readOrigin(r), originHash = r.string(),
            transmission = if (r.nil()) null else readTransmission(r),
            transmissionHash = r.stringOrNull(), receipt = if (r.nil()) null else readReceipt(r),
            pending = if (r.nil()) null else readQueue(r), shadow = if (r.nil()) null else readShadow(r))
    }

    private fun writeOrigin(w: Writer, o: NextRequestOriginEntity) {
        w.record(ORIGIN, 9)
        w.string(o.kind); w.string(o.requestId); w.long(o.queueId); w.int(o.protocol)
        w.string(o.accountId); w.stringOrNull(o.serverInstanceId); w.stringOrNull(o.syncEpoch)
        w.string(o.sourceHash); w.string(o.intentJson)
    }

    private fun readOrigin(r: Reader): NextRequestOriginEntity {
        r.record(ORIGIN, 9)
        return NextRequestOriginEntity(kind = r.string(), requestId = r.string(), queueId = r.long(),
            protocol = r.int(), accountId = r.string(), serverInstanceId = r.stringOrNull(),
            syncEpoch = r.stringOrNull(), sourceHash = r.string(), intentJson = r.string())
    }

    private fun writeTransmission(w: Writer, t: NextTransmissionEntity) {
        w.record(TRANSMISSION, 10)
        w.string(t.kind); w.string(t.requestId); w.long(t.queueId); w.int(t.protocol)
        w.string(t.accountId); w.string(t.serverInstanceId); w.string(t.syncEpoch); w.string(t.deviceId)
        w.string(t.wireHash); w.bytes(t.wireBytes)
    }

    private fun readTransmission(r: Reader): NextTransmissionEntity {
        r.record(TRANSMISSION, 10)
        return NextTransmissionEntity(kind = r.string(), requestId = r.string(), queueId = r.long(),
            protocol = r.int(), accountId = r.string(), serverInstanceId = r.string(),
            syncEpoch = r.string(), deviceId = r.string(), wireHash = r.string(), wireBytes = r.bytes())
    }

    private fun writeReceipt(w: Writer, a: NextAcceptanceEntity) {
        w.record(RECEIPT, 6)
        w.string(a.kind); w.string(a.requestId); w.string(a.originHash); w.string(a.transmissionHash)
        w.string(a.resultHash); w.string(a.resultJson)
    }

    private fun readReceipt(r: Reader): NextAcceptanceEntity {
        r.record(RECEIPT, 6)
        return NextAcceptanceEntity(kind = r.string(), requestId = r.string(), originHash = r.string(),
            transmissionHash = r.string(), resultHash = r.string(), resultJson = r.string())
    }

    private fun writeQueue(w: Writer, q: SyncOutboxEntity) {
        w.record(QUEUE, 16)
        w.long(q.id); w.string(q.operationId); w.string(q.recordType); w.string(q.entityUuid)
        w.string(q.wireEntityUuid); w.string(q.action); w.stringOrNull(q.referenceUuid)
        w.stringOrNull(q.payloadJson); w.longOrNull(q.baseRevision); w.stringOrNull(q.basePayloadJson)
        w.longOrNull(q.attemptedAt); w.int(q.attemptCount); w.stringOrNull(q.lastError)
        w.stringOrNull(q.errorCode); w.longOrNull(q.deadLetteredAt); w.long(q.createdAt)
    }

    private fun readQueue(r: Reader): SyncOutboxEntity {
        r.record(QUEUE, 16)
        return SyncOutboxEntity(id = r.long(), operationId = r.string(), recordType = r.string(),
            entityUuid = r.string(), wireEntityUuid = r.string(), action = r.string(),
            referenceUuid = r.stringOrNull(), payloadJson = r.stringOrNull(), baseRevision = r.longOrNull(),
            basePayloadJson = r.stringOrNull(), attemptedAt = r.longOrNull(), attemptCount = r.int(),
            lastError = r.stringOrNull(), errorCode = r.stringOrNull(), deadLetteredAt = r.longOrNull(),
            createdAt = r.long())
    }

    private fun writeShadow(w: Writer, s: SyncEntityStateEntity) {
        w.record(SHADOW, 7)
        w.string(s.entityType); w.string(s.entityUuid); w.long(s.revision); w.bool(s.deleted)
        w.stringOrNull(s.payloadJson); w.stringOrNull(s.payloadHash); w.long(s.updatedAt)
    }

    private fun readShadow(r: Reader): SyncEntityStateEntity {
        r.record(SHADOW, 7)
        return SyncEntityStateEntity(entityType = r.string(), entityUuid = r.string(), revision = r.long(),
            deleted = r.bool(), payloadJson = r.stringOrNull(), payloadHash = r.stringOrNull(), updatedAt = r.long())
    }

    private const val FACT = 100
    private const val DAY = 101
    private const val SOURCE = 102
    private const val ORIGIN = 103
    private const val TRANSMISSION = 104
    private const val RECEIPT = 105
    private const val QUEUE = 106
    private const val SHADOW = 107
    private const val NULL = 0
    private const val INT = 1
    private const val LONG = 2
    private const val BOOL = 3
    private const val STRING = 4
    private const val BYTES = 5
    private const val RECORD = 6

    private fun demand(condition: Boolean, code: Code = Code.MALFORMED) {
        if (!condition) throw Rejected(code)
    }

    private class Writer {
        private val output = ByteArrayOutputStream()
        private val data = DataOutputStream(output)
        private fun reserve(size: Int) {
            demand(size >= 0 && size <= MAX_PAYLOAD_BYTES - output.size(), Code.BUDGET)
        }
        fun raw(value: ByteArray) { reserve(value.size); data.write(value) }
        fun rawInt(value: Int) { reserve(4); data.writeInt(value) }
        fun nil() { reserve(1); data.writeByte(NULL) }
        fun int(value: Int) { reserve(5); data.writeByte(INT); data.writeInt(value) }
        fun long(value: Long) { reserve(9); data.writeByte(LONG); data.writeLong(value) }
        fun bool(value: Boolean) { reserve(2); data.writeByte(BOOL); data.writeByte(if (value) 1 else 0) }
        fun intOrNull(value: Int?) { if (value == null) nil() else int(value) }
        fun longOrNull(value: Long?) { if (value == null) nil() else long(value) }
        fun stringOrNull(value: String?) { if (value == null) nil() else string(value) }
        fun string(value: String) {
            // Check before length multiplication or allocation. Length means UTF-16 code units.
            demand(value.length <= (MAX_PAYLOAD_BYTES - 5) / 2, Code.BUDGET)
            reserve(5 + value.length * 2)
            data.writeByte(STRING); data.writeInt(value.length)
            for (char in value) data.writeChar(char.code)
        }
        fun bytes(value: ByteArray) {
            demand(value.size <= MAX_PAYLOAD_BYTES - 5, Code.BUDGET)
            reserve(5 + value.size); data.writeByte(BYTES); data.writeInt(value.size); data.write(value)
        }
        fun record(id: Int, fields: Int) {
            reserve(9); data.writeByte(RECORD); data.writeInt(id); data.writeInt(fields)
        }
        fun finish(): ByteArray = output.toByteArray()
    }

    private class Reader(private val owned: ByteArray) {
        private var offset = 0
        private fun need(size: Int) { demand(size >= 0 && size <= owned.size - offset) }
        private fun byte(): Int { need(1); return owned[offset++].toInt() and 255 }
        private fun tag(expected: Int) { demand(byte() == expected) }
        fun raw(size: Int): ByteArray {
            need(size)
            return owned.copyOfRange(offset, offset + size).also { offset += size }
        }
        fun rawInt(): Int {
            need(4)
            return (byte() shl 24) or (byte() shl 16) or (byte() shl 8) or byte()
        }
        private fun rawLong(): Long {
            need(8)
            var value = 0L
            repeat(8) { value = (value shl 8) or byte().toLong() }
            return value
        }
        fun nil(): Boolean {
            need(1)
            if (owned[offset].toInt() != NULL) return false
            offset++
            return true
        }
        fun int(): Int { tag(INT); return rawInt() }
        fun long(): Long { tag(LONG); return rawLong() }
        fun bool(): Boolean { tag(BOOL); return when (byte()) { 0 -> false; 1 -> true; else -> throw Rejected(Code.MALFORMED) } }
        fun intOrNull(): Int? = if (nil()) null else int()
        fun longOrNull(): Long? = if (nil()) null else long()
        fun stringOrNull(): String? = if (nil()) null else string()
        fun string(): String {
            tag(STRING)
            val length = rawInt()
            demand(length >= 0)
            demand(length <= (MAX_PAYLOAD_BYTES - 5) / 2, Code.BUDGET)
            need(length * 2)
            val chars = CharArray(length)
            for (index in chars.indices) chars[index] = ((byte() shl 8) or byte()).toChar()
            return String(chars)
        }
        fun bytes(): ByteArray {
            tag(BYTES)
            val length = rawInt()
            demand(length >= 0)
            demand(length <= MAX_PAYLOAD_BYTES - 5, Code.BUDGET)
            return raw(length)
        }
        fun record(id: Int, fields: Int) {
            tag(RECORD); demand(rawInt() == id); demand(rawInt() == fields)
        }
        fun end() { demand(offset == owned.size) }
    }
}
