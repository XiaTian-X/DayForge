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
import com.dayforge.domain.model.StableHabitBinding
import kotlinx.serialization.json.*

internal data class WidgetConfigurationSpec(val type: HabitType, val preferences: String,
    val prefix: String, val receiver: Class<*>)

internal class WidgetConfigurationSnapshot(val spec: WidgetConfigurationSpec, val widgetId: Int,
    val habits: List<HabitEntity>, val publication: WidgetDisplayPublisher.Publication)

internal class WidgetConfigurationExpired : IllegalStateException("WIDGET_CONFIGURATION_EXPIRED")
internal class WidgetBoundSnapshot(val habit: HabitEntity?, val publication: WidgetDisplayPublisher.Publication)

/** Persistent display selection, not action authority. The numeric preference is only a cache. */
private data class WidgetStoredBinding(val identity: StableHabitBinding, val type: HabitType) {
    fun encode(): String = buildJsonObject {
        put("version", 1); put("type", type.name); put("identity", Json.parseToJsonElement(identity.encode()))
    }.toString()
    companion object {
        fun decode(raw: String?): WidgetStoredBinding? = try {
            if (raw == null || raw.length > 4096) null else {
                val body = Json.parseToJsonElement(raw).jsonObject
                require(body.keys == setOf("version", "type", "identity") && body.getValue("version") == JsonPrimitive(1))
                val type = body.getValue("type").jsonPrimitive.also { require(it.isString) }.content
                WidgetStoredBinding(requireNotNull(StableHabitBinding.decodeOrNull(body.getValue("identity").toString())), HabitType.valueOf(type))
            }
        } catch (_: IllegalArgumentException) { null }
    }
}

