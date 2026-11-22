package com.dayforge.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.local.AuthenticationSession
import com.dayforge.data.local.LocalCoreWriteAccess
import com.dayforge.data.local.LocalDataSession
import com.dayforge.data.local.LocalSyncAccess
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Identity-format tests on a physical Android runner. Constructed values are data only:
 * this class never authenticates credentials, grants a lease, or qualifies stored blocks. */
@RunWith(AndroidJUnit4::class)
class G0CountBlockIdentityTest {
    private val caps=setOf("sync.read","structure.write","facts.append","timer.control")
    private fun id(index:Int)="00000000-0000-4000-8000-"+index.toString().padStart(12,'0')
    private fun pair(user:String=id(1),generation:String=id(2),server:String=id(3),epoch:String=id(4),
        device:String=id(5),revision:Int=1,capabilities:Set<String> = caps):Pair<LocalCoreWriteAccess,LocalSyncAccess> {
        val session=LocalDataSession(AuthenticationSession(user,generation),server,epoch)
        return LocalCoreWriteAccess(session,capabilities,device) to LocalSyncAccess(session,device,revision,capabilities)
    }
    private fun binding(pair:Pair<LocalCoreWriteAccess,LocalSyncAccess>)=G0CountBlockSql.binding(pair.first,pair.second)
    private fun reject(core:LocalCoreWriteAccess,sync:LocalSyncAccess) {
        assertTrue(runCatching {G0CountBlockSql.binding(core,sync)}.exceptionOrNull() is IllegalArgumentException)
    }

    @Test fun loginGenerationPermissionRevisionAndPermissionOrderingDoNotRenameUnchangedHistoricalData() {
        val original=binding(pair())
        assertTrue(original.matches(Regex("[0-9a-f]{64}")))
        assertEquals(original,binding(pair()))
        assertEquals(original,binding(pair(generation=id(20))))
        assertEquals(original,binding(pair(revision=2)))
        assertEquals(original,binding(pair(capabilities=linkedSetOf("timer.control","facts.append","structure.write","sync.read"))))
        // A coherent read-only snapshot has the same data identity; it does not gain
        // the omitted write permissions. Every actual operation still checks its lease.
        assertEquals(original,binding(pair(generation=id(21),revision=3,capabilities=setOf("sync.read"))))
    }

    @Test fun accountServerEpochAndDeviceChangesEachCreateDifferentDataBindings() {
        val values=listOf(pair(),pair(user=id(10)),pair(server=id(11)),pair(epoch=id(12)),pair(device=id(13)))
            .map(::binding)
        assertEquals(5,values.toSet().size)
    }

    @Test fun mismatchedCoreAndSyncSnapshotsAreRejectedInsteadOfSelectingEitherIdentity() {
        val (core,sync)=pair()
        for(other in listOf(pair(user=id(10)),pair(generation=id(11)),pair(server=id(12)),pair(epoch=id(13)),
            pair(device=id(14)),pair(capabilities=setOf("sync.read")))) {
            reject(core,other.second);reject(other.first,sync)
        }
        reject(core.copy(capabilities=null),sync)
        reject(core.copy(capturedDeviceId=null),sync)
        reject(core.copy(session=core.session.copy(serverInstanceId=null)),sync)
        reject(core.copy(session=core.session.copy(syncEpoch=null)),sync)
    }

    @Test fun unregisteredSnapshotsDoNotAcquireADeviceLocalHistoricalBinding() {
        val (core,sync)=pair()
        val unregistered=LocalSyncAccess(sync.session)
        reject(core.copy(capabilities=emptySet(),capturedDeviceId=null),unregistered)
        // A missing device must not be substituted with the user, server or session ID.
        reject(core,unregistered)
    }
}
