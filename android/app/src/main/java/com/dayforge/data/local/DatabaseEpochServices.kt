package com.dayforge.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.dayforge.data.api.AuthApi
import com.dayforge.data.api.EndpointResolver
import com.dayforge.data.api.NextSyncHttp
import com.dayforge.data.api.SyncV2Api
import com.dayforge.data.local.PreferencesManager
import com.dayforge.data.local.TokenManager
import com.dayforge.data.repository.AccountMaterialDependencyGate
import com.dayforge.data.repository.NextReplicaCandidateValidator
import com.dayforge.data.repository.ReplicaRecoveryConnection
import com.dayforge.data.repository.WidgetDisplayPublisher
import com.dayforge.domain.service.AccountIconController
import com.dayforge.domain.service.AccountSessionCoordinator
import com.dayforge.domain.service.CountActionGate
import com.dayforge.domain.service.DeviceThemeController
import com.dayforge.domain.service.StructuralEditGuard
import kotlinx.serialization.json.Json

import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Stable application inputs. No raw Room/DAO or per-epoch repository is cached here. */
@Singleton
internal class DatabaseEpochServices @Inject constructor(
    @param:ApplicationContext val context: Context,
    val dataStore: DataStore<Preferences>,
    val authApi: AuthApi,
    val endpointResolver: EndpointResolver,
    val nextSyncHttp: NextSyncHttp,
    val syncV2Api: SyncV2Api,
    val preferencesManager: PreferencesManager,
    val tokenManager: TokenManager,
    val accountMaterialDependencyGate: AccountMaterialDependencyGate,
    val nextReplicaCandidateValidator: NextReplicaCandidateValidator,
    val replicaRecoveryConnection: ReplicaRecoveryConnection,
    val widgetDisplayPublisher: WidgetDisplayPublisher,
    val accountIconController: AccountIconController,
    val accountSessionCoordinator: AccountSessionCoordinator,
    val countActionGate: CountActionGate,
    val deviceThemeController: DeviceThemeController,
    val structuralEditGuard: StructuralEditGuard,
    val json: Json,
    val coordination: DatabaseSyncCoordination
)
