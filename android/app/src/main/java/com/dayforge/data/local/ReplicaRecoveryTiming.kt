package com.dayforge.data.local

/** Diagnostic durations only. No SQL, identifiers, contents or credentials are logged.
 * Nested durations overlap and must not be added as disjoint operation cost. This adds no
 * coroutine, dispatch, authority or cache; failures/cancellation retain their original type.
 */
internal object ReplicaRecoveryTiming {
    enum class Stage { SCHEMA, BUSINESS_RAW, BUSINESS_PROJECTION, ICON_REFERENCE, ICON_ACTUAL,
        // Diagnostic children of BUSINESS_RAW; do not add them to that inclusive parent.
        RAW_READ_BUILD, RAW_JSON, RAW_UTF8_LENGTH, RAW_BUDGET, RAW_HASH_WITH_UTF8,
        // FINISH_COORDINATED includes its children and entry wait; never add parent and children.
        FINISH_CATCHUP, FINISH_COORDINATED, FINISH_MATERIAL_PROOF, FINISH_DESTINATION_RECHECK,
        FINISH_RELEASE_CLOSE, FINISH_FINAL_PROOF, FINISH_PUBLISH }

    suspend fun <T> measure(stage: Stage, block: suspend () -> T): T {
        if (!com.dayforge.BuildConfig.DEBUG) return block()
        val started = android.os.SystemClock.elapsedRealtime()
        var returned = false
        try { return block().also { returned = true } }
        finally { android.util.Log.d("ReplicaRecoveryTiming", "stage=$stage returned=$returned elapsed_ms=${android.os.SystemClock.elapsedRealtime() - started}") }
    }

    fun <T> compute(stage: Stage, block: () -> T): T {
        if (!com.dayforge.BuildConfig.DEBUG) return block()
        val started = android.os.SystemClock.elapsedRealtime()
        var returned = false
        try { return block().also { returned = true } }
        finally { android.util.Log.d("ReplicaRecoveryTiming", "stage=$stage returned=$returned elapsed_ms=${android.os.SystemClock.elapsedRealtime() - started}") }
    }
}
