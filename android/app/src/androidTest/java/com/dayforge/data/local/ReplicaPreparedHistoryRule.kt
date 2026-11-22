package com.dayforge.data.local

import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/** Test-only handoff between the existing phase watchdog and PhysicalDatabaseRule.
 * Unmarked methods retain their exact original Statement, including runner deadlines.
 * No owner is invented, replaced, transferred across cases, or used as business authority. */
internal class ReplicaPreparedHistoryRule(
    private val storage: PhysicalDatabaseRule,
    private val preserve: (Throwable) -> Unit = { PhysicalDatabaseRule.preserveProcessCleanupFailure(it) }
) : TestRule {
    private val stages = RetainedJourneyStages(preserve)
    private val monitor = Any()
    private var preparationOwner: Job? = null
    private var closing = false
    internal var methodName: String? = null
        private set
    internal val selected: Boolean get() = stages.requiresPreparation
    internal val actualPreparationOwner: Job? get() = synchronized(monitor) { preparationOwner }
    internal var fallbackOwner: Job? = null
        private set

    internal fun <T> prepare(block: suspend () -> T): T = stages.prepare {
        val actual = requireNotNull(currentCoroutineContext()[Job])
        synchronized(monitor) {
            check(!closing && preparationOwner == null) { "REPLICA_PREPARATION_ADMISSION_CLOSED" }
            preparationOwner = actual
        }
        block()
    }

    internal fun business(block: () -> Unit) {
        synchronized(monitor) { check(!closing) { "REPLICA_PREPARATION_ADMISSION_CLOSED" } }
        stages.business(block)
    }

    /** Called from the subclass's single @After, before inherited actor/database cleanup.
     * An interrupted inherited @Before can leave work alive before prepare() was entered:
     * null is not completion. Seal the original physical fence before JUnit continues.
     * A normally failed prepare may register only its actual, already completed Job; this
     * preserves PhysicalDatabaseRule's original bounded DataStore cleanup branch. */
    internal fun beforeInheritedCleanup() {
        if (!selected) return
        synchronized(monitor) {
            closing = true
            try {
                stages.cleanup()
                val original = checkNotNull(preparationOwner) { "REPLICA_SETUP_COMPLETION_UNPROVEN" }
                check(original.isCompleted) { "REPLICA_PREPARATION_OWNER_NOT_DRAINED" }
                if (!storage.usesRecoveryCleanup) {
                    storage.ownRecoveryMethod(original)
                    fallbackOwner = original
                }
            } catch (error: Throwable) {
                preserve(error)
                throw error
            }
        }
    }

    override fun apply(base: Statement, description: Description): Statement {
        if (description.getAnnotation(RetainedPreparedJourney::class.java) == null) return base
        methodName = description.methodName
        return stages.apply(base, description)
    }
}
