package com.dayforge.data.repository

/** Fixed categories only: never log SQL, payloads, dates, object IDs or account credentials. */
internal class HistoryAuditTiming(private val kind: Kind) {
    enum class Kind { CHALLENGE, COMPLETION, ONE_TIME }
    enum class Stage { AUTHORITY, RAW_ROWS, DECODE, REBUILD, PAYLOAD_HASH, DAY_RULES, FACT_ROWS, FACT_PROOF, SELECT_ROUND, EXIT_AUTH }
    private var previous = android.os.SystemClock.elapsedRealtime()

    fun mark(stage: Stage) {
        if (!com.dayforge.BuildConfig.DEBUG) return
        val now = android.os.SystemClock.elapsedRealtime()
        android.util.Log.d("HistoryAuditTiming", "kind=$kind stage=$stage elapsed_ms=${now - previous}")
        previous = now
    }
}
