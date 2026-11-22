package com.dayforge.ui

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.MotionEvent
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.Window
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inspector.WindowInspector
import androidx.annotation.RequiresApi
import androidx.core.view.WindowInsetsCompat
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.IdentityHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*

/** App semantics bracket a real composite screenshot; recent app-buffer commits are separate candidates.
 * A commit is not matched to the snapshot, nor proof that system IME or exiting surfaces disappeared.
 * Every capture remains a pixel-review candidate; no exact presentation time is inferred.
 * No marker is rendered and no production UI/authority or Compose idle state is changed. */
@RequiresApi(29)
@OptIn(ExperimentalComposeUiApi::class)
internal class CountJourneyWindows(private val directory: File) {
    data class Node(val id: Int, val text: List<String>, val editable: String?, val state: String?,
        val click: Boolean, val enabled: Boolean, val bounds: Rect, val descendants: Set<Int>, val fullyVisible: Boolean)
    data class Snapshot(val window: Int, val focused: Boolean, val nodes: List<Node>,
        val imeVisible: Boolean?, val imeBottom: Int?) {
        fun texts(): List<String> = nodes.flatMap { it.text }
        fun state(value: String) = nodes.any { it.state == value }
        fun text(value: String) = texts().contains(value)
        fun button(label: String, within: String? = null): Node? {
            fun matchesButton(node: Node) = node.click && nodes.any { it.id in node.descendants && label in it.text }
            val container = within?.let { value -> nodes.filter { node ->
                nodes.any { it.id in node.descendants && value in it.text } &&
                    nodes.any { it.id in node.descendants && matchesButton(it) }
            }.minByOrNull { it.bounds.width * it.bounds.height } }
            if (within != null && container == null) return null
            return nodes.filter { it.click && (container == null || it.id in container.descendants) &&
                nodes.any { child -> child.id in it.descendants && label in child.text } }
                .minByOrNull { it.bounds.width * it.bounds.height }
        }
    }
    data class Draw(val window: Int, val drawAt: Long, val committedAt: Long)
    data class Seen(val snapshot: Snapshot, val drawAt: Long?, val committedAt: Long?,
        val snapshotAt: Long, val captureStarted: Long, val observedAt: Long, val image: String) {
        val evidence get() = if (committedAt != null) "recent_app_buffer_candidate" else "composite_candidate_semantics_only"
        val appBufferCommitMatchesSnapshot = false
    }
    data class Input(val downTime: Long, val started: Long, val downReturned: Long, val upReturned: Long)
    data class Dispatch(val downTime: Long, val action: Int, val enteredAt: Long, val returnedAt: Long,
        val busyBefore: Boolean, val busyAfter: Boolean, val window: Int, val rawX: Float, val rawY: Float, val handled: Boolean)
    private val inputCallbacks = IdentityHashMap<Window, Pair<Window.Callback, Window.Callback>>()
    private val dispatched = arrayListOf<Dispatch>()
    /** Installed before setContent/first input. Transparent original Window dispatch, never
     * consumes an event or invokes a Compose callback; timestamps describe actual delivery. */
    fun observeInput(window: Window, busy: () -> Boolean) {
        check(android.os.Looper.myLooper() === android.os.Looper.getMainLooper())
        check(!inputCallbacks.containsKey(window))
        val original = requireNotNull(window.callback)
        val observed = object : Window.Callback by original {
            override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                val entered = SystemClock.elapsedRealtimeNanos(); val before = busy()
                val rawX = event.rawX; val rawY = event.rawY
                val handled = original.dispatchTouchEvent(event)
                dispatched += Dispatch(event.downTime, event.actionMasked, entered, SystemClock.elapsedRealtimeNanos(),
                    before, busy(), System.identityHashCode(window.decorView), rawX, rawY, handled)
                return handled
            }
        }
        inputCallbacks[window] = original to observed; window.callback = observed
    }
    suspend fun dispatches(input: Input): List<Dispatch> = withContext(Dispatchers.Main.immediate) {
        dispatched.filter { it.downTime == input.downTime && it.enteredAt >= input.started && it.enteredAt <= input.upReturned }.toList()
    }
    private val attached = IdentityHashMap<View, ViewTreeObserver.OnDrawListener>()
    private val draws = ArrayDeque<Draw>()
    private val lock = Any()
    @Volatile private var closed = false

    suspend fun attach() = withContext(Dispatchers.Main.immediate) {
        for (view in WindowInspector.getGlobalWindowViews()) if (!attached.containsKey(view) && view.isAttachedToWindow) {
            check(view.isHardwareAccelerated) { "Physical frame commit observation requires the original hardware window" }
            val windowId = System.identityHashCode(view)
            val listener = ViewTreeObserver.OnDrawListener {
                android.os.Trace.beginSection("DF.observer.onDraw")
                try {
                    if (!closed) {
                        val at = SystemClock.elapsedRealtimeNanos()
                        view.viewTreeObserver.registerFrameCommitCallback {
                            if (!closed) synchronized(lock) {
                                if (draws.size == 1024) draws.removeFirst()
                                draws.addLast(Draw(windowId, at, SystemClock.elapsedRealtimeNanos()))
                            }
                        }
                    }
                } finally { android.os.Trace.endSection() }
            }
            attached[view] = listener; view.viewTreeObserver.addOnDrawListener(listener)
        }
    }
    private fun ime(window: View): Pair<Boolean, Int>? = window.rootWindowInsets?.let { raw ->
        val insets = WindowInsetsCompat.toWindowInsetsCompat(raw, window)
        insets.isVisible(WindowInsetsCompat.Type.ime()) to insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
    }
    private data class ImeObservation(val window: Int, val visible: Boolean, val bottom: Int, val observedAt: Long)
    /** One real BACK pair when the original focused app window first reports an IME,
     * including a later visible observation after an initially hidden sample. No
     * dialog-dismiss callback, forced hide API, business callback or repeat injection.
     * Hidden samples cannot rule out a future IME show; callers retain their compound checks.
     * Publish the attempt before suspending/injecting so a timeout preserves what actually
     * happened. These observations never establish readiness or replace the original check. */
    suspend fun dismissImeIfVisible(onObservation: (JsonObject) -> Unit): JsonObject = withTimeout(45_000) {
        suspend fun currentIme(): ImeObservation = withContext(Dispatchers.Main.immediate) {
            val window = WindowInspector.getGlobalWindowViews().single { it.isAttachedToWindow && it.isShown && it.hasWindowFocus() }
            val state = requireNotNull(ime(window)) { "Actual focused-window IME insets required" }
            ImeObservation(System.identityHashCode(window), state.first, state.second, SystemClock.elapsedRealtimeNanos())
        }
        val attemptStarted = SystemClock.elapsedRealtimeNanos()
        var before: ImeObservation? = null; var after: ImeObservation? = null
        var began: Long? = null; var downRequested: Long? = null; var upRequested: Long? = null
        var downReturned: Long? = null; var upReturned: Long? = null
        var downAccepted: Boolean? = null; var upAccepted: Boolean? = null
        var hiddenObserved: Long? = null; var phase = "started"
        var failedPhase: String? = null; var failureClass: String? = null
        fun observe(next: String): JsonObject {
            phase = next
            return buildJsonObject {
                put("phase", phase); put("attempt_started_ns", attemptStarted)
                put("window", before?.window?.let(::JsonPrimitive) ?: JsonNull)
                put("visible_before", before?.visible?.let(::JsonPrimitive) ?: JsonNull)
                put("bottom_before", before?.bottom?.let(::JsonPrimitive) ?: JsonNull)
                put("before_observed_ns", before?.observedAt?.let(::JsonPrimitive) ?: JsonNull)
                put("started_ns", began?.let(::JsonPrimitive) ?: JsonNull)
                // Initial visibility does not determine a later physical input's outcome.
                // False means no request yet; a partial/rejected pair remains unknown.
                put("back_injected", when {
                    downAccepted == true && upAccepted == true -> JsonPrimitive(true)
                    before != null && downRequested == null -> JsonPrimitive(false)
                    else -> JsonNull
                })
                put("down_requested_ns", downRequested?.let(::JsonPrimitive) ?: JsonNull)
                put("down_returned_ns", downReturned?.let(::JsonPrimitive) ?: JsonNull)
                put("down_accepted", downAccepted?.let(::JsonPrimitive) ?: JsonNull)
                put("up_requested_ns", upRequested?.let(::JsonPrimitive) ?: JsonNull)
                put("up_returned_ns", upReturned?.let(::JsonPrimitive) ?: JsonNull)
                put("up_accepted", upAccepted?.let(::JsonPrimitive) ?: JsonNull)
                put("window_after", after?.window?.let(::JsonPrimitive) ?: JsonNull)
                put("visible_after", after?.visible?.let(::JsonPrimitive) ?: JsonNull)
                put("bottom_after", after?.bottom?.let(::JsonPrimitive) ?: JsonNull)
                put("after_observed_ns", after?.observedAt?.let(::JsonPrimitive) ?: JsonNull)
                put("hidden_observed_ns", hiddenObserved?.let(::JsonPrimitive) ?: JsonNull)
                put("hidden_condition_checked", hiddenObserved != null)
                put("visibility_pass_claim", false)
                put("failed_phase", failedPhase?.let(::JsonPrimitive) ?: JsonNull)
                put("error_class", failureClass?.let(::JsonPrimitive) ?: JsonNull)
            }.also(onObservation)
        }
        try {
            observe("before_observation")
            val initial = currentIme(); before = initial
            began = SystemClock.elapsedRealtimeNanos()
            observe("before_back_decision")
            fun backIfVisible(current: ImeObservation) {
                if (!current.visible || downRequested != null) return
                check(current.window == initial.window) { "IME back must preserve the original prompt window" }
                val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
                val uptime = SystemClock.uptimeMillis()
                downRequested = SystemClock.elapsedRealtimeNanos(); observe("back_down_requested")
                val acceptedDown = automation.injectInputEvent(KeyEvent(uptime, uptime, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0), true)
                downReturned = SystemClock.elapsedRealtimeNanos(); downAccepted = acceptedDown
                observe("back_down_returned"); check(acceptedDown)
                upRequested = SystemClock.elapsedRealtimeNanos(); observe("back_up_requested")
                val acceptedUp = automation.injectInputEvent(KeyEvent(uptime, uptime + 1, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK, 0), true)
                upReturned = SystemClock.elapsedRealtimeNanos(); upAccepted = acceptedUp
                observe("back_up_returned"); check(acceptedUp)
            }
            backIfVisible(initial)
            var current = currentIme(); after = current; observe("waiting_hidden")
            while (current.visible || current.bottom != 0) {
                backIfVisible(current)
                delay(16)
                val next = currentIme()
                val changed = next.window != current.window || next.visible != current.visible || next.bottom != current.bottom
                current = next; after = current
                if (changed) observe("waiting_hidden") // Latest value only; no per-frame history or logging.
            }
            check(current.window == initial.window) { "IME back must preserve the original prompt window" }
            hiddenObserved = SystemClock.elapsedRealtimeNanos()
            observe("hidden_observed")
        } catch (error: Throwable) {
            failedPhase = phase; failureClass = error.javaClass.name
            try { observe("failed") } catch (diagnostic: Throwable) { error.addSuppressed(diagnostic) }
            throw error
        }
    }
    private fun snapshot(window: View): Snapshot? {
        android.os.Trace.beginSection("DF.observer.snapshot")
        try {
            if (!window.isAttachedToWindow || !window.isShown) return null
            val roots = arrayListOf<ViewRootForTest>()
            fun visit(view: View) {
                if (view is ViewRootForTest) roots += view
                if (view is ViewGroup) for (index in 0 until view.childCount) visit(view.getChildAt(index))
            }
            visit(window)
            if (roots.isEmpty()) return null
            val viewport = android.graphics.Rect()
            if (!window.getLocalVisibleRect(viewport)) return null
            val windowScreen = IntArray(2); window.getLocationOnScreen(windowScreen)
            viewport.offset(windowScreen[0], windowScreen[1]) // Now the same screen coordinates as every node.
            val display = android.graphics.Rect(); window.getWindowVisibleDisplayFrame(display)
            if (!viewport.intersect(display)) return null // Exclude real system/IME-obscured display area.
            val ime = ime(window)
            val nodes = arrayListOf<Node>()
            for (root in roots) {
                val screen = IntArray(2); val local = IntArray(2)
                root.view.getLocationOnScreen(screen); root.view.getLocationInWindow(local)
                fun collect(node: SemanticsNode, disabled: Boolean): Set<Int> {
                    val config = node.config
                    val off = disabled || config.contains(SemanticsProperties.Disabled)
                    val children = node.children.flatMap { collect(it, off) }.toSet() + node.id
                    val bounds = node.boundsInWindow.translate((screen[0] - local[0]).toFloat(), (screen[1] - local[1]).toFloat())
                    val visible = Rect(maxOf(bounds.left, viewport.left.toFloat()), maxOf(bounds.top, viewport.top.toFloat()),
                        minOf(bounds.right, viewport.right.toFloat()), minOf(bounds.bottom, viewport.bottom.toFloat()))
                    if (!config.contains(SemanticsProperties.InvisibleToUser) && visible.width > 0 && visible.height > 0) {
                        nodes += Node(node.id, config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text },
                            config.getOrNull(SemanticsProperties.EditableText)?.text,
                            config.getOrNull(SemanticsProperties.StateDescription),
                            config.contains(SemanticsActions.OnClick), !off, visible, children, visible == bounds)
                    }
                    return children
                }
                collect(root.semanticsOwner.unmergedRootSemanticsNode, false)
            }
            return Snapshot(System.identityHashCode(window), window.hasWindowFocus(), nodes.sortedBy { it.id }, ime?.first, ime?.second)
        } finally { android.os.Trace.endSection() }
    }
    private suspend fun current(window: Int): Snapshot? = withContext(Dispatchers.Main.immediate) {
        WindowInspector.getGlobalWindowViews().firstOrNull { System.identityHashCode(it) == window }?.let(::snapshot)
    }
    /** Stable app semantics bracket a real full-display screenshot candidate. A recent
     * hardware commit belongs to the same window but is not matched to the snapshot. Equal before/after semantics,
     * focus and IME insets cannot certify the compositor's system or exiting-window layers.
     * A static Dialog needs no forced redraw; pixel qualification remains independent. */
    suspend fun seen(label: String, after: Long, predicate: (Snapshot) -> Boolean): Seen = withTimeout(45_000) {
        require(label.matches(Regex("[a-z_]+")))
        while (true) {
            attach()
            val sampled = withContext(Dispatchers.Main.immediate) {
                val value = WindowInspector.getGlobalWindowViews().mapNotNull(::snapshot)
                    .singleOrNull { it.focused && predicate(it) }
                SystemClock.elapsedRealtimeNanos() to value
            }
            val value = sampled.second
            if (value != null && sampled.first >= after) {
                val recentDraw = synchronized(lock) { draws.lastOrNull {
                    it.drawAt >= after && it.committedAt >= after && it.committedAt <= sampled.first && it.window == value.window
                } }
                val started = SystemClock.elapsedRealtimeNanos()
                val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                val ended = SystemClock.elapsedRealtimeNanos()
                if (bitmap != null) {
                    try {
                        if (current(value.window) == value) {
                            check(directory.isDirectory || directory.mkdirs())
                            val file = File(directory, "$label.png")
                            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                            return@withTimeout Seen(value, recentDraw?.drawAt, recentDraw?.committedAt,
                                sampled.first, started, ended, file.name)
                        }
                    } finally { bitmap.recycle() }
                }
            }
            // No SQL, permission probe, synthetic invalidation or forced layout in this observer.
            delay(16)
        }
        @Suppress("UNREACHABLE_CODE") error("No qualified app frame")
    }
    /** Same RequestFocus/SetText semantics used by performTextReplacement, without a
     * Compose test rule replacing the Activity's real platform frame clock. */
    suspend fun replaceText(label: String, value: String) = withTimeout(45_000) {
        fun currentTarget(): SemanticsNode {
            val targets = arrayListOf<Pair<View, SemanticsNode>>()
            fun visit(window: View, view: View) {
                if (view is ViewRootForTest) {
                    fun collect(node: SemanticsNode) {
                        if (node.config.contains(SemanticsActions.SetText) &&
                            node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == label }) targets += window to node
                        node.children.forEach(::collect)
                    }
                    collect(view.semanticsOwner.rootSemanticsNode) // Original test API uses the merged tree.
                }
                if (view is ViewGroup) for (index in 0 until view.childCount) visit(window, view.getChildAt(index))
            }
            WindowInspector.getGlobalWindowViews().filter { it.isAttachedToWindow && it.isShown && it.hasWindowFocus() }
                .forEach { visit(it, it) }
            val (window, node) = targets.single()
            val visible = requireNotNull(snapshot(window)?.nodes?.singleOrNull { it.id == node.id })
            check(visible.enabled && visible.editable != null)
            check(!node.config.contains(SemanticsProperties.Disabled))
            return node
        }
        withContext(Dispatchers.Main.immediate) {
            val node = currentTarget()
            if (node.config.getOrNull(SemanticsProperties.Focused) != true)
                check(requireNotNull(node.config.getOrNull(SemanticsActions.RequestFocus)?.action).invoke())
            check(requireNotNull(node.config.getOrNull(SemanticsActions.SetText)?.action).invoke(AnnotatedString(value)))
        }
        while (true) {
            val actual = withContext(Dispatchers.Main.immediate) {
                currentTarget().config.getOrNull(SemanticsProperties.EditableText)?.text
            }
            if (actual == value) return@withTimeout
            delay(16) // Original platform frames; never a Compose test-clock advance.
        }
    }
    /** Failure evidence has no ready predicate and never upgrades a timeout into visibility.
     * It samples only current views/model; no SQL/retry/forced draw/clock advancement occurs. */
    suspend fun failureEvidence(stage: String, model: JsonObject) {
        val sampled = withContext(Dispatchers.Main.immediate) {
            SystemClock.elapsedRealtimeNanos() to JsonArray(WindowInspector.getGlobalWindowViews().map { view ->
                val state = snapshot(view)
                val location = IntArray(2); view.getLocationOnScreen(location)
                buildJsonObject {
                    put("window", System.identityHashCode(view)); put("view_class", view.javaClass.name)
                    put("attached", view.isAttachedToWindow); put("shown", view.isShown); put("focused", view.hasWindowFocus())
                    put("width", view.width); put("height", view.height); put("screen_x", location[0]); put("screen_y", location[1])
                    put("snapshot_available", state != null)
                    put("ime_visible", ime(view)?.first?.let(::JsonPrimitive) ?: JsonNull)
                    put("ime_bottom", ime(view)?.second?.let(::JsonPrimitive) ?: JsonNull)
                    val display = android.graphics.Rect(); view.getWindowVisibleDisplayFrame(display)
                    put("visible_display_frame", JsonArray(listOf(display.left, display.top, display.right, display.bottom).map(::JsonPrimitive)))
                    put("nodes", state?.let { JsonArray(it.nodes.map { node -> buildJsonObject {
                        put("id", node.id); put("text", JsonArray(node.text.map(::JsonPrimitive)))
                        put("editable", node.editable?.let(::JsonPrimitive) ?: JsonNull)
                        put("state", node.state?.let(::JsonPrimitive) ?: JsonNull)
                        put("click", node.click); put("enabled", node.enabled); put("fully_visible", node.fullyVisible)
                        put("bounds_screen", JsonArray(listOf(node.bounds.left, node.bounds.top, node.bounds.right, node.bounds.bottom).map(::JsonPrimitive)))
                        put("descendants", JsonArray(node.descendants.sorted().map(::JsonPrimitive)))
                    } }) } ?: JsonNull)
                }
            })
        }
        val began = SystemClock.elapsedRealtimeNanos()
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val ended = SystemClock.elapsedRealtimeNanos()
        if (bitmap != null) try {
            File(directory, "failure.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
        File(directory, "failure.json").writeText(buildJsonObject {
            put("kind", "failure_diagnostic_not_ready"); put("stage", stage); put("ready_or_visibility_pass", false)
            put("model", model); put("window_snapshot_ns", sampled.first); put("windows", sampled.second)
            put("screenshot_started_ns", began); put("screenshot_ended_ns", ended)
            put("image", if (bitmap != null) JsonPrimitive("failure.png") else JsonNull)
            put("draw_samples", synchronized(lock) { draws.size })
        }.toString())
    }
    private fun dialogOf(view: View): Window? {
        if (view is DialogWindowProvider) return view.window
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            dialogOf(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    @Suppress("DEPRECATION")
    private fun systemAllowsTouch(windows: List<AccessibilityWindowInfo>, point: Offset): Boolean = try {
        fun contains(window: AccessibilityWindowInfo): Boolean {
            val x = point.x.toInt(); val y = point.y.toInt()
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                return android.graphics.Region().also(window::getRegionInScreen).contains(x, y)
            }
            return android.graphics.Rect().also(window::getBoundsInScreen).contains(x, y)
        }
        val appWindow = windows.singleOrNull {
            it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isFocused && contains(it)
        }
        appWindow != null && windows.none {
            (it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD || it.layer > appWindow.layer) && contains(it)
        }
    } finally {
        windows.forEach { it.recycle() }
    }

    /** Collect system hit-test evidence after the screenshot, then resample the original dialog
     * before the only real DOWN/UP. The caller supplies the same bounded wait as the screenshot. */
    suspend fun tapCurrentDialog(windowId: Int, button: (Snapshot) -> Node?, witness: (JsonObject) -> Unit): Input {
        val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
        val flag = android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        val addFlag = ui.serviceInfo.flags and flag == 0
        val dialog = withContext(Dispatchers.Main.immediate) {
            val root = WindowInspector.getGlobalWindowViews().single { System.identityHashCode(it) == windowId }
            checkNotNull(dialogOf(root)).also {
                check(it.decorView === root)
                if (!inputCallbacks.containsKey(it)) observeInput(it) { false }
            }
        }
        fun pick(): Node? = snapshot(dialog.decorView)?.let(button)
        var selected: Node? = null
        var pickedAt: Long? = null
        var attempt: Input? = null
        var dispatch: List<Dispatch> = emptyList()
        try {
            if (addFlag) ui.serviceInfo = ui.serviceInfo.apply { flags = flags or flag }
            while (true) {
                val candidate = withContext(Dispatchers.Main.immediate) { pick() }
                if (candidate != null && systemAllowsTouch(ui.windows, candidate.bounds.center)) {
                    val fresh = withContext(Dispatchers.Main.immediate) {
                        pick()?.takeIf { it.id == candidate.id && it.bounds == candidate.bounds }?.also {
                            pickedAt = SystemClock.elapsedRealtimeNanos()
                        }
                    }
                    if (fresh != null) {
                        selected = fresh
                        val input = tap(fresh)
                        attempt = input
                        dispatch = dispatches(input).filter { it.window == windowId }
                        check(dispatch.map { it.action } == listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP) &&
                            dispatch.all { it.handled && fresh.bounds.contains(Offset(it.rawX, it.rawY)) }) {
                            "The single Record touch was not handled in the original dialog button bounds"
                        }
                        return input
                    }
                }
                kotlinx.coroutines.android.awaitFrame()
            }
        } finally {
            try {
                if (addFlag) ui.serviceInfo = ui.serviceInfo.apply { flags = flags and flag.inv() }
            } finally {
                witness(buildJsonObject {
                    put("window", windowId); put("fresh_pick_ns", pickedAt?.let(::JsonPrimitive) ?: JsonNull)
                    put("bounds", selected?.bounds?.let { b -> JsonArray(listOf(b.left, b.top, b.right, b.bottom).map(::JsonPrimitive)) } ?: JsonNull)
                    put("input_start_ns", attempt?.started?.let(::JsonPrimitive) ?: JsonNull)
                    put("up_returned_ns", attempt?.upReturned?.let(::JsonPrimitive) ?: JsonNull)
                    put("down_time_ms", attempt?.downTime?.let(::JsonPrimitive) ?: JsonNull)
                    put("dispatch", JsonArray(dispatch.map { event -> buildJsonObject {
                        put("action", event.action); put("window", event.window); put("down_time_ms", event.downTime)
                        put("raw_x", event.rawX); put("raw_y", event.rawY); put("handled", event.handled)
                        put("entered_ns", event.enteredAt); put("returned_ns", event.returnedAt)
                    } }))
                })
            }
        }
    }

    fun tap(node: Node): Input {
        check(node.click && node.enabled)
        return touch(node.bounds)
    }
    /** A second actual input at the original coordinates may be ignored by the disabled
     * button. Do not invoke its callback or simulate a successful queued command. */
    fun touch(bounds: Rect): Input {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val uptime = SystemClock.uptimeMillis(); val started = SystemClock.elapsedRealtimeNanos()
        val down = MotionEvent.obtain(uptime, uptime, MotionEvent.ACTION_DOWN, bounds.center.x, bounds.center.y, 0)
        val up = MotionEvent.obtain(uptime, uptime + 1, MotionEvent.ACTION_UP, bounds.center.x, bounds.center.y, 0)
        try {
            check(automation.injectInputEvent(down, true)); val returned = SystemClock.elapsedRealtimeNanos()
            check(automation.injectInputEvent(up, true))
            return Input(uptime, started, returned, SystemClock.elapsedRealtimeNanos())
        } finally { down.recycle(); up.recycle() }
    }
    suspend fun close() = withContext(Dispatchers.Main.immediate) {
        closed = true
        attached.forEach { (view, listener) -> if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnDrawListener(listener) }
        attached.clear()
        inputCallbacks.forEach { (window, callbacks) ->
            if (window.callback === callbacks.second) window.callback = callbacks.first
        }
        inputCallbacks.clear()
    }
}
