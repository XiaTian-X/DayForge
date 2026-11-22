package com.dayforge.widget.checkin

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.dayforge.data.local.LocalReplicaIdentity
import com.dayforge.data.local.PhysicalDatabaseEpochFixture
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.model.HabitSchedule
import com.dayforge.data.model.HabitType
import com.dayforge.data.repository.LegacyWidgetRestartClaim
import com.dayforge.widget.IsolatedWidgetRefreshRule
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import java.util.Collections
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Actual Hilt Activity self-CLEAR_TOP/system TaskInfo + original durable legacy producer.
 * No fake task manager, no suspend writer spy. Known-Intent replay is NOT process death.
 * Separate host/PID gates and typed/multi-tap/Main-back-stack cases remain mandatory.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class CountWidgetTaskEntryTest {
    @get:Rule(order = 0) val widgets = IsolatedWidgetRefreshRule()
    @get:Rule(order = 1) val fixture = PhysicalDatabaseEpochFixture()
    @get:Rule(order = 2) val hilt = HiltAndroidRule(this)
    @javax.inject.Inject lateinit var countGate: com.dayforge.domain.service.CountActionGate
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext
    private data class Seen(val activity: ComponentActivity, val intent: Intent, val task: Int,
        val systemBase: Intent?, val job: Job, val destroyed: AtomicBoolean = AtomicBoolean())
    private val seen = Collections.synchronizedList(arrayListOf<Seen>())
    private val changed = MutableStateFlow(0)
    private val callbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, state: Bundle?) {
            if (activity !is CountWidgetActionActivity && activity !is WidgetFactActionActivity &&
                activity !is MetricPromptActivity && activity !is GoalCompletionActivity) return
            val component = activity as ComponentActivity
            val task = activity.getSystemService(ActivityManager::class.java).appTasks.map { it.taskInfo }
                .singleOrNull { taskId(it) == activity.taskId }
            seen += Seen(component, Intent(activity.intent), activity.taskId, task?.baseIntent?.let(::Intent),
                requireNotNull(component.lifecycleScope.coroutineContext[Job]))
            changed.value++
        }
        override fun onActivityDestroyed(activity: Activity) {
            synchronized(seen) { seen.singleOrNull { it.activity === activity } }?.destroyed?.set(true)
            changed.value++
        }
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    }
    @Suppress("DEPRECATION")
    private fun taskId(info: ActivityManager.RecentTaskInfo) = if (android.os.Build.VERSION.SDK_INT >= 29) info.taskId else info.id
    @Before fun setup() = runBlocking<Unit> {
        hilt.inject(); fixture.connect(); fixture.login()
        (app.applicationContext as Application).registerActivityLifecycleCallbacks(callbacks)
    }
    @After fun finishActivities() = runBlocking<Unit> {
        val all = synchronized(seen) { seen.toList() }
        withContext(Dispatchers.Main) { all.forEach { if (!it.activity.isFinishing) it.activity.finish() } }
        for (item in all) withTimeout(5_000) { item.job.join() }
        (app.applicationContext as Application).unregisterActivityLifecycleCallbacks(callbacks)
    }
    private suspend fun awaitSeen(predicate: (Seen) -> Boolean): Seen = withTimeout(30_000) {
        var result: Seen? = null
        changed.first { synchronized(seen) { seen.firstOrNull(predicate) }.also { result = it } != null }
        requireNotNull(result)
    }
    private suspend fun ended(item: Seen) {
        withTimeout(30_000) { changed.first { item.destroyed.get() } }
        withTimeout(5_000) { item.job.join() }
    }
    private suspend fun seed() = fixture.current { graph ->
        val habit = HabitEntity(name = "Legacy stable task", habitType = HabitType.COUNTING, iconResId = 0,
            colorHex = "#123456", schedule = HabitSchedule.Daily, targetValue = 10, targetCycles = null)
        val id = graph.database.habitDao().insert(habit)
        val row = requireNotNull(graph.database.habitDao().getVisibleHabitById(id))
        val identity = fixture.sessions.exclusive { graph.database.withTransaction {
            LegacyWidgetRestartClaim.displayed(row, requireNotNull(fixture.tokens.localCoreWriteAccess()),
                LocalReplicaIdentity.readInTransaction(graph.database))
        } }
        graph.legacyWidgetFactWorkflow.capture(identity).forAction("increment")
    }
    private fun launch(intent: Intent) = instrumentation.runOnMainSync { app.startActivity(intent) }
    private fun after(index: Int): List<Seen> = synchronized(seen) { seen.drop(index) }
    private fun size() = synchronized(seen) { seen.size }
    private suspend fun countImage(): Triple<Int, Int, Int> = fixture.current { graph -> graph.database.withTransaction {
        fun count(table: String) = graph.database.openHelper.writableDatabase.query("SELECT count(*) FROM `$table`").use {
            assertTrue(it.moveToFirst()); it.getInt(0)
        }
        Triple(count("completions"), count("count_command_intents"), count("count_command_results"))
    } }
    private suspend fun completeFresh(intent: Intent, expectPrompt: Boolean = true): Seen {
        val initial = size(); launch(intent)
        val known = awaitSeen { item -> after(initial).any { it === item } && item.activity is CountWidgetActionActivity &&
            item.intent.data?.scheme == "dayforge-count-known" }
        assertEquals(known.intent.component, known.systemBase?.component)
        assertEquals(known.intent.data, known.systemBase?.data)
        val raw = awaitSeen { item -> after(initial).any { it === item } && item.activity is CountWidgetActionActivity &&
            item.intent.data?.scheme == "dayforge-count-raw" }
        assertEquals(raw.task, known.task)
        ended(raw); ended(known)
        if (expectPrompt) {
            val prompt = awaitSeen { item -> after(initial).any { it === item } && item.activity is MetricPromptActivity }
            ended(prompt)
        } else assertTrue(after(initial).none { it.activity is MetricPromptActivity || it.activity is GoalCompletionActivity })
        return known
    }

    @Test(timeout = 150_000) fun actualSystemHandoffCommitsOnceKnownReplayDoesNotWriteAndFreshSecondTapIsIndependent() = runBlocking<Unit> {
        val input = seed()
        val first = completeFresh(CountWidgetActionActivity.legacyIntent(app, input))
        assertEquals(Triple(1, 1, 1), countImage())
        val beforeReplay = fixture.exactMain(); val start = size()
        launch(Intent(first.intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_MULTIPLE_TASK))
        val repeated = awaitSeen { item -> after(start).any { it === item } && item.activity is CountWidgetActionActivity }
        ended(repeated)
        // The first actual no-links consumer already closed this exact command. Current
        // authenticated delivery prevents a replayed known ID from opening a second form.
        assertTrue(after(start).none { it.activity is MetricPromptActivity || it.activity is GoalCompletionActivity })
        assertEquals(Triple(1, 1, 1), countImage())
        assertEquals(beforeReplay, fixture.exactMain())
        val second = completeFresh(CountWidgetActionActivity.legacyIntent(app, input))
        assertNotEquals(first.intent.data?.authority, second.intent.data?.authority)
        assertNotEquals(first.task, second.task)
        assertEquals(Triple(2, 2, 2), countImage())
        fixture.current { graph ->
            assertEquals(2, graph.database.syncOutboxDao().getAll().count { it.recordType == "completion" })
            graph.database.openHelper.writableDatabase.query("SELECT count(*) FROM count_command_status WHERE delivery NOT IN ('CLOSED','DELIVERED')").use {
                assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
            }
        }
    }

    @Test(timeout = 150_000) fun systemKnownIdWithoutPersistentIntentCannotBecomeFreshAndCreatesNoEffect() = runBlocking<Unit> {
        val input = seed(); val first = completeFresh(CountWidgetActionActivity.legacyIntent(app, input))
        val before = fixture.exactMain(); val start = size()
        val unknown = Intent(first.intent).apply {
            data = requireNotNull(data).buildUpon().authority(UUID.randomUUID().toString()).build()
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        }
        launch(unknown)
        val rejected = awaitSeen { item -> after(start).any { it === item } && item.activity is CountWidgetActionActivity }
        assertEquals(unknown.data, rejected.systemBase?.data)
        ended(rejected)
        assertEquals(before, fixture.exactMain()); assertEquals(Triple(1, 1, 1), countImage())
        assertTrue(after(start).none { it.activity is MetricPromptActivity || it.activity is GoalCompletionActivity })
    }

    @Test(timeout = 150_000) fun existingPreparedRecoveryMustAcquireSameHabitGateBeforeAnyT1OrT2() = runBlocking<Unit> {
        val claim = seed(); val commandId = UUID.randomUUID().toString()
        fixture.current { graph ->
            val executor = com.dayforge.data.repository.CountCommandLegacyWidgetExecutor(graph.database, fixture.tokens, fixture.sessions)
            val habit = requireNotNull(graph.database.habitDao().getVisibleHabitById(claim.identity.habitId))
            val prepared = executor.begin(habit, claim, commandId)
            executor.release(prepared.second)
        }
        assertEquals(Triple(0, 1, 0), countImage())
        val raw = CountWidgetActionActivity.legacyIntent(app, claim)
        val known = Intent(raw).apply {
            data = android.net.Uri.Builder().scheme("dayforge-count-known").authority(commandId)
                .appendPath(requireNotNull(raw.data?.authority)).build()
        }
        val lease = requireNotNull(countGate.tryAcquire(claim.identity.session(), claim.identity.habitUuid, claim.identity.habitId))
        val before = fixture.exactMain(); val start = size()
        try {
            launch(known)
            val rejected = awaitSeen { item -> after(start).any { it === item } && item.activity is CountWidgetActionActivity }
            assertEquals(known.data, rejected.systemBase?.data); ended(rejected)
            assertEquals(before, fixture.exactMain()); assertEquals(Triple(0, 1, 0), countImage())
            assertTrue(after(start).none { it.activity is MetricPromptActivity || it.activity is GoalCompletionActivity })
        } finally { countGate.release(lease) }
        withTimeout(5_000) { countGate.busyHabitIds.first { claim.identity.habitId !in it } }
        val retryStart = size(); launch(known)
        val resumed = awaitSeen { item -> after(retryStart).any { it === item } && item.activity is CountWidgetActionActivity }
        ended(resumed)
        val prompt = awaitSeen { item -> after(retryStart).any { it === item } && item.activity is MetricPromptActivity }
        ended(prompt)
        assertEquals(Triple(1, 1, 1), countImage())
        fixture.current { graph -> graph.database.openHelper.writableDatabase.query("SELECT commandId,delivery FROM count_command_status").use {
            assertTrue(it.moveToFirst()); assertEquals(commandId, it.getString(0)); assertEquals("CLOSED", it.getString(1)); assertFalse(it.moveToNext())
        } }
    }

    private suspend fun typed(countdown: Boolean): com.dayforge.data.repository.WidgetFactClaim = fixture.current { graph ->
        val id = graph.habitRepository.createHabit("Typed task migration", "", HabitType.COUNTING, 0,
            "#123456", HabitSchedule.Daily, targetValue = 10, isCountdown = countdown,
            appearance = com.dayforge.domain.model.ObjectAppearance(com.dayforge.domain.model.IconReference.Role("habit.custom"),
                "#123456", "theme"), completionPolicy = "recurring", creationAuthority = graph.nextObjectCreator.capture())
        graph.widgetFactReader.read(requireNotNull(graph.habitRepository.getHabitById(id))).claim
    }
    private suspend fun rejectedOldTyped(input: com.dayforge.data.repository.WidgetFactClaim, action: String) {
        val before = fixture.exactMain(); val requests = widgets.requestCount; val start = size()
        val expected = app.getString(com.dayforge.R.string.widget_count_action_refresh_required)
        val event = instrumentation.uiAutomation.executeAndWaitForEvent({ launch(input.actionIntent(app, action)) }, {
            it.eventType == android.view.accessibility.AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED &&
                it.text.any { part -> part.toString() == expected }
        }, 5_000)
        try { assertTrue(event.text.any { it.toString() == expected }) } finally { releaseEvent(event) }
        val activity = awaitSeen { item -> after(start).any { it === item } && item.activity is WidgetFactActionActivity }
        ended(activity) // Actual old callback and all lifecycle work finished before the oracle.
        assertEquals(requests + 1, widgets.requestCount)
        assertEquals(before, fixture.exactMain())
        assertTrue(after(start).none { it.activity is CountWidgetActionActivity || it.activity is MetricPromptActivity || it.activity is GoalCompletionActivity })
    }
    @Suppress("DEPRECATION") // Preserve API26-32 event pooling compatibility.
    private fun releaseEvent(event: android.view.accessibility.AccessibilityEvent) { event.recycle() }

    @Test(timeout = 150_000) fun oldTypedCountdownActivityRefusesButNewStableTaskCommitsItsOriginalIncrement() = runBlocking<Unit> {
        val input = typed(countdown = true)
        assertNotNull(input.countPolicy)
        rejectedOldTyped(input, "increment") // Original countdown-minus renderer maps to increment.
        assertEquals(Triple(0, 0, 0), countImage())
        completeFresh(CountWidgetActionActivity.typedIntent(app, input, "increment"))
        assertEquals(Triple(1, 1, 1), countImage())
        fixture.current { graph ->
            val view = graph.widgetFactReader.read(requireNotNull(graph.habitRepository.getHabitById(input.habitId)))
            assertEquals(1L, requireNotNull(view.count).todayQuantity)
            assertEquals(1, graph.database.syncOutboxDao().getAll().count { it.recordType == "completion" })
            val pending = com.dayforge.data.repository.CountCommandStore(graph.database, fixture.tokens, fixture.sessions).discover(8)
            assertTrue(pending.complete); assertNull(pending.continuation); assertTrue(pending.commands.isEmpty())
        }
    }

    @Test(timeout = 150_000) fun oldTypedUndoActivityRefusesButNewStableTaskUsesTheExactOriginalTarget() = runBlocking<Unit> {
        val input = typed(countdown = false)
        completeFresh(CountWidgetActionActivity.typedIntent(app, input, "increment"))
        assertEquals(Triple(1, 1, 1), countImage())
        val target = fixture.current { graph ->
            graph.widgetFactReader.read(requireNotNull(graph.habitRepository.getHabitById(input.habitId))).claim
        }
        val original = requireNotNull(target.completionUuid)
        rejectedOldTyped(target, "undo")
        assertEquals(Triple(1, 1, 1), countImage())
        withTimeout(5_000) { countGate.busyHabitIds.first { input.habitId !in it } }
        completeFresh(CountWidgetActionActivity.typedIntent(app, target, "undo"), expectPrompt = false)
        assertEquals(Triple(0, 2, 2), countImage())
        fixture.current { graph ->
            assertTrue(graph.database.completionDao().getAllCompletionsOnce().isEmpty())
            val sources = graph.database.syncOutboxDao().getAll().filter { it.recordType == "completion" }
            assertEquals(2, sources.size)
            val removed = sources.single { it.action == "delete" }
            assertEquals(original, removed.entityUuid)
            val origin = requireNotNull(graph.database.nextRequestDao().origin(com.dayforge.data.repository.NEXT_OPERATION, removed.operationId))
            assertEquals(com.dayforge.data.repository.NextRequestSql.sourceHash(removed), origin.sourceHash)
            val payload = kotlinx.serialization.json.Json.parseToJsonElement(origin.intentJson).jsonObject.getValue("payload").jsonObject
            assertEquals("revert", payload.getValue("event_type").jsonPrimitive.content)
            assertEquals(original, payload.getValue("reverts_event_uuid").jsonPrimitive.content)
            val view = graph.widgetFactReader.read(requireNotNull(graph.habitRepository.getHabitById(input.habitId)))
            assertEquals(0L, requireNotNull(view.count).todayQuantity)
            val pending = com.dayforge.data.repository.CountCommandStore(graph.database, fixture.tokens, fixture.sessions).discover(8)
            assertTrue(pending.complete); assertNull(pending.continuation); assertTrue(pending.commands.isEmpty())
        }
    }


    /** A visible existing test ComponentActivity supplies a legitimate foreground sender.
     * It is not a Launcher, AppWidgetHost, RemoteViews or product result consumer. */
    private suspend fun <T> withForegroundSender(block: suspend () -> T): T {
        var job: Job? = null
        val screen = ActivityScenario.launch(ComponentActivity::class.java)
        var sender: ComponentActivity? = null
        var senderTask: Int? = null
        return try {
            screen.onActivity {
                sender = it; senderTask = it.taskId
                job = requireNotNull(it.lifecycleScope.coroutineContext[Job])
            }
            block().also {
                screen.onActivity { returned ->
                    assertSame(sender, returned); assertEquals(requireNotNull(senderTask).toLong(), returned.taskId.toLong())
                    assertFalse(returned.isFinishing)
                    assertEquals(androidx.lifecycle.Lifecycle.State.RESUMED, returned.lifecycle.currentState)
                }
            }
        } finally {
            screen.close()
            job?.let { withTimeout(5_000) { it.join() } }
        }
    }
    /** Test-constructed real Android PendingIntent. The installed Glance1.1.0 ordinary
     * StartActivityIntentAction uses this request code/flags and preserves nonnull data.
     * This does NOT assert that Glance or a Launcher generated/delivered this object. */
    private fun systemPending(raw: Intent): PendingIntent = PendingIntent.getActivity(app, 0, raw,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE, null).also {
            assertEquals("com.dayforge.testbed", it.creatorPackage)
        }
    private fun send(pending: PendingIntent) = instrumentation.runOnMainSync { pending.send() }
    private suspend fun sentFresh(pending: PendingIntent): Seen {
        val initial = size(); send(pending)
        val known = awaitSeen { item -> after(initial).any { it === item } && item.activity is CountWidgetActionActivity &&
            item.intent.data?.scheme == "dayforge-count-known" }
        val raw = awaitSeen { item -> after(initial).any { it === item } && item.activity is CountWidgetActionActivity &&
            item.intent.data?.scheme == "dayforge-count-raw" }
        assertEquals(raw.task, known.task)
        assertEquals(known.intent.component, known.systemBase?.component)
        assertEquals(known.intent.data, known.systemBase?.data)
        ended(raw); ended(known)
        val prompt = awaitSeen { item -> after(initial).any { it === item } && item.activity is MetricPromptActivity }
        ended(prompt) // Actual no-links consumer, not an arbitrary Activity finish as T2.
        return known
    }
    private fun expectNotification(resource: Int, action: () -> Unit) {
        val expected = app.getString(resource)
        val event = instrumentation.uiAutomation.executeAndWaitForEvent({ action() }, {
            it.eventType == android.view.accessibility.AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED &&
                it.text.any { part -> part.toString() == expected }
        }, 5_000)
        try { assertTrue(event.text.any { it.toString() == expected }) } finally { releaseEvent(event) }
    }

    @Test(timeout = 150_000) fun realPendingIntentTwoExplicitSendsCreateIndependentCommandsAndPreserveTheFirstFacts() = runBlocking<Unit> {
        withForegroundSender {
            val claim = seed(); val raw = CountWidgetActionActivity.legacyIntent(app, claim)
            val pending = systemPending(raw)
            try {
                val first = sentFresh(pending)
                assertEquals(Triple(1, 1, 1), countImage())
                val firstImage = fixture.exactMain().contents.getValue("tables").jsonObject
                // Matching filter identity updates the same system token; it is not ONE_SHOT
                // or payload de-duplication. Each explicit send represents a new requested tap.
                assertEquals(pending, systemPending(Intent(raw)))
                val second = sentFresh(pending)
                assertNotEquals(first.task, second.task)
                assertNotEquals(first.intent.data?.authority, second.intent.data?.authority)
                assertEquals(Triple(2, 2, 2), countImage())
                val later = fixture.exactMain().contents.getValue("tables").jsonObject
                for (table in listOf("completions", "sync_outbox", "count_command_intents", "count_command_results")) {
                    val previous = firstImage.getValue(table).jsonArray
                    assertTrue("First immutable rows remain intact: $table", previous.isNotEmpty() &&
                        previous.all { it in later.getValue(table).jsonArray })
                }
                fixture.current { graph ->
                    val pendingCommands = com.dayforge.data.repository.CountCommandStore(graph.database, fixture.tokens, fixture.sessions).discover(8)
                    assertTrue(pendingCommands.complete); assertNull(pendingCommands.continuation); assertTrue(pendingCommands.commands.isEmpty())
                    assertEquals(2, graph.database.syncOutboxDao().getAll().count { it.recordType == "completion" })
                }
            } finally { pending.cancel() }
        }
    }

    @Test(timeout = 150_000) fun realPendingIntentBusyKnownIdentityNeverRequeuesButANewExplicitSendCanSucceed() = runBlocking<Unit> {
        withForegroundSender {
            val claim = seed(); val pending = systemPending(CountWidgetActionActivity.legacyIntent(app, claim))
            val lease = requireNotNull(countGate.tryAcquire(claim.identity.session(), claim.identity.habitUuid, claim.identity.habitId))
            val before = fixture.exactMain(); val start = size()
            try {
                val old = try {
                    expectNotification(com.dayforge.R.string.widget_count_busy_not_recorded) { send(pending) }
                    val known = awaitSeen { item -> after(start).any { it === item } && item.activity is CountWidgetActionActivity &&
                        item.intent.data?.scheme == "dayforge-count-known" }
                    val raw = awaitSeen { item -> after(start).any { it === item } && item.activity is CountWidgetActionActivity &&
                        item.intent.data?.scheme == "dayforge-count-raw" }
                    assertEquals(raw.task, known.task)
                    assertEquals(known.intent.component, known.systemBase?.component)
                    assertEquals(known.intent.data, known.systemBase?.data)
                    ended(raw); ended(known)
                    assertEquals(before, fixture.exactMain()); assertEquals(Triple(0, 0, 0), countImage())
                    assertTrue(after(start).none { it.activity is MetricPromptActivity || it.activity is GoalCompletionActivity })
                    known
                } finally { countGate.release(lease) }
                withTimeout(5_000) { countGate.busyHabitIds.first { claim.identity.habitId !in it } }
                val replayStart = size()
                // Component-level replay of the system's observed known ID, not a claim of
                // same-task recreation or OS redelivery. The consumed token cannot be Fresh.
                expectNotification(com.dayforge.R.string.widget_count_result_unresolved) {
                    launch(Intent(old.intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_MULTIPLE_TASK))
                }
                val replayed = awaitSeen { item -> after(replayStart).any { it === item } && item.activity is CountWidgetActionActivity }
                assertEquals(old.intent.data, replayed.systemBase?.data); ended(replayed)
                assertEquals(before, fixture.exactMain()); assertEquals(Triple(0, 0, 0), countImage())
                assertTrue(after(replayStart).none { it.activity is MetricPromptActivity || it.activity is GoalCompletionActivity })
                val fresh = sentFresh(pending)
                assertNotEquals(old.intent.data?.authority, fresh.intent.data?.authority)
                assertEquals(Triple(1, 1, 1), countImage())
                fixture.current { graph -> graph.database.openHelper.writableDatabase.query("SELECT commandId,delivery FROM count_command_status").use {
                    assertTrue(it.moveToFirst()); assertEquals(fresh.intent.data?.authority, it.getString(0))
                    assertEquals("CLOSED", it.getString(1)); assertFalse(it.moveToNext())
                } }
            } finally { pending.cancel() }
        }
    }
}
