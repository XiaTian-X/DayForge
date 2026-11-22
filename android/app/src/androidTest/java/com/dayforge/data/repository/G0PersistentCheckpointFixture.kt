package com.dayforge.data.repository

import androidx.room.Room
import com.dayforge.data.local.HabitDatabase
import kotlinx.serialization.json.*
import java.io.File
import java.security.KeyStore
import java.util.UUID
import org.junit.After
import org.junit.Assert.*

abstract class G0PersistentCheckpointFixture : G0SealedDomainFixture() {
    override fun domainOpeningCallback()=G0SchemaOpeningCallback(true)
    internal val checkpointAlias="dayforge.test.g0.checkpoint.${UUID.randomUUID()}"
    internal val dataset=UUID.randomUUID().toString()
    internal val checkpointFile get()=File(directory,"proof-checkpoint")
    internal fun checkpoint(datasetId:String=dataset,bufferDigests:Boolean=true)=G0PersistentCheckpoint(checkpointFile,checkpointAlias,datasetId,bufferDigests)
    internal fun store(cp:G0PersistentCheckpoint?=null,reuseRows:Boolean=true)=G0ShortMetricStore(db,tokens,sessions,authority,domainGate,metric.uuid,checkpoint=cp,reuseCheckpointRows=reuseRows)
    internal fun cold() {
        freshGateForColdOpen()
        storage.isolatedBuilder={ context -> Room.databaseBuilder(context,HabitDatabase::class.java,"habit_database")
            .addCallback(domainOpeningCallback()).openHelperFactory(domainGate.factory).build() }
        storage.reopen()
    }
    internal suspend fun seed(count:Int) {
        register()
        val (http,_)=channel { input -> successReply(input,revision=(if(input.path.endsWith("/identity")) 0L else
            wireOperation(input)["base_revision"]?.jsonPrimitive?.longOrNull ?: 0L)+1) }
        repeat(count) { i ->
            val row=editMetric("Seed $i")
            val delivery=requireNotNull(sender(http).sendOperation(access(),row.operationId))
            assertEquals(NextOperationAcceptance.COMMITTED,sender(http).acceptOperation(delivery))
        }
    }
    internal suspend fun certify(cp:G0PersistentCheckpoint=checkpoint()):G0ShortMetricStore {
        val result=store(cp);result.initialize(access(),trustedDomainSchema(true));return result
    }
    internal fun rawReopen() { storage.isolatedBuilder=null;storage.reopen() }
    @After fun removeCheckpointKey() {
        KeyStore.getInstance("AndroidKeyStore").apply { load(null);deleteEntry(checkpointAlias) }
    }
}
