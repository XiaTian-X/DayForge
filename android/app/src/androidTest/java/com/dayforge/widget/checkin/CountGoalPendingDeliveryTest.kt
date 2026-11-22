package com.dayforge.widget.checkin

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.lifecycleScope
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.SupportSQLiteStatement
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import kotlinx.serialization.json.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dayforge.R
import com.dayforge.data.appearance.MaterialSocketServer
import com.dayforge.data.local.PhysicalDatabaseEpochFixture
import com.dayforge.data.local.PreferencesManager
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.model.FailMode
import com.dayforge.data.model.HabitSchedule
import com.dayforge.data.model.HabitType
import com.dayforge.data.model.CheckInResult
import com.dayforge.data.repository.*
import com.dayforge.widget.IsolatedWidgetRefreshRule
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real retained Goal Activity plus a distinct real metric consumer. No fake source, writer
 * spy, direct status UPDATE, arbitrary callback hook or claim that display implies pixels. */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class CountGoalPendingDeliveryTest {
    @get:Rule(order = 0) val widgets = IsolatedWidgetRefreshRule()
    private val typedGoalWrites = TypedGoalNativeWrites()
    @get:Rule(order = 1) val epochs = PhysicalDatabaseEpochFixture().also { it.nativeFactory = typedGoalWrites }
    @get:Rule(order = 2) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 3) val compose = createEmptyComposeRule()
    @Inject lateinit var preferences: PreferencesManager
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext
    private val seen = java.util.Collections.synchronizedList(arrayListOf<Seen>())
    private data class Seen(val activity: androidx.activity.ComponentActivity, val job: Job)
    private lateinit var server: MaterialSocketServer
    private val callbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, state: Bundle?) {
            if (activity is GoalCompletionActivity || activity is MetricPromptActivity) {
                val component = activity as androidx.activity.ComponentActivity
                seen += Seen(component, requireNotNull(component.lifecycleScope.coroutineContext[Job]))
            }
        }
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }
    @Before fun connect() = runBlocking<Unit> {
        hilt.inject(); epochs.connect(); epochs.login()
        server = MaterialSocketServer { _, _ -> MaterialSocketServer.Reply("{}".toByteArray(), 503) }
        preferences.setServerUrl(server.origin.toString()); preferences.setRemoteServerUrl(null)
        epochs.sessions.exclusive {
            epochs.tokens.saveServerIdentity(UUID.randomUUID().toString(), UUID.randomUUID().toString())
            epochs.tokens.getOrCreateInstallationId()
            epochs.tokens.saveDeviceRegistration(UUID.randomUUID().toString(), setOf("sync.read", "structure.write", "facts.append", "timer.control"), true, 1)
        }
        (app.applicationContext as Application).registerActivityLifecycleCallbacks(callbacks)
    }
    @After fun finish() = runBlocking<Unit> {
        val all = synchronized(seen) { seen.toList() }
        instrumentation.runOnMainSync { all.forEach { if (!it.activity.isDestroyed) it.activity.finish() } }
        withTimeout(5_000) { all.forEach { it.job.join() } }
        (app.applicationContext as Application).unregisterActivityLifecycleCallbacks(callbacks)
        if (::server.isInitialized) server.close()
    }
    private fun launch(intent: Intent) = instrumentation.runOnMainSync { app.startActivity(intent) }
    private suspend fun next(type: Class<out Activity>, after: Int): Seen = withTimeout(30_000) {
        var observed = synchronized(seen) { seen.drop(after).firstOrNull { type.isInstance(it.activity) } }
        while (observed == null) {
            delay(25)
            observed = synchronized(seen) { seen.drop(after).firstOrNull { type.isInstance(it.activity) } }
        }
        observed
    }
    private suspend fun ended(value: Seen) {
        withTimeout(30_000) { while (!value.activity.isDestroyed) delay(25) }
        withTimeout(5_000) { value.job.join() }
    }
    private suspend fun seed(): Pair<CountCommandReceipt, CountCommandPendingRepository.FollowUp> = epochs.host.withGraph { graph ->
        val habit = HabitEntity(id = 816, uuid = UUID.randomUUID().toString(), name = "Original COUNT goal",
            habitType = HabitType.COUNTING, iconResId = 1, colorHex = "#123456", schedule = HabitSchedule.Daily,
            targetValue = 1, targetCycles = 1, failMode = FailMode.STRICT)
        graph.database.withTransaction {
            val sql = graph.database.openHelper.writableDatabase
            sql.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
            assertEquals(habit.id, graph.habitDao.insert(habit))
            sql.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
        }
        val identity = graph.countActionReader.legacy(habit)
        val result = graph.checkInService.incrementCount(app, habit.id, identity) as CheckInResult.Success
        assertTrue(result.goalReached)
        val receipt = requireNotNull(result.command)
        receipt to requireNotNull(graph.countCommandPendingRepository.followUp(receipt.reference))
    }
    private fun goal(follow: CountCommandPendingRepository.FollowUp): Intent = GoalCompletionActivity.createIntent(app,
        follow.habit.id, follow.habit.name, requireNotNull(follow.goalProgress), requireNotNull(follow.habit.targetCycles),
        legacyIdentity = requireNotNull(follow.legacyIdentity), countCommand = follow.reference, restoreCount = true)
    private suspend fun status(reference: CountCommandReference) = epochs.current { graph ->
        CountCommandStore(graph.database, epochs.tokens, epochs.sessions).resolve(reference.native()).historical.status
    }
    private suspend fun closed(reference: CountCommandReference) = withTimeout(30_000) {
        while (status(reference).pending) delay(25)
        assertEquals(CountCommandRecords.Delivery.CLOSED, status(reference).delivery)
    }

    @Test(timeout = 150_000) fun anotherRealConsumerClosesT2ThenOriginalDisplayedGoalAndOldIntentCannotMutateOrDisplay() = runBlocking<Unit> {
        val (receipt, follow) = seed(); val start = seen.size; launch(goal(follow))
        val original = next(GoalCompletionActivity::class.java, start)
        val label = app.getString(R.string.action_confirm_complete)
        compose.waitUntil(10_000) { compose.onAllNodesWithText(label).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() }
        val click = requireNotNull(compose.onNodeWithText(label).fetchSemanticsNode().config[SemanticsActions.OnClick].action)
        val beforeOther = seen.size
        launch(MetricPromptActivity.createIntent(app, follow.habit.id, follow.habit.name,
            legacyIdentity = follow.legacyIdentity, countCommand = receipt.reference, restoreCount = true))
        val other = next(MetricPromptActivity::class.java, beforeOther)
        closed(receipt.reference); ended(other)
        val before = epochs.exactMain()
        compose.mainClock.autoAdvance = false
        try {
            instrumentation.runOnMainSync {
                assertFalse(original.activity.isFinishing); assertFalse(original.activity.isDestroyed)
            }
            val event = instrumentation.uiAutomation.executeAndWaitForEvent({
                instrumentation.runOnMainSync { assertTrue(click.invoke()) }
            }, { it.text.any { text -> text.toString().contains("COMMAND_GOAL_NOT_PENDING") } }, 10_000)
            assertTrue(event.text.any { it.toString().contains("COMMAND_GOAL_NOT_PENDING") })
        } finally { compose.mainClock.autoAdvance = true }
        ended(original)
        assertEquals(before, epochs.exactMain())
        epochs.current { graph ->
            val current = requireNotNull(graph.habitDao.getHabitById(follow.habit.id))
            assertTrue(current.isActive); assertEquals(FailMode.STRICT, current.failMode)
            assertEquals(1, graph.completionDao.getByHabitOnce(current.id).size)
        }
        val lateStart = seen.size; launch(goal(follow))
        val late = next(GoalCompletionActivity::class.java, lateStart); ended(late)
        assertEquals(before, epochs.exactMain())
        assertTrue(compose.onAllNodesWithText(label).fetchSemanticsNodes(atLeastOneRootRequired = false).isEmpty())
    }

    @Test(timeout = 150_000) fun stillPendingGoalCanUseItsOriginalActionAndOriginalNoLinksConsumerClosesIt() = runBlocking<Unit> {
        val (receipt, follow) = seed(); assertTrue(status(receipt.reference).pending)
        val initial = seen.size; launch(goal(follow))
        val original = next(GoalCompletionActivity::class.java, initial)
        val label = app.getString(R.string.action_continue_checkin)
        compose.waitUntil(10_000) { compose.onAllNodesWithText(label).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() }
        compose.onNodeWithText(label).performClick()
        ended(original)
        val metric = next(MetricPromptActivity::class.java, initial); closed(receipt.reference); ended(metric)
        epochs.current { graph ->
            val current = requireNotNull(graph.habitDao.getHabitById(follow.habit.id))
            assertTrue(current.isActive); assertEquals(FailMode.LOOSE, current.failMode)
            val facts = graph.completionDao.getByHabitOnce(current.id)
            assertEquals(1, facts.size); assertEquals(receipt.factUuid, facts.single().uuid)
            assertEquals(0, graph.metricLogDao.countAll())
            graph.database.openHelper.writableDatabase.query("SELECT count(*) FROM main.count_command_results").use {
                assertTrue(it.moveToFirst()); assertEquals(1L, it.getLong(0)); assertFalse(it.moveToNext())
            }
        }
    }

    private data class TypedSeed(val receipt: CountCommandReceipt, val current: WidgetFactSnapshot,
        val completion: WidgetFactSnapshot, val fact: com.dayforge.data.local.entity.CompletionEntity,
        val frozenInput: String)

    /** Real typed creator and original COUNT private producer. Appearance is not the route
     * oracle: authenticate the actual formal Outcome and immutable protocol5 origin too. */
    private suspend fun typedSeed(): TypedSeed = epochs.host.withGraph { graph ->
        val id = graph.habitRepository.createHabit("Typed original COUNT goal", "", HabitType.COUNTING, 1,
            "#123456", HabitSchedule.Daily, targetValue = 1, targetCycles = 1, failMode = FailMode.STRICT,
            appearance = com.dayforge.domain.model.ObjectAppearance(com.dayforge.domain.model.IconReference.Role("habit.custom"),
                "#123456", "theme"), completionPolicy = "recurring", creationAuthority = graph.nextObjectCreator.capture())
        val habit = requireNotNull(graph.habitRepository.getHabitById(id))
        val original = graph.widgetFactReader.read(habit).claim
        assertEquals("This fixture exercises typed formal-v5, without a rounds/v1 activation", 0, original.challengeContract)
        val commandId = UUID.randomUUID().toString()
        val result = graph.checkInService.widgetCountActionWithReceipt(app, original, "increment", commandId)
        assertTrue(result.current.goalReached)
        val receipt = requireNotNull(result.current.command)
        assertEquals(commandId, receipt.commandId); assertTrue(receipt.recorded); assertTrue(receipt.requiresMetricFollowUp)
        val current = requireNotNull(result.current.followUp)
        val completion = requireNotNull(result.completionSource)
        assertEquals(receipt.factUuid, completion.claim.completionUuid)
        assertEquals(habit.uuid, completion.claim.habitUuid)
        assertEquals(1, current.targetProgress)
        val rows = graph.database.completionDao().getByHabitOnce(id)
        assertEquals(1, rows.size); val fact = rows.single()
        assertEquals(receipt.factUuid, fact.uuid); assertEquals(habit.uuid, fact.habitUuid)
        val history = CountCommandStore(graph.database, epochs.tokens, epochs.sessions).resolve(receipt.reference.native()).historical
        assertFalse(CountCommandLegacyInput.isLegacy(history.intent))
        assertEquals(CountCommandRecords.Action.COUNT_ADD, history.intent.action)
        assertEquals(CountCommandRecords.Execution.APPLIED, history.status.execution)
        assertTrue(history.status.pending)
        val input = history.intent.body().getValue("input").jsonObject
        assertEquals(original, CountCommandInputs.restoredWidget(input))
        assertEquals(JsonNull, input.getValue("round"))
        assertEquals(fact.uuid, CountCommandRecords.uuid(input, "reserved_fact_uuid"))
        val outcome = requireNotNull(history.outcome)
        assertEquals(CountCommandEvidence.Profile.FORMAL_V5, outcome.evidence.profile)
        outcome.evidence.requireFormalOutcome(history.intent, outcome)
        val source = graph.database.syncOutboxDao().getAll().single { it.recordType == "completion" && it.entityUuid == fact.uuid }
        val origin = requireNotNull(graph.database.nextRequestDao().origin(NEXT_OPERATION, source.operationId))
        assertEquals(5, origin.protocol); assertEquals(source.id, origin.queueId)
        assertEquals(NextRequestSql.sourceHash(source), origin.sourceHash)
        val operation = decodeNextOperationIntent(origin.intentJson)
        assertEquals("activity_event", operation.entityType); assertEquals(fact.uuid, operation.entityUuid)
        assertEquals("upsert", operation.action)
        TypedSeed(receipt, current, completion, fact, history.intent.text())
    }
    private fun typedGoal(seed: TypedSeed): Intent = GoalCompletionActivity.createIntent(app,
        seed.current.habit.id, seed.current.habit.name, seed.current.targetProgress,
        requireNotNull(seed.current.habit.targetCycles), claim = seed.current.claim,
        countCommand = seed.receipt.reference, countCompletionSource = seed.completion.claim, restoreCount = true)
    private suspend fun requireSameTypedCount(seed: TypedSeed) = epochs.current { graph ->
        val facts = graph.database.completionDao().getByHabitOnce(seed.current.habit.id)
        assertEquals(listOf(seed.fact), facts)
        val row = CountCommandStore(graph.database, epochs.tokens, epochs.sessions).resolve(seed.receipt.reference.native()).historical
        assertEquals(seed.frozenInput, row.intent.text())
        val outcome = requireNotNull(row.outcome)
        assertEquals(CountCommandEvidence.Profile.FORMAL_V5, outcome.evidence.profile)
        outcome.evidence.requireFormalOutcome(row.intent, outcome)
        assertEquals(1, graph.database.syncOutboxDao().getAll().count { it.recordType == "completion" && it.entityUuid == seed.fact.uuid })
        assertEquals(0, graph.metricLogDao.countAll())
        graph.database.openHelper.writableDatabase.query("SELECT count(*) FROM main.count_command_intents").use {
            assertTrue(it.moveToFirst()); assertEquals(1L, it.getLong(0)); assertFalse(it.moveToNext())
        }
        graph.database.openHelper.writableDatabase.query("SELECT count(*) FROM main.count_command_results").use {
            assertTrue(it.moveToFirst()); assertEquals(1L, it.getLong(0)); assertFalse(it.moveToNext())
        }
    }

    @Test(timeout = 150_000) fun typedOriginalProducerGoalDisplaysAndConfirmUsesOriginalWriterBeforeRealConsumerT2() = runBlocking<Unit> {
        val seed = typedSeed(); val start = seen.size
        typedGoalWrites.arm(seed.current.habit.id)
        launch(typedGoal(seed))
        val goal = next(GoalCompletionActivity::class.java, start)
        val confirm = app.getString(R.string.action_confirm_complete)
        compose.waitUntil(10_000) { compose.onAllNodesWithText(confirm).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() }
        compose.onNodeWithText(confirm).assertIsEnabled().performClick()
        ended(goal)
        val metric = next(MetricPromptActivity::class.java, start)
        closed(seed.receipt.reference); ended(metric)
        assertEquals(1, typedGoalWrites.attempts.get())
        assertEquals(1, typedGoalWrites.executed.get())
        epochs.current { graph ->
            val current = requireNotNull(graph.habitDao.getHabitById(seed.current.habit.id))
            assertEquals(seed.current.habit.uuid, current.uuid); assertFalse(current.isActive)
            assertEquals(FailMode.STRICT, current.failMode)
        }
        requireSameTypedCount(seed)
    }

    @Test(timeout = 150_000) fun typedClosedByAnotherRealConsumerRejectsOldGoalCallbackBeforeNativeUpdateAndOldIntent() = runBlocking<Unit> {
        var bodyStage = "typed_seed"
        try {
            val seed = typedSeed(); val start = seen.size
            bodyStage = "goal_launch"
            launch(typedGoal(seed))
            bodyStage = "goal_created_wait"
            val original = next(GoalCompletionActivity::class.java, start)
            val label = app.getString(R.string.action_confirm_complete)
            bodyStage = "confirm_semantics_wait"
            compose.waitUntil(10_000) { compose.onAllNodesWithText(label).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() }
            bodyStage = "original_onclick_capture"
            val oldClick = requireNotNull(compose.onNodeWithText(label).fetchSemanticsNode().config[SemanticsActions.OnClick].action)
            val otherStart = seen.size
            bodyStage = "other_metric_launch"
            launch(MetricPromptActivity.createIntent(app, seed.current.habit.id, seed.current.habit.name,
                factClaim = seed.completion.claim, countCommand = seed.receipt.reference, restoreCount = true))
            bodyStage = "other_metric_created_wait"
            val other = next(MetricPromptActivity::class.java, otherStart)
            bodyStage = "other_consumer_t2_and_end"
            closed(seed.receipt.reference); ended(other)
            bodyStage = "complete_before_image"
            val before = epochs.exactMain()
            typedGoalWrites.arm(seed.current.habit.id)
            bodyStage = "old_callback_clock_scope"
            compose.mainClock.autoAdvance = false
            try {
                instrumentation.runOnMainSync {
                    assertFalse(original.activity.isFinishing); assertFalse(original.activity.isDestroyed)
                }
                val event = instrumentation.uiAutomation.executeAndWaitForEvent({
                    instrumentation.runOnMainSync { assertTrue(oldClick.invoke()) }
                }, { it.text.any { text -> text.toString().contains("COMMAND_GOAL_NOT_PENDING") } }, 10_000)
                try { assertTrue(event.text.any { it.toString().contains("COMMAND_GOAL_NOT_PENDING") }) }
                finally { recycleTypedEvent(event) }
            } finally { compose.mainClock.autoAdvance = true }
            bodyStage = "old_goal_lifecycle_end"
            ended(original) // Original lifecycle Job includes the rejected command; no idle-only oracle.
            assertEquals(0, typedGoalWrites.attempts.get()); assertEquals(0, typedGoalWrites.executed.get())
            assertEquals(before, epochs.exactMain())
            epochs.current { graph ->
                val current = requireNotNull(graph.habitDao.getHabitById(seed.current.habit.id))
                assertTrue(current.isActive); assertEquals(FailMode.STRICT, current.failMode)
            }
            requireSameTypedCount(seed)
            bodyStage = "old_intent_relaunch"
            val lateStart = seen.size; launch(typedGoal(seed))
            val late = next(GoalCompletionActivity::class.java, lateStart); ended(late)
            assertEquals(0, typedGoalWrites.attempts.get()); assertEquals(0, typedGoalWrites.executed.get())
            assertEquals(before, epochs.exactMain())
            assertTrue(compose.onAllNodesWithText(label).fetchSemanticsNodes(atLeastOneRootRequired = false).isEmpty())
            android.util.Log.i("CountGoal287", "typed_closed_body_returned")
        } catch (failure: Throwable) {
            // Log before JUnit @After can mask the original body failure with disposal.
            // Preserve the SAME Throwable, original method timeout and all existing work.
            android.util.Log.e("CountGoal287", "typed_closed_body_failure stage=$bodyStage thread=${Thread.currentThread().name}", failure)
            throw failure
        }
    }
    @Suppress("DEPRECATION")
    private fun recycleTypedEvent(event: android.view.accessibility.AccessibilityEvent) { event.recycle() }

}


