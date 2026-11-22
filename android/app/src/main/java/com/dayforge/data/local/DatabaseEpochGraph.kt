package com.dayforge.data.local

import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.dao.CompletionDao
import com.dayforge.data.local.dao.HabitDao
import com.dayforge.data.local.dao.HabitMetricLinkDao
import com.dayforge.data.local.dao.MetricDao
import com.dayforge.data.local.dao.MetricLogDao
import com.dayforge.data.local.dao.SyncConflictDao
import com.dayforge.data.local.dao.SyncOutboxDao
import com.dayforge.data.local.dao.TimeLogDao
import com.dayforge.data.repository.BusinessSyncRepository
import com.dayforge.data.repository.CheckHistoryReader
import com.dayforge.data.repository.CountActionReader
import com.dayforge.data.repository.CountHistoryReader
import com.dayforge.data.repository.CountTrustNodeCollector
import com.dayforge.data.repository.CountCommandNodeCollector
import com.dayforge.data.repository.CountNodeCollectionSchedule
import com.dayforge.data.repository.DashboardReadRepository
import com.dayforge.data.repository.HabitPreferenceRepository
import com.dayforge.data.repository.HabitRepository
import com.dayforge.data.repository.HabitRestartEntry
import com.dayforge.data.repository.IncrementalSyncRepository
import com.dayforge.data.repository.LegacyActivityRateRefresher
import com.dayforge.data.repository.LegacyWidgetFactWorkflow
import com.dayforge.data.repository.LegacyWidgetIdentityVerifier
import com.dayforge.data.repository.MetricRepository
import com.dayforge.data.repository.NextChallengeRestartRepository
import com.dayforge.data.repository.NextConfigExportRepository
import com.dayforge.data.repository.NextConfigImportRepository
import com.dayforge.data.repository.NextObjectCreator
import com.dayforge.data.repository.NextObjectEditor
import com.dayforge.data.repository.NextReplicaSchemaValidator
import com.dayforge.data.repository.NextSyncRuntime
import com.dayforge.data.repository.NextTimerWriter
import com.dayforge.data.repository.OneTimeRepository
import com.dayforge.data.repository.RecurringHabitReader
import com.dayforge.data.repository.ReplicaPresentationRepository
import com.dayforge.data.repository.ReplicaRecoveryRepository
import com.dayforge.data.repository.SyncV2Merger
import com.dayforge.data.repository.TimerHistoryReader
import com.dayforge.data.repository.TimerSyncRepository
import com.dayforge.data.repository.WidgetConfigurationRepository
import com.dayforge.data.repository.WidgetFactReader
import com.dayforge.data.repository.WidgetRestartEntry
import com.dayforge.domain.service.CheckInService
import com.dayforge.domain.service.ConfigExportService
import com.dayforge.domain.service.ConfigImportService
import com.dayforge.domain.service.FailureChecker
import com.dayforge.domain.service.HabitStatusCalculator
import com.dayforge.domain.service.MetricOverviewProvider
import com.dayforge.domain.service.ReplicaRecoveryService
import com.dayforge.ui.screens.dashboard.DashboardHabitListBuilder
import com.dayforge.ui.screens.nested.NestedHabitTreeBuilder

