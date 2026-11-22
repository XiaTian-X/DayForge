package com.dayforge.data.repository

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Fault injection BELOW the gate: throw only after the real engine ends a transaction. */
internal class G0CommitFaultFactory(private val eligibleEnd: (SupportSQLiteDatabase) -> Boolean = { true }) : SupportSQLiteOpenHelper.Factory {
    val failAfterNextEnd = AtomicBoolean()
    private val expectedTrace = AtomicReference<G0PhaseTrace?>()
    /** A particular reference writer may run beside Room invalidation maintenance. Do not
     * let an unrelated transaction consume its fault or crash that background coroutine.
     */
    fun failAfterNextEndIn(trace: G0PhaseTrace) {
        check(!failAfterNextEnd.get() && expectedTrace.get() == null)
        expectedTrace.set(trace)
        failAfterNextEnd.set(true)
    }
    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
        val helper = FrameworkSQLiteOpenHelperFactory().create(configuration)
        return object : SupportSQLiteOpenHelper by helper {
            private var previous: SupportSQLiteDatabase? = null
            private var wrapped: SupportSQLiteDatabase? = null
            private fun wrap(raw: SupportSQLiteDatabase): SupportSQLiteDatabase = synchronized(this) {
                if (previous !== raw) {
                    previous = raw
                    wrapped = object : SupportSQLiteDatabase by raw {
                        override fun endTransaction() {
                            val trace=G0PhaseTrace.current.get()
                            if(trace==null) raw.endTransaction()
                            else trace.cpuCall("engine_end_call") { raw.endTransaction() }
                            fun postcheck() {
                                val expected=expectedTrace.get()
                                if (expected != null && trace !== expected) return
                                if (eligibleEnd(raw) && failAfterNextEnd.compareAndSet(true, false)) {
                                    expectedTrace.compareAndSet(expected,null)
                                    error("G0_COMMIT_RESULT_LOST")
                                }
                            }
                            if(trace==null) postcheck() else trace.call("engine_end_postcheck") { postcheck() }
                        }
                    }
                }
                checkNotNull(wrapped)
            }
            override val writableDatabase get() = wrap(helper.writableDatabase)
            override val readableDatabase get() = wrap(helper.readableDatabase)
        }
    }
}
