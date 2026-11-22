package com.dayforge.widget

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/** Default delegates to the unchanged isolation rule; explicit real mode never calls MockK. */
class CountJourneyWidgetRule : TestRule {
    val real = RealWidgetRefreshRuntime.isReal(
        InstrumentationRegistry.getArguments().getString(RealWidgetRefreshRuntime.ARGUMENT)
    )
    private var owner: RealWidgetRefreshRuntime.Session? = null

    override fun apply(base: Statement, description: Description): Statement {
        if (!real) return IsolatedWidgetRefreshRule().apply(base, description)
        return object : Statement() {
            override fun evaluate() {
                owner = runBlocking { RealWidgetRefreshRuntime.open(InstrumentationRegistry.getInstrumentation().targetContext) }
                // The UI @After drains while its Hilt component/tokens/Host still exist.
                // PhysicalDatabaseEpochFixture's beforeCleanup gate refuses unsafe release.
                base.evaluate()
                assertSafeForHostCleanup()
            }
        }
    }

    suspend fun drainAfterProducersJoined() { requireNotNull(owner).drainAfterProducersJoined() }
    fun assertSafeForHostCleanup() { if (real) requireNotNull(owner).assertSafeForHostCleanup() }
    fun writeEvidence(directory: File?) { owner?.writeEvidence(directory) }
}
