package com.dayforge.data.repository

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.security.MessageDigest
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Pure byte equivalence plus a small physical-device cost sample, not a capacity gate. */
@RunWith(AndroidJUnit4::class)
class PayloadHashCostTest {
    private fun original(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    @Test fun exactUtf8DigestIncludesWhitespaceUnicodeAndMalformedSurrogates() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", syncPayloadHash(""))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", syncPayloadHash("abc"))
        val values = listOf("", "abc", "{\"value\":1}", "{ \"value\":1 }", "目标🙂\n\t\u0000", "\uD800", "\uDC00") +
            listOf(31, 32, 55, 56, 63, 64, 65, 4096, 16384).map { "日".repeat(it) }
        val locale = Locale.getDefault()
        try {
            for (selected in listOf(Locale.US, Locale.forLanguageTag("tr-TR"), Locale.forLanguageTag("ar"))) {
                Locale.setDefault(selected)
                values.forEach {
                    assertEquals(original(it), syncPayloadHash(it))
                    assertEquals(original(it), nextRequestHash(it.toByteArray(Charsets.UTF_8)))
                }
            }
        } finally { Locale.setDefault(locale) }
        val bytes = ByteArray(256) { it.toByte() }
        assertEquals(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }, nextRequestHash(bytes))
        assertNotEquals(syncPayloadHash(values[2]), syncPayloadHash(values[3]))
    }

    @Test fun compareExistingPayloadPathWithEquivalentDirectHexOnDevice() {
        val payloads = List(128) { "{\"sequence\":$it,\"note\":\"${"历史校验".repeat(128)}\"}" }
        payloads.forEach { assertEquals(original(it), nextRequestHash(it.toByteArray(Charsets.UTF_8))) }
        fun measure(hash: (String) -> String): Pair<Long, Int> {
            var checksum = 0
            val start = SystemClock.elapsedRealtimeNanos()
            payloads.forEach { checksum = checksum xor hash(it).hashCode() }
            return (SystemClock.elapsedRealtimeNanos() - start) / 1000 to checksum
        }
        val formatted = mutableListOf<Long>(); val direct = mutableListOf<Long>()
        repeat(3) { round ->
            val old: Pair<Long, Int>; val new: Pair<Long, Int>
            if (round % 2 == 0) { old = measure(::original); new = measure { nextRequestHash(it.toByteArray(Charsets.UTF_8)) } }
            else { new = measure { nextRequestHash(it.toByteArray(Charsets.UTF_8)) }; old = measure(::original) }
            assertEquals(old.second, new.second)
            formatted += old.first; direct += new.first
        }
        android.util.Log.i("PayloadHashCost", "samples=128 formatted_us=$formatted direct_us=$direct")
    }
}
