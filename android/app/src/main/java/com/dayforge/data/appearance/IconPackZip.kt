package com.dayforge.data.appearance

import java.util.zip.CRC32
import java.util.zip.DataFormatException
import java.util.zip.Inflater

internal class IconPackInputException(val code: String) : IllegalArgumentException(code)

internal fun packRequire(condition: Boolean, code: String = "PACK_ZIP_INVALID") {
    if (!condition) throw IconPackInputException(code)
}

/** Read-only ZIP index over caller-owned frozen bytes; never interprets names as filesystem paths. */
internal class IconPackZip(private val bytes: ByteArray, checkpoint: () -> Unit) {
    private data class Entry(val name: String, val flags: Int, val method: Int, val crc: Long,
        val size: Int, val compressed: Int, val local: Int, val needed: Int, var data: Int = 0)
    private val entries: Map<String, Entry>
    val names: Set<String> get() = entries.keys.toSet()

    init {
        val end = (bytes.size - 22 downTo maxOf(0, bytes.size - 65_557)).firstOrNull {
            u32(it) == 0x06054b50L && it + 22 + u16(it + 20) == bytes.size
        } ?: throw IconPackInputException("PACK_ZIP_INVALID")
        var count = u16(end + 10).toLong()
        var size = u32(end + 12)
        var start = u32(end + 16)
        var directoryEnd = end
        val zip64 = end >= 20 && u32(end - 20) == 0x07064b50L
        if (zip64) {
            packRequire(u32(end - 16) == 0L && u32(end - 4) == 1L)
            val record = bounded(u64(end - 12))
            range(record, 56)
            packRequire(u32(record) == 0x06064b50L && u64(record + 4) == 44L && record + 56 == end - 20)
            packRequire(u16(record + 14) == 45, "PACK_ZIP_UNSUPPORTED")
            packRequire(u32(record + 16) == 0L && u32(record + 20) == 0L)
            val wideCount = u64(record + 32)
            packRequire(u64(record + 24) == wideCount)
            fun agrees(narrow: Long, wide: Long, sentinel: Long) = narrow == sentinel || narrow == wide
            packRequire(agrees(u16(end + 4).toLong(), 0, 65535) && agrees(u16(end + 6).toLong(), 0, 65535))
            packRequire(agrees(u16(end + 8).toLong(), wideCount, 65535) && agrees(count, wideCount, 65535))
            packRequire(agrees(size, u64(record + 40), 0xffffffffL) && agrees(start, u64(record + 48), 0xffffffffL))
            count = wideCount; size = u64(record + 40); start = u64(record + 48); directoryEnd = record
        } else {
            packRequire(u16(end + 4) == 0 && u16(end + 6) == 0 && u16(end + 8).toLong() == count)
        }
        packRequire(count in 1..257 && start <= directoryEnd && size == directoryEnd.toLong() - start)
        var cursor = bounded(start)
        val indexed = linkedMapOf<String, Entry>()
        repeat(count.toInt()) {
            checkpoint()
            range(cursor, 46)
            packRequire(u32(cursor) == 0x02014b50L)
            val flags = u16(cursor + 8)
            val method = u16(cursor + 10)
            packRequire(u16(cursor + 6) in 10..45 && method in setOf(0, 8) && flags and 0x080e.inv() == 0, "PACK_ZIP_UNSUPPORTED")
            packRequire(method == 8 || flags and 6 == 0, "PACK_ZIP_UNSUPPORTED")
            val nameLength = u16(cursor + 28)
            val extraLength = u16(cursor + 30)
            val endEntry = cursor + 46 + nameLength + extraLength + u16(cursor + 32)
            packRequire(endEntry <= directoryEnd)
            val name = name(cursor + 46, nameLength)
            packRequire(name !in indexed, "PACK_ZIP_DUPLICATE")
            val attrs = u32(cursor + 38)
            val kind = (attrs shr 16).toInt() and 0xf000
            packRequire(attrs and 0x10L == 0L && kind in setOf(0, 0x8000), "PACK_ZIP_LINK")
            val extras = extras(cursor + 46 + nameLength, extraLength)
            validateNameExtra(extras, name)
            var wide = extras[1]?.first ?: -1
            val wideEnd = extras[1]?.second ?: -1
            fun wideValue(value: Long, sentinel: Long, width: Int = 8): Long {
                if (value != sentinel) return value
                packRequire(wide >= 0 && wide + width <= wideEnd)
                val result = if (width == 8) u64(wide) else u32(wide)
                wide += width
                return result
            }
            val expanded = wideValue(u32(cursor + 24), 0xffffffffL)
            val compressed = wideValue(u32(cursor + 20), 0xffffffffL)
            val local = wideValue(u32(cursor + 42), 0xffffffffL)
            packRequire(wideValue(u16(cursor + 34).toLong(), 65535, 4) == 0L)
            packRequire(wide == wideEnd)
            packRequire(expanded <= if (name == "manifest.json") 1_048_576 else 2_097_152, "PACK_ENTRY_LIMIT")
            indexed[name] = Entry(name, flags, method, u32(cursor + 16), expanded.toInt(), bounded(compressed), bounded(local), u16(cursor + 6))
            cursor = endEntry
        }
        packRequire(cursor == directoryEnd)
        val ordered = indexed.values.sortedBy { it.local }
        packRequire(ordered.first().local == 0)
        ordered.forEachIndexed { index, entry ->
            checkpoint()
            val offset = entry.local
            val next = ordered.getOrNull(index + 1)?.local ?: bounded(start)
            range(offset, 30)
            packRequire(u32(offset) == 0x04034b50L && u16(offset + 4) == entry.needed &&
                u16(offset + 6) == entry.flags && u16(offset + 8) == entry.method)
            val length = u16(offset + 26)
            packRequire(name(offset + 30, length) == entry.name)
            val extraLength = u16(offset + 28)
            val extras = extras(offset + 30 + length, extraLength)
            validateNameExtra(extras, entry.name)
            val data = offset + 30 + length + extraLength
            packRequire(data <= next && entry.compressed <= next - data)
            val extra = extras[1]
            var expanded = u32(offset + 22)
            var compressed = u32(offset + 18)
            if (expanded == 0xffffffffL || compressed == 0xffffffffL) {
                packRequire(extra != null && extra.second - extra.first == 16)
                expanded = u64(extra!!.first); compressed = u64(extra.first + 8)
            } else packRequire(extra == null)
            val descriptor = entry.flags and 8 != 0
            packRequire((expanded == entry.size.toLong() || descriptor && expanded == 0L) &&
                (compressed == entry.compressed.toLong() || descriptor && compressed == 0L) &&
                (u32(offset + 14) == entry.crc || descriptor && u32(offset + 14) == 0L))
            val tail = data + entry.compressed
            if (descriptor) {
                val length = next - tail
                packRequire(length in setOf(12, 16, 20, 24))
                val signed = length == 16 || length == 24
                if (signed) packRequire(u32(tail) == 0x08074b50L)
                val first = tail + if (signed) 4 else 0
                packRequire(u32(first) == entry.crc)
                if (length >= 20) packRequire(u64(first + 4) == entry.compressed.toLong() && u64(first + 12) == entry.size.toLong())
                else packRequire(u32(first + 4) == entry.compressed.toLong() && u32(first + 8) == entry.size.toLong())
            } else packRequire(tail == next)
            entry.data = data
        }
        entries = indexed
    }

