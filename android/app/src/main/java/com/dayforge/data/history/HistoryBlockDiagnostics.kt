package com.dayforge.data.history

/** Fixed, data-free observations only. An observer cannot supply or replace a decoded Node,
 * a proof, a record, or a return value. The implementation always invokes its own encode/decode.
 * No observer is installed by production code in the extraction phase. */
internal object HistoryBlockDiagnostics {
    enum class Stage { NODE_DECODE, NODE_ENCODE }
    fun interface Observation { fun end(success: Boolean) }
    fun interface Observer { fun begin(stage: Stage): Observation }
    val current = ThreadLocal<Observer?>()

    fun <T> measure(stage: Stage, block: () -> T): T {
        val observation = current.get()?.begin(stage)
        var success = false
        var primaryFailure: Throwable? = null
        return try { block().also { success = true } }
        catch (failure: Throwable) { primaryFailure = failure; throw failure }
        finally {
            try { observation?.end(success) }
            catch (observationFailure: Throwable) {
                val primary = primaryFailure
                if (primary == null) throw observationFailure
                if (observationFailure !== primary) primary.addSuppressed(observationFailure)
            }
        }
    }
}