/** One eagerly constructed dependency graph. No component resolves a current DAO mid-call. */
internal class DatabaseEpochGraph internal constructor(val database: HabitDatabase, services: DatabaseEpochServices) {
    private var capturedSource: Any? = null
    internal val sourceIdentity: Any get() = checkNotNull(capturedSource) { "DATABASE_EPOCH_SOURCE_NOT_READY" }
    internal fun bindSource(identity: Any) { check(capturedSource == null); capturedSource = identity }
    val completionDao: CompletionDao = database.completionDao()
    val habitDao: HabitDao = database.habitDao()
    val habitMetricLinkDao: HabitMetricLinkDao = database.habitMetricLinkDao()
    val metricDao: MetricDao = database.metricDao()
    val metricLogDao: MetricLogDao = database.metricLogDao()
    val syncConflictDao: SyncConflictDao = database.syncConflictDao()
    val syncOutboxDao: SyncOutboxDao = database.syncOutboxDao()
    val timeLogDao: TimeLogDao = database.timeLogDao()
    val checkHistoryReader: CheckHistoryReader = CheckHistoryReader(database, services.tokenManager, services.accountSessionCoordinator)
    val countActionReader: CountActionReader = CountActionReader(database, services.tokenManager, services.accountSessionCoordinator)
    val countHistoryReader: CountHistoryReader = CountHistoryReader(database, services.tokenManager, services.accountSessionCoordinator)
    val countTrustNodeCollector: CountTrustNodeCollector = CountTrustNodeCollector(database, services.tokenManager, services.accountSessionCoordinator)
    val countCommandNodeCollector: CountCommandNodeCollector = CountCommandNodeCollector(database, services.tokenManager, services.accountSessionCoordinator)
    val countNodeCollectionSchedule = CountNodeCollectionSchedule(countTrustNodeCollector, countCommandNodeCollector)
    val legacyActivityRateRefresher: LegacyActivityRateRefresher = LegacyActivityRateRefresher(database, services.tokenManager, services.accountSessionCoordinator)
    val nextChallengeRestartRepository: NextChallengeRestartRepository = NextChallengeRestartRepository(database, services.tokenManager, services.accountSessionCoordinator, services.nextSyncHttp, services.accountMaterialDependencyGate)
    val nextConfigExportRepository: NextConfigExportRepository = NextConfigExportRepository(database, services.tokenManager, services.accountSessionCoordinator, services.preferencesManager, services.accountIconController, services.deviceThemeController)
    val nextObjectCreator: NextObjectCreator = NextObjectCreator(database, services.tokenManager, services.accountSessionCoordinator, services.accountIconController)
    val nextObjectEditor: NextObjectEditor = NextObjectEditor(database, services.tokenManager, services.accountSessionCoordinator, services.accountIconController)
    val nextReplicaSchemaValidator: NextReplicaSchemaValidator = NextReplicaSchemaValidator(services.context, database, services.tokenManager, services.accountSessionCoordinator)
    val nextSyncRuntime: NextSyncRuntime = NextSyncRuntime(database, services.tokenManager, services.accountSessionCoordinator, services.nextSyncHttp, services.preferencesManager, services.accountMaterialDependencyGate, services.coordination)
    val oneTimeRepository: OneTimeRepository = OneTimeRepository(database, services.tokenManager, services.accountSessionCoordinator, services.preferencesManager)
    val replicaPresentationRepository: ReplicaPresentationRepository = ReplicaPresentationRepository(database, services.tokenManager, services.accountSessionCoordinator)
    val timerHistoryReader: TimerHistoryReader = TimerHistoryReader(database, services.tokenManager, services.accountSessionCoordinator)
    val widgetConfigurationRepository: WidgetConfigurationRepository = WidgetConfigurationRepository(services.context, database, services.tokenManager, services.widgetDisplayPublisher, services.accountSessionCoordinator)
    val dashboardReadRepository: DashboardReadRepository = DashboardReadRepository(database, services.tokenManager, services.accountSessionCoordinator, replicaPresentationRepository, countHistoryReader, checkHistoryReader)
    val habitRestartEntry: HabitRestartEntry = HabitRestartEntry(nextChallengeRestartRepository)
    val legacyWidgetIdentityVerifier: LegacyWidgetIdentityVerifier = LegacyWidgetIdentityVerifier(database, services.tokenManager, replicaPresentationRepository)
    val nextConfigImportRepository: NextConfigImportRepository = NextConfigImportRepository(database, services.tokenManager, services.accountSessionCoordinator, services.accountIconController, nextObjectCreator, services.context)
    val nextTimerWriter: NextTimerWriter = NextTimerWriter(database, services.tokenManager, services.accountSessionCoordinator, timerHistoryReader)
    val recurringHabitReader: RecurringHabitReader = RecurringHabitReader(database, services.tokenManager, services.accountSessionCoordinator, nextObjectEditor, countHistoryReader, checkHistoryReader, timerHistoryReader)
    val syncV2Merger: SyncV2Merger = SyncV2Merger(database, habitDao, completionDao, timeLogDao, metricDao, metricLogDao, habitMetricLinkDao, syncOutboxDao, syncConflictDao)
    val timerSyncRepository: TimerSyncRepository = TimerSyncRepository(services.syncV2Api, timeLogDao)
    val widgetFactReader: WidgetFactReader = WidgetFactReader(database, services.tokenManager, services.accountSessionCoordinator, services.preferencesManager, countHistoryReader, checkHistoryReader)
    val configImportPreviewReader = com.dayforge.data.repository.ConfigImportPreviewReader(habitDao, metricDao, timeLogDao, completionDao, metricLogDao)
    val configExportService: ConfigExportService = ConfigExportService(habitDao, metricDao, habitMetricLinkDao)
    val configImportService: ConfigImportService = ConfigImportService(habitDao, metricDao, habitMetricLinkDao, database, services.structuralEditGuard)
    val failureChecker: FailureChecker = FailureChecker(completionDao, timeLogDao, countHistoryReader, checkHistoryReader, timerHistoryReader)
    val habitPreferenceRepository: HabitPreferenceRepository = HabitPreferenceRepository(database, services.tokenManager, services.accountSessionCoordinator, services.preferencesManager, replicaPresentationRepository, legacyWidgetIdentityVerifier)
    val incrementalSyncRepository: IncrementalSyncRepository = IncrementalSyncRepository(services.syncV2Api, services.endpointResolver, services.authApi, services.tokenManager, services.preferencesManager, habitDao, completionDao, timeLogDao, metricDao, metricLogDao, habitMetricLinkDao, syncOutboxDao, syncConflictDao, timerSyncRepository, syncV2Merger, services.json, services.accountSessionCoordinator, problemDatabase = database)
    val metricRepository: MetricRepository = MetricRepository(database, metricDao, metricLogDao, habitDao, habitMetricLinkDao, services.structuralEditGuard, nextObjectEditor, nextObjectCreator, widgetFactReader, nextTimerWriter)
    val widgetRestartEntry: WidgetRestartEntry = WidgetRestartEntry(database, services.tokenManager, services.accountSessionCoordinator, nextObjectEditor, widgetFactReader, services.structuralEditGuard)
    val habitStatusCalculator: HabitStatusCalculator = HabitStatusCalculator(failureChecker, completionDao, timeLogDao, oneTimeRepository, nextTimerWriter, countHistoryReader, checkHistoryReader)
    val nestedHabitTreeBuilder: NestedHabitTreeBuilder = NestedHabitTreeBuilder(habitDao, completionDao, timeLogDao, failureChecker, oneTimeRepository, nextTimerWriter, countHistoryReader, checkHistoryReader, countActionReader)
    val businessSyncRepository: BusinessSyncRepository = BusinessSyncRepository(incrementalSyncRepository, services.endpointResolver, database, services.tokenManager, services.accountSessionCoordinator, services.nextSyncHttp, nextSyncRuntime, services.coordination)
    val habitRepository: HabitRepository = HabitRepository(habitDao, completionDao, timeLogDao, database, services.structuralEditGuard, nextObjectEditor, nextObjectCreator, oneTimeRepository, countHistoryReader, widgetFactReader, checkHistoryReader, timerHistoryReader, recurringHabitReader, habitRestartEntry, widgetRestartEntry, nextTimerWriter, countActionReader)
    val goalTargetReadRepository = com.dayforge.data.repository.GoalTargetReadRepository(habitDao)
    val settingsReadRepository = com.dayforge.data.repository.SettingsReadRepository(habitDao, timeLogDao)
    val creationReadRepository = com.dayforge.data.repository.CreationReadRepository(habitDao, metricDao)
    val overviewReadRepository = com.dayforge.data.repository.OverviewReadRepository(timeLogDao, habitDao, metricDao)
    val habitTargetReadRepository = com.dayforge.data.repository.HabitTargetReadRepository(
        habitDao, timeLogDao, completionDao, metricDao, habitMetricLinkDao, metricLogDao)
    private val metricDisplayReader = com.dayforge.data.repository.MetricDisplayReader(database,
        services.tokenManager, services.accountSessionCoordinator, nextObjectEditor)
    val metricDetailSourceReader = com.dayforge.data.repository.MetricDetailSourceReader(database, metricRepository, metricDisplayReader)
    val metricOverviewProvider: MetricOverviewProvider = MetricOverviewProvider.owned(metricRepository, metricDisplayReader)
    val dashboardHabitListBuilder: DashboardHabitListBuilder = DashboardHabitListBuilder(habitStatusCalculator)
    val legacyWidgetFactWorkflow: LegacyWidgetFactWorkflow = LegacyWidgetFactWorkflow(database, services.tokenManager, services.accountSessionCoordinator, legacyWidgetIdentityVerifier, habitRepository, services.structuralEditGuard)
    val countCommandPendingRepository = com.dayforge.data.repository.CountCommandPendingRepository(database, services.tokenManager, services.accountSessionCoordinator, habitRepository, legacyWidgetFactWorkflow, widgetFactReader, services.preferencesManager, metricRepository)
    val replicaRecoveryRepository: ReplicaRecoveryRepository = ReplicaRecoveryRepository(services.context, database, services.tokenManager, services.accountSessionCoordinator, services.preferencesManager, services.dataStore, businessSyncRepository, services.nextReplicaCandidateValidator, services.accountIconController, services.replicaRecoveryConnection)
    val checkInService: CheckInService = CheckInService(habitRepository, completionDao, timeLogDao, services.countActionGate)
    val activeTimerStateProvider: com.dayforge.domain.service.ActiveTimerStateProvider =
        com.dayforge.domain.service.ActiveTimerStateProvider(timeLogDao, habitRepository, services.context, nextTimerWriter)
    val replicaRecoveryService: ReplicaRecoveryService = ReplicaRecoveryService(replicaRecoveryRepository, services.tokenManager, services.accountSessionCoordinator)
}