    fun read(name: String, limit: Int, checkpoint: () -> Unit): ByteArray {
        val entry = entries[name] ?: throw IconPackInputException("PACK_ENTRY_MISSING")
        packRequire(entry.size <= limit, "PACK_ENTRY_LIMIT")
        checkpoint()
        val result = if (entry.method == 0) {
            packRequire(entry.size == entry.compressed)
            bytes.copyOfRange(entry.data, entry.data + entry.size)
        } else {
            val inflater = Inflater(true)
            try {
                inflater.setInput(bytes, entry.data, entry.compressed)
                val output = ByteArray(entry.size + 1)
                var count = 0
                while (!inflater.finished()) {
                    checkpoint()
                    val read = inflater.inflate(output, count, minOf(65_536, output.size - count))
                    count += read
                    packRequire(count <= entry.size, "PACK_ENTRY_LIMIT")
                    packRequire(read > 0 || inflater.finished(), "PACK_ZIP_DEFLATE")
                }
                packRequire(inflater.remaining == 0 && count == entry.size, "PACK_ZIP_DEFLATE")
                output.copyOf(count)
            } catch (_: DataFormatException) { throw IconPackInputException("PACK_ZIP_DEFLATE") }
            finally { inflater.end() }
        }
        packRequire(CRC32().apply { update(result) }.value == entry.crc, "PACK_ZIP_CRC")
        return result
    }

    private fun name(start: Int, length: Int): String {
        range(start, length)
        val value = bytes.copyOfRange(start, start + length)
        packRequire(value.all { it.toInt() in 32..126 }, "PACK_ZIP_PATH")
        return value.toString(Charsets.US_ASCII).also {
            packRequire(it == "manifest.json" || Regex("blobs/[0-9a-f]{64}").matches(it), "PACK_ZIP_PATH")
        }
    }
    private fun extras(start: Int, length: Int): Map<Int, Pair<Int, Int>> {
        range(start, length)
        val result = mutableMapOf<Int, Pair<Int, Int>>()
        var cursor = start
        while (cursor < start + length) {
            packRequire(start + length - cursor >= 4)
            val id = u16(cursor)
            val end = cursor + 4 + u16(cursor + 2)
            packRequire(end <= start + length && id !in result)
            // PKWARE Unix extra's variable tail can carry a link target; no links in this profile.
            packRequire(id != 0x000d || end - cursor - 4 == 12, "PACK_ZIP_LINK")
            result[id] = cursor + 4 to end
            cursor = end
        }
        return result
    }
    private fun validateNameExtra(extras: Map<Int, Pair<Int, Int>>, name: String) {
        extras[0x7075]?.let { (start, end) ->
            val raw = name.toByteArray(Charsets.US_ASCII)
            packRequire(end - start == 5 + raw.size && bytes[start] == 1.toByte() &&
                u32(start + 1) == CRC32().apply { update(raw) }.value &&
                bytes.copyOfRange(start + 5, end).contentEquals(raw), "PACK_ZIP_PATH")
        }
    }
    private fun bounded(value: Long): Int { packRequire(value in 0..bytes.size.toLong()); return value.toInt() }
    private fun range(start: Int, length: Int) = packRequire(start >= 0 && length >= 0 && start <= bytes.size - length)
    private fun u16(at: Int): Int { range(at, 2); return (bytes[at].toInt() and 255) or ((bytes[at + 1].toInt() and 255) shl 8) }
    private fun u32(at: Int): Long { range(at, 4); return u16(at).toLong() or (u16(at + 2).toLong() shl 16) }
    private fun u64(at: Int): Long {
        range(at, 8)
        val upper = u32(at + 4)
        packRequire(upper <= 0x7fffffffL)
        return u32(at) or (upper shl 32)
    }
}
