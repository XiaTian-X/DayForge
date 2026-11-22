package com.dayforge.ui

import android.os.SystemClock
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real Activity, editor, system IME and physical BACK injection. No synthetic Insets or
 * Compose test clock. The callback gates the real show after the first hidden sample. */
@SdkSuppress(minSdkVersion = 29)
@RunWith(AndroidJUnit4::class)
class CountJourneyImePhysicalTest {
    private lateinit var scenario: ActivityScenario<ComponentActivity>
    private lateinit var activity: ComponentActivity
    private lateinit var editor: EditText
    private lateinit var observer: CountJourneyWindows
    private val observations = arrayListOf<JsonObject>()

    @Before
    fun setUp() {
        scenario = ActivityScenario.launch(ComponentActivity::class.java)
        scenario.onActivity { screen ->
            activity = screen
            WindowCompat.setDecorFitsSystemWindows(screen.window, false)
            screen.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
            editor = EditText(screen).apply {
                showSoftInputOnFocus = false
                setSingleLine(true)
                setText("41.125")
            }
            screen.setContentView(editor)
            check(editor.requestFocus())
        }
        observer = CountJourneyWindows(File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "ime-regression"))
        runBlocking { awaitIme(false) }
    }

    @After
    fun tearDown() {
        try {
            if (::observer.isInitialized) runBlocking { observer.close() }
        } finally {
            if (::scenario.isInitialized) scenario.close()
        }
    }

    @Test
    fun imeVisibleAfterFirstHiddenObservationGetsOneBackPair() = runBlocking {
        var showArranged = false
        val result = observer.dismissImeIfVisible { observation ->
            observations += observation
            if (observation.getValue("phase").jsonPrimitive.content == "before_back_decision") {
                assertFalse(showArranged)
                assertFalse(observation.getValue("visible_before").jsonPrimitive.boolean)
                assertEquals(0, observation.getValue("bottom_before").jsonPrimitive.int)
                showArranged = true
                // Block only this test's callback until actual platform Insets report the show.
                // The production helper still obtains both samples itself, from the real window.
                runBlocking { showImeAndAwait() }
            }
        }
        assertTrue(showArranged)
        assertFalse(result.getValue("visible_before").jsonPrimitive.boolean)
        assertTrue(observations.any { it["visible_after"]?.jsonPrimitive?.content == "true" })
        assertOneAcceptedBackAndPreservedEditor(result)
    }

    @Test
    fun initiallyVisibleImeGetsOneBackPair() = runBlocking {
        showImeAndAwait()
        val result = observer.dismissImeIfVisible { observations += it }
        assertTrue(result.getValue("visible_before").jsonPrimitive.boolean)
        assertOneAcceptedBackAndPreservedEditor(result)
    }

    private suspend fun showImeAndAwait() {
        withTimeout(45_000) {
            while (!withContext(Dispatchers.Main.immediate) {
                requireNotNull(activity.getSystemService(InputMethodManager::class.java)).isActive(editor)
            }) delay(16)
        }
        withContext(Dispatchers.Main.immediate) {
            activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED)
            editor.showSoftInputOnFocus = true
            check(editor.requestFocus())
            check(requireNotNull(activity.getSystemService(InputMethodManager::class.java))
                .showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT))
        }
        awaitIme(true)
    }

    private suspend fun awaitIme(visible: Boolean): Int = withTimeout(45_000) {
        while (true) {
            val matched = withContext(Dispatchers.Main.immediate) {
                val window = activity.window.decorView
                val raw = window.rootWindowInsets
                if (!window.isAttachedToWindow || !window.isShown || !window.hasWindowFocus() || raw == null) null
                else {
                    val insets = WindowInsetsCompat.toWindowInsetsCompat(raw, window)
                    val actual = insets.isVisible(WindowInsetsCompat.Type.ime())
                    val bottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
                    if (actual == visible && (if (visible) bottom > 0 else bottom == 0))
                        System.identityHashCode(window) else null
                }
            }
            if (matched != null) return@withTimeout matched
            delay(16)
        }
        @Suppress("UNREACHABLE_CODE") error("No actual IME observation")
    }

    private suspend fun assertOneAcceptedBackAndPreservedEditor(result: JsonObject) {
        assertEquals(listOf("back_down_requested", "back_down_returned", "back_up_requested", "back_up_returned"),
            observations.map { it.getValue("phase").jsonPrimitive.content }.filter { it.startsWith("back_") })
        assertTrue(result.getValue("back_injected").jsonPrimitive.boolean)
        assertTrue(result.getValue("down_accepted").jsonPrimitive.boolean)
        assertTrue(result.getValue("up_accepted").jsonPrimitive.boolean)
        val ordered = listOf("down_requested_ns", "down_returned_ns", "up_requested_ns", "up_returned_ns", "hidden_observed_ns")
            .map { result.getValue(it).jsonPrimitive.long }
        assertTrue(ordered.zipWithNext().all { (before, after) -> before <= after })
        assertTrue(ordered.last() <= SystemClock.elapsedRealtimeNanos())
        assertEquals("hidden_observed", result.getValue("phase").jsonPrimitive.content)
        assertFalse(result.getValue("visible_after").jsonPrimitive.boolean)
        assertEquals(0, result.getValue("bottom_after").jsonPrimitive.int)
        assertTrue(result.getValue("hidden_condition_checked").jsonPrimitive.boolean)
        assertFalse(result.getValue("visibility_pass_claim").jsonPrimitive.boolean)
        val window = awaitIme(false)
        assertEquals(window, result.getValue("window").jsonPrimitive.int)
        assertEquals(window, result.getValue("window_after").jsonPrimitive.int)
        withContext(Dispatchers.Main.immediate) {
            assertFalse(activity.isFinishing)
            assertFalse(activity.isDestroyed)
            assertTrue(editor.hasFocus())
            assertEquals("41.125", editor.text.toString())
        }
    }
}
