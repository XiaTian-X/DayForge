package com.dayforge.data.repository

import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.model.FailMode
import com.dayforge.data.model.HabitSchedule
import com.dayforge.data.model.HabitType
import com.dayforge.data.model.PlanStructureMetadata
import com.dayforge.domain.model.IconReference
import com.dayforge.domain.model.ObjectAppearance
import com.dayforge.data.repository.G0CountIncrementalBlocks.Current
import com.dayforge.data.repository.G0CountIncrementalBlocks.Pending
import com.dayforge.data.history.HistoryBlockCodec.Bytes
import com.dayforge.data.history.HistoryBlockCodec.Code
import com.dayforge.data.history.HistoryBlockCodec.Rejected
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/** B3-only bounded current state. Existing B2 payload bytes/meaning are unchanged.
 * Complete scalar Habit representation plus owned weekly containers; no historical facts,
 * wire bytes, current account permission or native row authority can be manufactured here.
 */
internal object G0CountCurrentBlockCodec {
    const val PROFILE = "plain-count-current-v1"
    private const val FORMAT=1
    private const val MAX_PAYLOAD_BYTES=G0CountBusinessBlockCodec.MAX_PAYLOAD_BYTES
    private val MAGIC=byteArrayOf(0x44,0x46,0x47,0x30,0x43,0x43,0x55,0x52)
    private const val CURRENT=200
    private const val HABIT=201
    private const val SCHEDULE=202
    private const val APPEARANCE=203
    private const val PLAN_METADATA=204
    private const val PENDING=205

    fun encode(current:Current):Bytes {
        val w=Writer();w.raw(MAGIC);w.rawInt(FORMAT);w.record(CURRENT,5)
        w.string(PROFILE);w.int(current.maxFacts);w.int(current.maxPending)
        w.int(current.habits.size)
        current.habits.toSortedMap().forEach {(uuid,habit)->w.string(uuid);writeHabit(w,habit)}
        w.int(current.pending.size)
        current.pending.forEach {item->
            w.record(PENDING,4);w.string(item.requestId);w.string(item.factUuid);w.long(item.queueId);w.bool(item.inflight)
        }
        return Bytes.copyOf(w.finish())
    }
    fun decode(bytes:Bytes):Current = try {
        demand(bytes.size<=MAX_PAYLOAD_BYTES,Code.BUDGET)
        val r=Reader(bytes.toByteArray());demand(r.raw(MAGIC.size).contentEquals(MAGIC));demand(r.rawInt()==FORMAT)
        r.record(CURRENT,5);demand(r.string()==PROFILE)
        val maxFacts=r.int();val maxPending=r.int();demand(maxFacts in 1..128 || maxFacts==736)
        demand(maxPending in 1..128 && (maxFacts!=736 || maxPending==6))
        demand(r.int()==2)
        var previous:String?=null
        val habits=linkedMapOf<String,HabitEntity>()
        repeat(2) {
            val key=r.string();demand(previous==null || requireNotNull(previous)<key)
            val habit=readHabit(r);demand(habit.uuid==key);habits[key]=habit;previous=key
        }
        val count=r.int();demand(count in 0..maxPending)
        val pending=ArrayList<Pending>(count)
        repeat(count) {r.record(PENDING,4);pending+=Pending(r.string(),r.string(),r.long(),r.bool())}
        r.end();Current(habits,pending,maxFacts,maxPending)
    } catch(failure:Rejected) {throw failure} catch(_:IllegalArgumentException) {throw Rejected(Code.MALFORMED)}

