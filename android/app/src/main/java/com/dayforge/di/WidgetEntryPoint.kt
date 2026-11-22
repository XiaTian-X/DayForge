package com.dayforge.di

import android.content.Context
import com.dayforge.data.local.DatabaseEpochGraph
import com.dayforge.data.local.DatabaseRuntimeHost
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.PreferencesManager
import com.dayforge.data.repository.WidgetDisplayPublisher
import com.dayforge.domain.service.CountActionGate
import com.dayforge.domain.service.WidgetConfigurationWorkflow
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

/** Stable entrypoint only. No per-epoch database/DAO/repository is cached by the Hilt component. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
    fun access(): WidgetGraphAccess
    companion object {
        fun from(context: Context): WidgetGraphAccess = dagger.hilt.android.EntryPointAccessors
            .fromApplication(context.applicationContext, WidgetEntryPoint::class.java).access()
        internal suspend fun <T> withGraph(context: Context, block: suspend () -> T): T =
            from(context).withGraph(block)
        internal fun database(context: Context): HabitDatabase = from(context).bound().database
        fun calculator(context: Context, database: HabitDatabase): com.dayforge.domain.service.HabitStatusCalculator =
            try { from(context).bound().also { check(it.database === database) { "DATABASE_EPOCH_INPUT_EXPIRED" } }.habitStatusCalculator }
            catch (error: IllegalStateException) {
                // Preserve only the original unsealed/non-Hilt reference fixture fallback.
                // A production controlled database must never turn missing source scope into
                // an unauthenticated calculator or resolve another owner's cached DAO.
                if (com.dayforge.data.local.CountTrustRuntime.forDatabase(database) != null) throw error
                com.dayforge.domain.service.HabitStatusCalculator(com.dayforge.domain.service.FailureChecker(
                    database.completionDao(), database.timeLogDao()), database.completionDao(), database.timeLogDao())
            }
    }
}

@Singleton
class WidgetGraphAccess @Inject internal constructor(private val host: DatabaseRuntimeHost,
    private val publisher: WidgetDisplayPublisher, private val configuration: WidgetConfigurationWorkflow,
    private val preferences: PreferencesManager, private val countGate: CountActionGate) {
    internal fun bound(): DatabaseEpochGraph = host.boundGraph()
    internal suspend fun <T> withGraph(block: suspend () -> T): T = host.withGraph { block() }
    fun habitRepository() = bound().habitRepository
    fun habitStatusCalculator() = bound().habitStatusCalculator
    fun timerWriter() = bound().nextTimerWriter
    fun factReader() = bound().widgetFactReader
    fun legacyFactWorkflow() = bound().legacyWidgetFactWorkflow
    fun activityRateRefresher() = bound().legacyActivityRateRefresher
    fun countActionGate() = countGate
    fun displayPublisher() = publisher
    fun configurationWorkflow() = configuration
    fun preferencesManager() = preferences
}
