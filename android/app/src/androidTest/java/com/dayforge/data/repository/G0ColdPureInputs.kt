package com.dayforge.data.repository

import android.database.Cursor
import com.dayforge.data.api.*
import com.dayforge.data.api.dto.*
import com.dayforge.data.local.HabitDatabase
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.KSerializer

/** Bounded pure decoding only. No SQL/absence/ownership/authorization result is retained.
 * Entries use the same strict G0 codec as locked verification, and only identical bytes AND the
 * exact code-owned serializer. Input and output never escape the private cold initialization.
 */
internal class G0ColdPureInputs private constructor(private val values: Map<Key,Any>) {
    enum class Kind { INTENT, WIRE, RESULT }
    data class Input(val kind: Kind, val bytes: ByteArray)
    private data class Key(val kind: Kind, val bytes: String)
    @Suppress("UNCHECKED_CAST")
    fun <T> find(bytes: ByteArray, serializer: KSerializer<T>): T? {
        if(bytes.size>MAX_INPUT) return null
        val kind=when {
            serializer===SyncV2Operation.serializer() -> Kind.INTENT
            serializer===NextSyncPushRequest.serializer() -> Kind.WIRE
            serializer===NextSyncOperationResult.serializer() -> Kind.RESULT
            else -> return null
        }
        // ISO-8859-1 is a reversible byte mapping, including malformed UTF-8. No lossy key.
        return values[Key(kind,bytes.toString(Charsets.ISO_8859_1))] as T?
    }
    companion object {
        private const val MAX_INPUT=8192
        private const val MAX_ENTRIES=384
        private const val MAX_BYTES=2_097_152
        suspend fun capture(db: HabitDatabase): List<Input> {
            NextEvidenceReadSession.current(db)
            val sql=db.openHelper.writableDatabase
            val specs=listOf(Triple("next_request_origins","intentJson",Kind.INTENT),
                Triple("next_transmissions","wireBytes",Kind.WIRE),Triple("next_acceptances","resultJson",Kind.RESULT))
            val result=mutableListOf<Input>();var bytes=0
            for((table,column,kind) in specs) {
                // Fixed internal names; only small values copied. This is speculative input,
                // never a membership proof. Missing/large/invalid values use original validation.
                // Limit candidates, not just small matches. CASE avoids copying oversized
                // payloads into the cursor. The normal (kind, requestId) index supplies order;
                // this is not a bound on I/O or a guarantee for an unverified damaged schema.
                sql.query("SELECT CASE WHEN length(CAST($column AS BLOB))<=8192 THEN $column ELSE NULL END FROM $table WHERE kind=? ORDER BY requestId LIMIT 128",arrayOf(NEXT_OPERATION)).use { c ->
                    while(c.moveToNext()) {
                        currentCoroutineContext().ensureActive()
                        G0PhaseTrace.current.get()?.call("cold_pure_candidate") { Unit }
                        val value=when {
                            kind==Kind.WIRE && c.getType(0)==Cursor.FIELD_TYPE_BLOB -> c.getBlob(0)
                            kind!=Kind.WIRE && c.getType(0)==Cursor.FIELD_TYPE_STRING -> c.getString(0).toByteArray(Charsets.UTF_8)
                            else -> continue
                        }
                        if(value.size<=MAX_INPUT && value.size<=MAX_BYTES-bytes) { result+=Input(kind,value.copyOf());bytes+=value.size }
                    }
                }
            }
            return result
        }
        suspend fun build(inputs: List<Input>, device: String? = null): G0ColdPureInputs {
            currentCoroutineContext().ensureActive()
            check(currentCoroutineContext()[NextEvidenceReadSession]==null) { "G0_PURE_INPUT_SESSION_TRANSFER" }
            val values=linkedMapOf<Key,Any>();var retained=0
            val codec=G0ColdStrictDecoder()
            // Interleaving is only a reuse hint. Missing/differently ordered rows may pair
            // unrelated values; exact full-envelope comparison must then fall back normally.
            val groups=inputs.take(MAX_ENTRIES).groupBy { it.kind }
            val ordered=(0 until (groups.values.maxOfOrNull { it.size } ?: 0)).flatMap { i ->
                Kind.entries.mapNotNull { groups[it]?.getOrNull(i) }
            }
            for(input in ordered) {
                currentCoroutineContext().ensureActive()
                if(input.bytes.size>MAX_INPUT || input.bytes.size>MAX_BYTES-retained) continue
                val bytes=input.bytes.copyOf()
                val key=Key(input.kind,bytes.toString(Charsets.ISO_8859_1))
                if(key in values) continue
                val value=try {
                    val trace=G0PhaseTrace.current.get()
                    suspend fun decode():Any = when(input.kind) {
                        Kind.INTENT -> codec.decode(bytes,SyncV2Operation.serializer())
                        Kind.WIRE -> if(device==null) codec.decode(bytes,NextSyncPushRequest.serializer()) else codec.decodeEnvelope(bytes,device)
                        Kind.RESULT -> codec.decode(bytes,NextSyncOperationResult.serializer())
                    }
                    if(trace==null) decode() else trace.stage("cold_pure_decode") { decode() }
                } catch(_:InvalidFrozenSyncRequest) {
                    // Not acceptance: the complete original audit will reject the actual input.
                    G0PhaseTrace.current.get()?.call("cold_pure_invalid_fallback") { Unit }
                    continue
                }
                currentCoroutineContext().ensureActive()
                values[key]=value;retained+=bytes.size
            }
            return G0ColdPureInputs(values.toMap())
        }
    }
}