/** Fixed lower-native observer, not a SQL injection/authorization seam. Always wraps the
 * generated statement at compile time, so a cached-before-arm UPDATE is still observed.
 * Only the two new typed methods arm it; the original legacy cases delegate unchanged. */
private class TypedGoalNativeWrites : SupportSQLiteOpenHelper.Factory {
    val attempts = java.util.concurrent.atomic.AtomicInteger()
    val executed = java.util.concurrent.atomic.AtomicInteger()
    private val expectedId = java.util.concurrent.atomic.AtomicLong()
    fun arm(id: Long) { check(id > 0 && expectedId.compareAndSet(0, id)); check(attempts.get() == 0 && executed.get() == 0) }
    private fun <T> execute(raw: SupportSQLiteDatabase, id: Long?, target: Long?, action: () -> T): T {
        if (expectedId.get() == 0L) return action()
        attempts.incrementAndGet()
        check(id == expectedId.get() && raw.inTransaction() && target == 0L)
        val result = action()
        check(raw.inTransaction())
        raw.query("SELECT changes()").use { check(it.moveToFirst() && it.getLong(0) == 1L && !it.moveToNext()) }
        raw.query("SELECT isActive FROM main.habits WHERE id=? LIMIT 2", arrayOf(requireNotNull(id))).use {
            check(it.moveToFirst() && it.getType(0) == android.database.Cursor.FIELD_TYPE_INTEGER && it.getLong(0) == 0L && !it.moveToNext())
        }
        executed.incrementAndGet()
        return result
    }
    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
        check(configuration.context.packageName == "com.dayforge.testbed")
        var native: SupportSQLiteDatabase? = null; var wrapped: SupportSQLiteDatabase? = null
        val monitor = Any()
        fun wrap(raw: SupportSQLiteDatabase): SupportSQLiteDatabase = synchronized(monitor) {
            if (native !== raw) {
                native = raw
                wrapped = object : SupportSQLiteDatabase by raw {
                    override fun compileStatement(sql: String): SupportSQLiteStatement {
                        val statement = raw.compileStatement(sql)
                        if (sql != GOAL_UPDATE) return statement
                        return object : SupportSQLiteStatement by statement {
                            var id: Long? = null; var target: Long? = null
                            override fun bindLong(index: Int, value: Long) {
                                statement.bindLong(index, value)
                                if (index == 1) target = value
                                if (index == 3) id = value
                            }
                            override fun clearBindings() { statement.clearBindings(); id = null; target = null }
                            override fun execute() = execute(raw, id, target) { statement.execute() }
                            override fun executeInsert(): Long = execute(raw, id, target) { statement.executeInsert() }
                            override fun executeUpdateDelete(): Int = execute(raw, id, target) { statement.executeUpdateDelete() }
                        }
                    }
                    override fun execSQL(sql: String, bindArgs: Array<out Any?>) {
                        if (sql == GOAL_UPDATE) execute(raw, (bindArgs.getOrNull(2) as? Number)?.toLong(),
                            (bindArgs.getOrNull(0) as? Number)?.toLong()) { raw.execSQL(sql, bindArgs) }
                        else raw.execSQL(sql, bindArgs)
                    }
                }
            }
            requireNotNull(wrapped)
        }
        val parent = configuration.callback
        val callback = object : SupportSQLiteOpenHelper.Callback(parent.version) {
            override fun onConfigure(db: SupportSQLiteDatabase) = parent.onConfigure(wrap(db))
            override fun onCreate(db: SupportSQLiteDatabase) = parent.onCreate(wrap(db))
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = parent.onUpgrade(wrap(db), oldVersion, newVersion)
            override fun onDowngrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = parent.onDowngrade(wrap(db), oldVersion, newVersion)
            override fun onOpen(db: SupportSQLiteDatabase) = parent.onOpen(wrap(db))
            override fun onCorruption(db: SupportSQLiteDatabase) = parent.onCorruption(wrap(db))
        }
        val helper = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(configuration.context)
            .name(configuration.name).callback(callback).noBackupDirectory(configuration.useNoBackupDirectory).build())
        return object : SupportSQLiteOpenHelper by helper {
            override val writableDatabase get() = wrap(helper.writableDatabase)
            override val readableDatabase get() = wrap(helper.readableDatabase)
        }
    }
    private companion object { const val GOAL_UPDATE = "UPDATE habits SET isActive = ?, updatedAt = ? WHERE id = ?" }
}
