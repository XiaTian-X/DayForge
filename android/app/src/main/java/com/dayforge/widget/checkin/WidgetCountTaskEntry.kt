package com.dayforge.widget.checkin

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import com.dayforge.data.repository.LegacyWidgetFactClaim
import com.dayforge.data.repository.WidgetFactClaim
import java.security.MessageDigest
import java.util.UUID

/** Task identity protocol only. No database, permission, timing input or business admission
 * is captured here. Actual writers still authenticate the original immutable ordinary claim.
 * Device tests must validate self-CLEAR_TOP and actual baseIntent before this is admissible.
 */
internal object WidgetCountTaskEntry {
    private const val TYPED = "com.dayforge.widget.count.typed_input"
    private const val LEGACY = "com.dayforge.widget.count.legacy_input"
    private const val FRESH = "com.dayforge.widget.count.fresh_handoff"
    private const val RAW = "dayforge-count-raw"
    private const val KNOWN = "dayforge-count-known"

    sealed interface Input {
        val action: String
        val payload: String
        val kind: String
        data class Typed(val claim: WidgetFactClaim, override val action: String) : Input {
            override val payload = claim.encode()
            override val kind = "typed"
            init { require(claim.countPolicy != null && action in setOf("increment", "undo")) }
        }
        data class Legacy(val claim: LegacyWidgetFactClaim) : Input {
            override val action = requireNotNull(claim.action)
            override val payload = claim.encode()
            override val kind = "legacy"
            init { require(action in setOf("increment", "decrement")) }
        }
    }
    sealed interface Result {
        data object HandedOff : Result
        data class Fresh(val commandId: String, val input: Input) : Result
        data class Restore(val commandId: String, val input: Input) : Result
    }
    /** Process-local one-use delivery capability, not authority. A consumed/binder-proxy/
     * missing token can never produce another Fresh result after recreation or process death.
     * It lives only in the ordinary Intent; no retained global table or payload de-duplication.
     */
    private class FreshHandoff(val commandId: String, val hash: String, val taskId: Int) : Binder() {
        private var consumed = false
        @Synchronized fun consume(id: String, digest: String, task: Int): Boolean {
            if (consumed || id != commandId || digest != hash || task != taskId) return false
            consumed = true
            return true
        }
    }

    fun rawIntent(context: Context, activity: Class<out Activity>, input: Input): Intent =
        attach(Intent(context, activity), input).apply {
            data = Uri.Builder().scheme(RAW).authority(hash(input)).build()
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_MULTIPLE_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
        }

    /** Must execute synchronously from onCreate, before any Gate, DB read or acceptance.
     * HandedOff does not call finish(): the same-component standard CLEAR_TOP operation must
     * replace the old record and task base atomically on the system side. */
    fun enter(activity: Activity): Result {
        val intent = activity.intent
        require(intent.component == activity.componentName && activity.isTaskRoot) { "COUNT_TASK_ROOT_REQUIRED" }
        val input = read(intent)
        val digest = hash(input)
        val uri = requireNotNull(intent.data) { "COUNT_TASK_IDENTITY_REQUIRED" }
        require(uri.query == null && uri.fragment == null) { "COUNT_TASK_IDENTITY_CHANGED" }
        if (uri.scheme == RAW) {
            require(uri.authority == digest && uri.pathSegments.isEmpty()) { "COUNT_TASK_INPUT_CHANGED" }
            val commandId = UUID.randomUUID().toString()
            val token = FreshHandoff(commandId, digest, activity.taskId)
            val next = attach(Intent().setComponent(activity.componentName), input).apply {
                data = knownUri(commandId, digest)
                putExtras(Bundle().apply { putBinder(FRESH, token) })
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
            }
            activity.startActivity(next)
            return Result.HandedOff
        }
        require(uri.scheme == KNOWN && uri.pathSegments == listOf(digest)) { "COUNT_TASK_INPUT_CHANGED" }
        val commandId = requireNotNull(uri.authority)
        require(com.dayforge.domain.model.isContractUuid(commandId)) { "COUNT_TASK_COMMAND_ID_INVALID" }
        require(uri == knownUri(commandId, digest)) { "COUNT_TASK_IDENTITY_CHANGED" }
        // TaskInfo.baseIntent may omit extras. The stable component/data, unlike setIntent's
        // client-side Bundle, must already be the effective system task identity here.
        val manager = activity.getSystemService(ActivityManager::class.java)
        val task = manager.appTasks.map { it.taskInfo }.singleOrNull { taskId(it) == activity.taskId }
            ?: error("COUNT_TASK_SYSTEM_IDENTITY_UNAVAILABLE")
        require(task.baseIntent.component == activity.componentName && task.baseIntent.data == uri) {
            "COUNT_TASK_SYSTEM_IDENTITY_NOT_REPLACED"
        }
        val token = intent.extras?.getBinder(FRESH) as? FreshHandoff
        return if (token?.consume(commandId, digest, activity.taskId) == true) Result.Fresh(commandId, input)
            else Result.Restore(commandId, input)
    }

    @Suppress("DEPRECATION")
    private fun taskId(info: ActivityManager.RecentTaskInfo): Int =
        if (android.os.Build.VERSION.SDK_INT >= 29) info.taskId else info.id

    private fun read(intent: Intent): Input {
        val typed = intent.getStringExtra(TYPED)
        val legacy = intent.getStringExtra(LEGACY)
        require((typed == null) != (legacy == null)) { "COUNT_TASK_ORIGINAL_INPUT_REQUIRED" }
        return if (typed != null) Input.Typed(WidgetFactClaim.decode(typed), requireNotNull(intent.action))
        else Input.Legacy(LegacyWidgetFactClaim.decode(requireNotNull(legacy))).also {
            require(it.action == intent.action) { "COUNT_TASK_INPUT_CHANGED" }
        }
    }
    private fun attach(intent: Intent, input: Input): Intent = intent.apply {
        action = input.action
        putExtra(if (input is Input.Typed) TYPED else LEGACY, input.payload)
    }
    private fun knownUri(commandId: String, hash: String) = Uri.Builder().scheme(KNOWN).authority(commandId).appendPath(hash).build()
    private fun hash(input: Input): String {
        val digest = MessageDigest.getInstance("SHA-256")
        // Separate UTF-8 fields by their exact byte length, not an ambiguous concatenation.
        for (field in listOf("DayForge/widget-count-task/v1", input.kind, input.action, input.payload)) {
            val bytes = field.toByteArray(Charsets.UTF_8)
            digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.size).array()); digest.update(bytes)
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