    /** Exposed as data-only codec to test every Habit field and immutable capture directly. */
    fun habit(value:HabitEntity):Bytes {
        val w=Writer();w.raw(MAGIC);w.rawInt(FORMAT);writeHabit(w,value);return Bytes.copyOf(w.finish())
    }
    fun habit(value:Bytes):HabitEntity=try {
        demand(value.size<=MAX_PAYLOAD_BYTES,Code.BUDGET)
        val r=Reader(value.toByteArray());demand(r.raw(MAGIC.size).contentEquals(MAGIC));demand(r.rawInt()==FORMAT)
        readHabit(r).also {r.end()}
    } catch(failure:Rejected) {throw failure} catch(_:IllegalArgumentException) {throw Rejected(Code.MALFORMED)}
    private fun writeHabit(w:Writer,h:HabitEntity) {
        w.record(HABIT,26)
        w.long(h.id);w.string(h.name);w.string(h.description)
        w.int(when(h.habitType) {HabitType.CHECK_IN->1;HabitType.COUNTING->2;HabitType.TIMER->3;HabitType.GOAL->4})
        w.int(h.iconResId);w.string(h.colorHex);writeSchedule(w,h.schedule)
        w.int(h.targetValue);w.bool(h.isCountdown);w.bool(h.isActive);w.string(h.uuid);w.stringOrNull(h.parentHabitId)
        w.intOrNull(h.targetCycles);w.int(when(h.failMode) {FailMode.STRICT->1;FailMode.LOOSE->2})
        w.boolOrNull(h.goalSuccess);w.int(h.activityRate);w.long(h.activityRateUpdatedAt);w.longOrNull(h.bestTime)
        w.long(h.createdAt);w.long(h.updatedAt);w.stringOrNull(h.completionPolicy);w.intOrNull(h.oneTimeConfirmedVersion)
        w.stringOrNull(h.oneTimeConfirmedHeadEventUuid);w.stringOrNull(h.oneTimeConfirmedCompletionEventUuid)
        writeAppearance(w,h.appearance);writeMetadata(w,h.planMetadata)
    }
    private fun readHabit(r:Reader):HabitEntity {
        r.record(HABIT,26)
        return HabitEntity(id=r.long(),name=r.string(),description=r.string(),habitType=when(r.int()) {
            1->HabitType.CHECK_IN;2->HabitType.COUNTING;3->HabitType.TIMER;4->HabitType.GOAL;else->throw Rejected(Code.MALFORMED)
        },iconResId=r.int(),colorHex=r.string(),schedule=readSchedule(r),targetValue=r.int(),isCountdown=r.bool(),
            isActive=r.bool(),uuid=r.string(),parentHabitId=r.stringOrNull(),targetCycles=r.intOrNull(),
            failMode=when(r.int()) {1->FailMode.STRICT;2->FailMode.LOOSE;else->throw Rejected(Code.MALFORMED)},
            goalSuccess=r.boolOrNull(),activityRate=r.int(),activityRateUpdatedAt=r.long(),bestTime=r.longOrNull(),
            createdAt=r.long(),updatedAt=r.long(),completionPolicy=r.stringOrNull(),oneTimeConfirmedVersion=r.intOrNull(),
            oneTimeConfirmedHeadEventUuid=r.stringOrNull(),oneTimeConfirmedCompletionEventUuid=r.stringOrNull(),
            appearance=readAppearance(r),planMetadata=readMetadata(r))
    }
    private fun writeSchedule(w:Writer,s:HabitSchedule) {
        w.record(SCHEDULE,2)
        when(s) {
            is HabitSchedule.Once->{w.int(1);w.stringOrNull(s.dueDate)}
            HabitSchedule.Daily->{w.int(2);w.nil()}
            is HabitSchedule.Weekly->{w.int(3);demand(s.daysOfWeek.size<=7,Code.BUDGET);w.int(s.daysOfWeek.size);s.daysOfWeek.forEach(w::int)}
            is HabitSchedule.Monthly->{w.int(4);w.int(s.dayOfMonth)}
            is HabitSchedule.Custom->{w.int(5);w.int(s.frequencyDays)}
        }
    }
    private fun readSchedule(r:Reader):HabitSchedule {
        r.record(SCHEDULE,2)
        return when(r.int()) {
            1->HabitSchedule.Once(r.stringOrNull())
            2->{demand(r.nil());HabitSchedule.Daily}
            3->{val size=r.int();demand(size in 0..7);HabitSchedule.Weekly(java.util.Collections.unmodifiableList(List(size){r.int()}))}
            4->HabitSchedule.Monthly(r.int())
            5->HabitSchedule.Custom(r.int())
            else->throw Rejected(Code.MALFORMED)
        }
    }
    private fun writeAppearance(w:Writer,a:ObjectAppearance?) {
        if(a==null) {w.nil();return}
        w.record(APPEARANCE,4)
        when(val icon=a.icon) {
            is IconReference.Role->{w.int(1);w.string(icon.role)}
            is IconReference.Asset->{w.int(2);w.string(icon.assetId)}
        }
        w.string(a.accentColor);w.string(a.iconTint)
    }
    private fun readAppearance(r:Reader):ObjectAppearance? {
        if(r.nil())return null
        r.record(APPEARANCE,4)
        val icon=when(r.int()) {1->IconReference.Role(r.string());2->IconReference.Asset(r.string());else->throw Rejected(Code.MALFORMED)}
        return ObjectAppearance(icon,r.string(),r.string())
    }
    private fun writeMetadata(w:Writer,m:PlanStructureMetadata?) {
        if(m==null) {w.nil();return}
        w.record(PLAN_METADATA,8);w.string(m.creationTimestamp);w.long(m.sortOrder);w.stringOrNull(m.startDate)
        w.stringOrNull(m.goalDueDate);w.stringOrNull(m.timezone);w.stringOrNull(m.targetUnit)
        w.stringOrNull(m.preferredLocalTime);w.stringOrNull(m.originAssignmentId)
    }
    private fun readMetadata(r:Reader):PlanStructureMetadata? {
        if(r.nil())return null
        r.record(PLAN_METADATA,8)
        return PlanStructureMetadata(creationTimestamp=r.string(),sortOrder=r.long(),startDate=r.stringOrNull(),
            goalDueDate=r.stringOrNull(),timezone=r.stringOrNull(),targetUnit=r.stringOrNull(),
            preferredLocalTime=r.stringOrNull(),originAssignmentId=r.stringOrNull())
    }

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
        fun boolOrNull(value: Boolean?) { if (value == null) nil() else bool(value) }
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
        fun boolOrNull(): Boolean? = if (nil()) null else bool()
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