private data class WidgetRawBinding(val encoded: Any?, val hint: Any?)

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
    // A failed commit can still update SharedPreferences' in-memory value. Never adopt that
    // value as a persisted first-server promotion without a subsequent successful commit.
    private val unconfirmedBindings = mutableSetOf<Pair<String, Int>>()

    /** Resolve only the persisted stable identity; never infer one from a reused numeric ID. */
    internal suspend fun bound(spec: WidgetConfigurationSpec, widgetId: Int): WidgetBoundSnapshot? {
        val publication = publisher.capturePublication() ?: return null
        var row: HabitEntity? = null
        var boundRaw: WidgetRawBinding? = null
        if (!publication {
            val raw = rawBinding(spec, widgetId)
            boundRaw = raw
            val stored = WidgetStoredBinding.decode(raw.encoded as? String)
            val session = publication.session
            if (stored != null && session != null && stored.type == spec.type) {
                val identity = stored.identity.resolve(session, stored.identity.habitUuid)
                val current = database.withTransaction { database.habitDao().getHabitByUuid(stored.identity.habitUuid) }
                if (identity != null && current != null && current.habitType == spec.type &&
                    database.habitDao().getVisibleHabitById(current.id)?.uuid == current.uuid) {
                    val updated = WidgetRawBinding(WidgetStoredBinding(identity, spec.type).encode(), current.id)
                    check(rawBinding(spec, widgetId) == raw) { "WIDGET_BINDING_CHANGED" }
                    persistResolvedBinding(spec, widgetId, raw, updated)
                    row = current; boundRaw = updated
                }
            }
        }) return null
        val expected = row
        val original = requireNotNull(boundRaw)
        suspend fun stillBound(): Boolean = rawBinding(spec, widgetId) == original && database.withTransaction {
            expected == null || database.habitDao().getVisibleHabitById(expected.id)
                ?.let { it.uuid == expected.uuid && it.habitType == spec.type } == true
        }
        val boundPublication = WidgetDisplayPublisher.Publication({ display ->
            var published = false
            val valid = publication { if (stillBound()) { display(); published = true } }
            valid && published
        }, { publication.isCurrentWhileAccountLocked() && stillBound() }, publication::legacyRestartClaim,
            publication.frameOwner, publication.session, publication::isCurrentInMemory)
        return WidgetBoundSnapshot(expected, boundPublication)
    }

    internal suspend fun load(spec: WidgetConfigurationSpec, widgetId: Int): WidgetConfigurationSnapshot {
        require(widgetId != AppWidgetManager.INVALID_APPWIDGET_ID)
        val publication = publisher.capturePublication() ?: throw WidgetConfigurationExpired()
        try {
            val habits = database.habitDao().getVisibleHabitsOnce()
            val configured = configuredIds(spec, publication)
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
        var previous: WidgetRawBinding? = null
        val identity = StableHabitBinding.capture(requireNotNull(snapshot.publication.session), expected.uuid)
        val selected = WidgetRawBinding(WidgetStoredBinding(identity, spec.type).encode(), expected.id)
        var failure: Throwable? = null
        try {
            if (!snapshot.publication {
                requireSelection(spec, expected)
                withContext(Dispatchers.IO) {
                    check(expected.id !in configuredIdsWhileAccountLocked(spec, snapshot.publication, snapshot.widgetId)) { "WIDGET_ALREADY_CONFIGURED" }
                    previous = rawBinding(spec, snapshot.widgetId)
                    currentCoroutineContext().ensureActive()
                    heads[key] = stamp
                    started = true
                    // Assign ownership before commit: false/throw can still change the memory value.
                    unconfirmedBindings.add(key)
                    check(writeRawBinding(spec, snapshot.widgetId, selected)) { "WIDGET_BINDING_NOT_PERSISTED" }
                    unconfirmedBindings.remove(key)
                }
            }) throw WidgetConfigurationExpired()
            fun ownsBinding(): Boolean = heads[key] === stamp &&
                rawBinding(spec, snapshot.widgetId) == selected
            // Account validity is not enough: another page can replace this same widget's
            // binding within one account. Gate both normal display and typed writer reads.
            val selectedPublication = WidgetDisplayPublisher.Publication({ render ->
                var written = false
                val current = snapshot.publication { if (ownsBinding()) { render(); written = true } }
                current && written
            }, { snapshot.publication.isCurrentWhileAccountLocked() && ownsBinding() }, snapshot.publication::legacyRestartClaim,
                snapshot.publication.frameOwner, snapshot.publication.session, snapshot.publication::isCurrentInMemory)
            display(selectedPublication)
            if (!snapshot.publication {
                requireSelection(spec, expected)
                currentCoroutineContext().ensureActive()
                if (!ownsBinding()) throw WidgetConfigurationExpired()
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
                                    val value = rawBinding(spec, snapshot.widgetId)
                                    if (value == selected) {
                                        unconfirmedBindings.add(key)
                                        if (!writeRawBinding(spec, snapshot.widgetId, requireNotNull(previous)))
                                            throw IOException("WIDGET_BINDING_ROLLBACK_NOT_PERSISTED")
                                        unconfirmedBindings.remove(key)
                                    }
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

    private suspend fun configuredIds(spec: WidgetConfigurationSpec, publication: WidgetDisplayPublisher.Publication): Set<Long> {
        var result = emptySet<Long>()
        if (!publication { result = configuredIdsWhileAccountLocked(spec, publication) }) throw WidgetConfigurationExpired()
        return result
    }

    private suspend fun configuredIdsWhileAccountLocked(spec: WidgetConfigurationSpec,
        publication: WidgetDisplayPublisher.Publication, excludeWidget: Int? = null): Set<Long> {
        val ids = AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, spec.receiver))
        val session = publication.session ?: return emptySet()
        return database.withTransaction {
            ids.filter { it != excludeWidget }.mapNotNull { widgetId ->
                val stored = WidgetStoredBinding.decode(rawBinding(spec, widgetId).encoded as? String) ?: return@mapNotNull null
                if (stored.type != spec.type || stored.identity.resolve(session, stored.identity.habitUuid) == null) return@mapNotNull null
                database.habitDao().getHabitByUuid(stored.identity.habitUuid)?.takeIf {
                    it.habitType == spec.type && database.habitDao().getVisibleHabitById(it.id)?.uuid == it.uuid
                }?.id
            }.toSet()
        }
    }

    private fun rawBinding(spec: WidgetConfigurationSpec, widgetId: Int): WidgetRawBinding {
        val values = context.getSharedPreferences(spec.preferences, Context.MODE_PRIVATE).all
        return WidgetRawBinding(values["binding_v1_$widgetId"], values[spec.prefix + widgetId])
    }

    /** Called only under the original publication's account lock. */
    private suspend fun persistResolvedBinding(spec: WidgetConfigurationSpec, widgetId: Int,
        original: WidgetRawBinding, updated: WidgetRawBinding) {
        val key = spec.preferences to widgetId
        if (updated == original && key !in unconfirmedBindings) return
        unconfirmedBindings.add(key)
        try {
            withContext(Dispatchers.IO) {
                check(writeRawBinding(spec, widgetId, updated)) { "WIDGET_BINDING_NOT_PERSISTED" }
            }
            unconfirmedBindings.remove(key)
        } catch (error: Throwable) {
            try {
                withContext(NonCancellable + Dispatchers.IO) {
                    if (rawBinding(spec, widgetId) == updated) {
                        if (!writeRawBinding(spec, widgetId, original))
                            throw IOException("WIDGET_BINDING_ROLLBACK_NOT_PERSISTED")
                        // If retrying the same uncertain value, even rollback needs a successful commit.
                        unconfirmedBindings.remove(key)
                    }
                }
            } catch (cleanup: Throwable) {
                if (cleanup !== error) error.addSuppressed(cleanup)
            }
            throw error
        }
    }

    // KTX discards commit's Boolean. Configuration must never report failed persistence as success.
    @SuppressLint("UseKtx")
    private fun writeRawBinding(spec: WidgetConfigurationSpec, widgetId: Int, raw: WidgetRawBinding): Boolean {
        val edit = context.getSharedPreferences(spec.preferences, Context.MODE_PRIVATE).edit()
        fun put(key: String, value: Any?) { when (value) {
            null -> edit.remove(key)
            is String -> edit.putString(key, value)
            is Long -> edit.putLong(key, value)
            is Int -> edit.putInt(key, value)
            is Boolean -> edit.putBoolean(key, value)
            is Float -> edit.putFloat(key, value)
            is Set<*> -> edit.putStringSet(key, value.map { requireNotNull(it as? String) }.toSet())
            else -> error("WIDGET_BINDING_STORAGE_INVALID")
        } }
        put("binding_v1_$widgetId", raw.encoded); put(spec.prefix + widgetId, raw.hint)
        return edit.commit()
    }
}
