package com.dayforge.widget

import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import java.util.concurrent.atomic.AtomicInteger
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * Opt-in boundary for tests of business persistence, not asynchronous widget rendering.
 * Keep this rule outside database/activity rules so setup and teardown cannot enqueue work
 * that outlives their owned database. Widget scheduler/worker suites must not use this rule.
 */
class IsolatedWidgetRefreshRule : TestRule {
    private val requests = AtomicInteger()
    val requestCount: Int get() = requests.get()

    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            requests.set(0)
            mockkObject(WidgetRefreshScheduler)
            try {
                every { WidgetRefreshScheduler.request(any()) } answers {
                    requests.incrementAndGet()
                    null
                }
                base.evaluate()
            } finally {
                unmockkObject(WidgetRefreshScheduler)
            }
        }
    }
}
