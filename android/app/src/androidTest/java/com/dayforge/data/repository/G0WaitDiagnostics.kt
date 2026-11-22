package com.dayforge.data.repository

import android.os.Debug
import android.os.Process
import android.os.SystemClock
import android.os.Trace
import java.util.concurrent.atomic.AtomicInteger

/** G0 opt-in observation only. Process GC counters are not per-thread stall attribution. */
internal object G0WaitDiagnostics {
    private val sequence=AtomicInteger()
    private val phases=setOf("cold_validation","cold_prepare_history","cold_finish_history","cold_journal",
        "cold_open","cold_transaction","reference_open","engine_end_call")
    private val keys=listOf("art.gc.gc-count","art.gc.gc-time","art.gc.blocking-gc-count",
        "art.gc.blocking-gc-time","art.gc.bytes-allocated")
    data class Sample(val id:Int,val phase:String,val start:Long,val end:Long,val startTid:Int,val endTid:Int,
        val cpuNanos:Long?,val counters:Map<String,Long>,val ok:Boolean)
    data class Started(val id:Int,val phase:String,val start:Long,val tid:Int,val cpu:Long,val counters:Map<String,Long>)
    private fun counters():Map<String,Long> = keys.mapNotNull { key -> Debug.getRuntimeStat(key)?.toLongOrNull()?.let { key to it } }.toMap()
    fun begin(phase:String):Started? {
        if(phase !in phases) return null
        val before=counters();val cpu=Debug.threadCpuTimeNanos();val tid=Process.myTid()
        val id=sequence.incrementAndGet()
        val start=SystemClock.elapsedRealtimeNanos()
        if(android.os.Build.VERSION.SDK_INT>=29) Trace.beginAsyncSection("G0Wait:$id:$phase",id)
        return Started(id,phase,start,tid,cpu,before)
    }
    fun end(started:Started,ok:Boolean):Sample {
        val end=SystemClock.elapsedRealtimeNanos();val tid=Process.myTid();val cpu=Debug.threadCpuTimeNanos()
        if(android.os.Build.VERSION.SDK_INT>=29) Trace.endAsyncSection("G0Wait:${started.id}:${started.phase}",started.id)
        val after=counters()
        return Sample(started.id,started.phase,started.start,end,started.tid,tid,
            if(tid==started.tid && started.cpu>=0 && cpu>=started.cpu) cpu-started.cpu else null,
            started.counters.mapNotNull { (key,value) -> after[key]?.takeIf { it>=value }?.let { key to it-value } }.toMap(),ok)
    }
}
