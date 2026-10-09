package com.dayforge.data.repository

import android.annotation.SuppressLint
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import androidx.room.withTransaction
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.TokenManager
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.model.HabitType
import com.dayforge.domain.service.AccountSessionCoordinator
import com.dayforge.widget.WidgetRefreshScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext

internal data class WidgetConfigurationSpec(val type: HabitType, val preferences: String,
    val prefix: String, val receiver: Class<*>)

internal class WidgetConfigurationSnapshot(val spec: WidgetConfigurationSpec, val widgetId: Int,
    val habits: List<HabitEntity>, val publication: WidgetDisplayPublisher.Publication)

internal class WidgetConfigurationExpired : IllegalStateException("WIDGET_CONFIGURATION_EXPIRED")

/** Local display binding only. No habit/fact/outbox writes, or authority serialized to preferences. */
@Singleton
class WidgetConfigurationRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val database: HabitDatabase,
    private val tokens: TokenManager,
    private val publisher: WidgetDisplayPublisher,
    private val sessions: AccountSessionCoordinator
) {
    internal val changes = combine(tokens.factAccessChanges, tokens.iconAccessChanges) { _, _ -> Unit }
    // Accessed only within Publication's shared account lock. Prevent same-value binding ABA:
    // an old failing configuration must not undo a later configuration of the same widget.
    private val heads = mutableMapOf<Pair<String, Int>, Any>()

    internal suspend fun load(spec: WidgetConfigurationSpec, widgetId: Int): WidgetConfigurationSnapshot {
        require(widgetId != AppWidgetManager.INVALID_APPWIDGET_ID)
        val publication = publisher.capturePublication() ?: throw WidgetConfigurationExpired()
        try {
            val habits = database.habitDao().getVisibleHabitsOnce()
            val configured = withContext(Dispatchers.IO) { configuredIds(spec) }
            val snapshot = WidgetConfigurationSnapshot(spec, widgetId,
                habits.filter { it.habitType == spec.type && it.id !in configured }, publication)
            if (!isCurrent(snapshot)) throw WidgetConfigurationExpired()
            return snapshot
        } catch (error: Exception) {
            if (error is CancellationException || error is WidgetConfigurationExpired) throw error
            // Even an initial source error belongs to its captured page/account. It must
            // expire, not leave a retry that silently opens the replacement account.
            if (!publication { }) throw WidgetConfigurationExpired().also { it.addSuppressed(error) }
            throw error
        }
    }

    internal suspend fun isCurrent(snapshot: WidgetConfigurationSnapshot): Boolean = snapshot.publication { }

    /** Source rendering may use coordinated readers, so it must run OUTSIDE the binding lock. */
    internal suspend fun configure(snapshot: WidgetConfigurationSnapshot, expected: HabitEntity,
        display: suspend (WidgetDisplayPublisher.Publication) -> Unit) {
        require(snapshot.habits.any { it.id == expected.id && it.uuid == expected.uuid })
        val spec = snapshot.spec
        val key = spec.preferences to snapshot.widgetId
        val stamp = Any()
        var started = false
        var completed = false
        var previous: Long? = null
        var failure: Throwable? = null
        try {
            if (!snapshot.publication {
                requireSelection(spec, expected)
                withContext(Dispatchers.IO) {
                    check(expected.id !in configuredIds(spec, snapshot.widgetId)) { "WIDGET_ALREADY_CONFIGURED" }
                    val preferences = context.getSharedPreferences(spec.preferences, Context.MODE_PRIVATE)
                    val name = spec.prefix + snapshot.widgetId
                    previous = if (preferences.contains(name)) preferences.getLong(name, -1) else null
                    currentCoroutineContext().ensureActive()
                    heads[key] = stamp
                    started = true
                    // Assign ownership before commit: false/throw can still change the memory value.
                    check(writeBinding(spec, snapshot.widgetId, expected.id)) { "WIDGET_BINDING_NOT_PERSISTED" }
                }
            }) throw WidgetConfigurationExpired()
            fun ownsBinding(): Boolean = heads[key] === stamp &&
                context.getSharedPreferences(spec.preferences, Context.MODE_PRIVATE)
                    .getLong(spec.prefix + snapshot.widgetId, -1) == expected.id
            // Account validity is not enough: another page can replace this same widget's
            // binding within one account. Gate both normal display and typed writer reads.
            val selectedPublication = WidgetDisplayPublisher.Publication({ render ->
                var written = false
                val current = snapshot.publication { if (ownsBinding()) { render(); written = true } }
                current && written
            }, { snapshot.publication.isCurrentWhileAccountLocked() && ownsBinding() })
            display(selectedPublication)
            if (!snapshot.publication {
                requireSelection(spec, expected)
                currentCoroutineContext().ensureActive()
                if (heads[key] !== stamp) throw WidgetConfigurationExpired()
                heads.remove(key)
                completed = true
            }) throw WidgetConfigurationExpired()
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            if (started) {
                try {
                    if (!completed) withContext(NonCancellable) {
                        snapshot.publication {
                            if (heads[key] === stamp) {
                                withContext(Dispatchers.IO) {
                                    val value = context.getSharedPreferences(spec.preferences, Context.MODE_PRIVATE)
                                        .getLong(spec.prefix + snapshot.widgetId, -1)
                                    if (value == expected.id && !writeBinding(spec, snapshot.widgetId, previous))
                                        throw IOException("WIDGET_BINDING_ROLLBACK_NOT_PERSISTED")
                                }
                                heads.remove(key)
                            }
                        }
                    }
                } catch (cleanup: Throwable) {
                    if (failure == null) throw cleanup
                    if (failure !== cleanup) failure.addSuppressed(cleanup)
                } finally {
                    withContext(NonCancellable) {
                        // Only discard the owned memory lease under the shared lock; no
                        // recapture, old display replay or new account's persistent purge.
                        sessions.exclusive { if (heads[key] === stamp) heads.remove(key) }
                    }
                    // Current bindings/data only; no delayed unconditional old-account purge.
                    WidgetRefreshScheduler.request(context)
                }
            }
        }
    }

    private suspend fun requireSelection(spec: WidgetConfigurationSpec, expected: HabitEntity) = database.withTransaction {
        val current = requireNotNull(database.habitDao().getVisibleHabitById(expected.id)) { "WIDGET_HABIT_DELETED" }
        check(current.uuid == expected.uuid && current.habitType == spec.type) { "WIDGET_HABIT_CHANGED" }
    }

    private fun configuredIds(spec: WidgetConfigurationSpec, excludeWidget: Int? = null): Set<Long> {
        val ids = AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, spec.receiver))
        val preferences = context.getSharedPreferences(spec.preferences, Context.MODE_PRIVATE)
        return ids.filter { it != excludeWidget }.map { preferences.getLong(spec.prefix + it, -1) }
            .filter { it != -1L }.toSet()
    }

    // KTX discards commit's Boolean. Configuration must never report failed persistence as success.
    @SuppressLint("UseKtx")
    private fun writeBinding(spec: WidgetConfigurationSpec, widgetId: Int, habitId: Long?): Boolean {
        val edit = context.getSharedPreferences(spec.preferences, Context.MODE_PRIVATE).edit()
        val name = spec.prefix + widgetId
        if (habitId == null) edit.remove(name) else edit.putLong(name, habitId)
        return edit.commit()
    }
}
