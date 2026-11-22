package com.dayforge.data.repository

import android.database.Cursor
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.dayforge.data.local.*
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.local.entity.HabitMetricLinkEntity
import com.dayforge.data.local.entity.MetricEntity
import com.dayforge.domain.model.StableHabitBinding
import com.dayforge.domain.model.isContractUuid
import com.dayforge.domain.service.AccountSessionCoordinator
import com.dayforge.ui.components.LinkedMetricInfo
import java.util.Collections
import kotlinx.coroutines.flow.first

/** A fixed, already validated configuration inside ONE sealed household owner. Never
 * credentials or an edit ticket. The caller still authenticates every preparation and
 * protects the final read/actual transaction end/publication with its original lease.
 */
internal class G0CompletionPromptProof private constructor(
    private val profile:StaticOneLink,
    private val link:HabitMetricLinkEntity,
    private val metric:MetricEntity,
    private val staticWitness:G0DomainProofIndex.Witness,
    private val linkRow:List<G0DomainJournal.Cell>,
    private val metricRow:List<G0DomainJournal.Cell>,
    private val preferenceRaw:String,
    private val preference:HabitDevicePreferences,
    private val replica:ReplicaPresentationRepository
) {
    /** Null in the Store constructor leaves every prior household rejection intact.
     * Only one initial static link/metric is admitted, not synchronized structure history.
     */
    class StaticOneLink(val preferences:DataStore<Preferences>,val habitUuid:String,val metricUuid:String)

    class Prepared internal constructor(val source:WidgetFactSnapshot,
        val metrics:List<LinkedMetricInfo>,private val current:()->Boolean) {
        val habitName:String get()=source.habit.name
        fun valid():Boolean=runCatching(current).getOrDefault(false)
    }

    suspend fun requireCurrent(db:HabitDatabase,tokens:TokenManager,session:LocalDataSession,index:G0DomainProofIndex) {
        check(db.inTransaction()) { "G0_PROMPT_TRANSACTION_REQUIRED" }
        index.requireCurrent(staticWitness)
        require(index.row(key("habit_metric_links",link.id))==linkRow && index.row(key("metrics",metric.id))==metricRow) {
            "G0_PROMPT_STATIC_DEPENDENCY_CHANGED"
        }
        requireReplica(tokens,replica)
        val actual=readPreferences(profile,tokens,session)
        require(actual.first==preferenceRaw && actual.second==preference) { "G0_PROMPT_PREFERENCE_CHANGED" }
    }

    fun prepare(source:WidgetFactSnapshot,current:()->Boolean):Prepared? {
        // The complete one-member directory proves no link for the other household card.
        if(source.habit.uuid!=link.habitUuid || preference.neverAsk)return null
        require(source.habit.id==link.habitId) { "METRIC_PROMPT_LINK_CHANGED" }
        val infos=Collections.unmodifiableList(listOf(LinkedMetricInfo(metric.name,metric.id,null,metric.unit,metric.decimalPlaces)))
        // latestValue=null is backed by the original cold empty metric_logs proof and
        // this profile's unchanged table witness, not absence in a filtered join.
        return Prepared(source,infos,current)
    }

    companion object {
        private val mutableCountTables=setOf("habits","completions","count_days","sync_outbox","sync_control",
            "next_request_origins","next_transmissions","next_acceptances","sync_entity_state")
        private fun key(table:String,id:Long)=G0DomainProofIndex.Key(table,listOf(G0DomainJournal.Cell(Cursor.FIELD_TYPE_INTEGER,id)))

        /** Called only after the ORIGINAL complete count/source proofs and the Store's
         * all-table empty-scope audit. Index construction alone is not validation.
         */
        suspend fun capture(db:HabitDatabase,tokens:TokenManager,sessions:AccountSessionCoordinator,session:LocalDataSession,
            profile:StaticOneLink,habits:List<HabitEntity>,index:G0DomainProofIndex):G0CompletionPromptProof {
            check(db.inTransaction()) { "G0_PROMPT_TRANSACTION_REQUIRED" }
            require(tokens.usesPreferences(profile.preferences)) { "G0_PROMPT_PREFERENCE_OWNER_CHANGED" }
            require(isContractUuid(profile.habitUuid) && isContractUuid(profile.metricUuid)) { "G0_PROMPT_PROFILE_INVALID" }
            val replica=ReplicaPresentationRepository(db,tokens,sessions)
            requireReplica(tokens,replica)
            val links=db.habitMetricLinkDao().getAllLinksOnce()
            val metrics=db.metricDao().getAllMetricsOnce()
            require(links.size==1 && metrics.size==1) { "G0_PROMPT_STATIC_SCOPE_UNSUPPORTED" }
            val link=links.single();val metric=metrics.single()
            val habit=habits.single {it.uuid==profile.habitUuid}
            require(link.id>0 && isContractUuid(link.uuid) && metric.id>0 && isContractUuid(metric.uuid) &&
                link.habitId==habit.id && link.habitUuid==habit.uuid && link.metricId==metric.id &&
                link.metricUuid==metric.uuid && metric.uuid==profile.metricUuid && link.isActive && link.promptOnComplete &&
                metric.appearance!=null) { "G0_PROMPT_STATIC_BINDING_INVALID" }
            NextCommonFactMapper.validateLinkWrite(SyncV2Mapper.link(link))
            NextStructureMapper.writeMetric(metric) // Original formal metadata validation, not just the visible five fields.
            val linkRow=requireNotNull(index.row(key("habit_metric_links",link.id)))
            val metricRow=requireNotNull(index.row(key("metrics",metric.id)))
            nativeRows(linkRow,metricRow)
            val preference=readPreferences(profile,tokens,session)
            val witness=index.witness((G0DomainSqlCatalog.columns.keys-mutableCountTables).mapTo(linkedSetOf()) {
                G0DomainProofIndex.Dependency.Table(it)
            })
            return G0CompletionPromptProof(profile,link,metric,witness,linkRow,metricRow,preference.first,preference.second,replica)
        }

        private suspend fun requireReplica(tokens:TokenManager,replica:ReplicaPresentationRepository) {
            // Original participants without ReplicaPresentationRepository's nested Room
            // wrapper. This is the existing G0 no-history subset, not a relaxed replica check.
            val witness=tokens.replicaPresentationWitness()
            require(!witness.pointerPresent && witness.history.isEmpty() &&
                replica.readFenceInTransaction()==null) {
                "G0_PROMPT_REPLICA_SCOPE_UNSUPPORTED"
            }
        }

        private suspend fun readPreferences(profile:StaticOneLink,tokens:TokenManager,session:LocalDataSession):Pair<String,HabitDevicePreferences> {
            require(tokens.usesPreferences(profile.preferences)) { "G0_PROMPT_PREFERENCE_OWNER_CHANGED" }
            val values=profile.preferences.data.first()
            tokens.requireHabitPreferenceSession(values,session)
            require(session.serverInstanceId!=null && session.syncEpoch!=null) { "G0_PROMPT_REGISTERED_REQUIRED" }
            val binding=StableHabitBinding.capture(session,profile.habitUuid)
            val raw=requireNotNull(values[HabitDevicePreferences.key(binding)]) { "G0_PROMPT_FORMAL_PREFERENCE_REQUIRED" }
            val record=requireNotNull(HabitDevicePreferences.decode(raw)) { "G0_PROMPT_PREFERENCE_INVALID" }
            require(record.binding==binding) { "G0_PROMPT_PREFERENCE_BINDING_CHANGED" }
            val provisional=StableHabitBinding.capture(session.copy(serverInstanceId=null,syncEpoch=null),profile.habitUuid)
            require(values[HabitDevicePreferences.key(provisional)]==null) { "G0_PROMPT_PREFERENCE_MIGRATION_UNSUPPORTED" }
            return raw to record
        }

        private fun nativeRows(link:List<G0DomainJournal.Cell>,metric:List<G0DomainJournal.Cell>) {
            fun checkRow(table:String,row:List<G0DomainJournal.Cell>,integers:Set<String>,booleans:Set<String>,
                decimals:Set<String>,nullable:Set<String>) {
                G0DomainSqlCatalog.columns.getValue(table).forEachIndexed { i,name ->
                    val cell=row[i]
                    val valid=when {
                        cell.type==Cursor.FIELD_TYPE_NULL -> name in nullable
                        name in booleans -> cell.type==Cursor.FIELD_TYPE_INTEGER && (cell.value==0L || cell.value==1L)
                        name in integers -> cell.type==Cursor.FIELD_TYPE_INTEGER
                        name in decimals -> cell.type in setOf(Cursor.FIELD_TYPE_INTEGER,Cursor.FIELD_TYPE_FLOAT)
                        else -> cell.type==Cursor.FIELD_TYPE_STRING
                    }
                    require(valid) { "G0_PROMPT_NATIVE_ROW_INVALID:$table:$name" }
                }
            }
            checkRow("habit_metric_links",link,setOf("id","habitId","metricId","createdAt","updatedAt"),
                setOf("showInHabitDetail","promptOnComplete","isActive"),setOf("coefficient"),emptySet())
            checkRow("metrics",metric,setOf("id","decimalPlaces","iconResId","createdAt","updatedAt"),setOf("isActive"),
                setOf("targetValue","targetValueUpper"),setOf("targetDirection","targetValue","targetValueUpper"))
        }
    }
}
