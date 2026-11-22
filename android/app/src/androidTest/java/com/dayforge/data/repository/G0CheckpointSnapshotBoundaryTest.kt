package com.dayforge.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** The fingerprint and the verified state must describe the SAME cold transaction contents. */
@RunWith(AndroidJUnit4::class)
class G0CheckpointSnapshotBoundaryTest : G0PersistentCheckpointFixture() {
    private fun receiptHash():String=db.openHelper.writableDatabase.query("SELECT resultHash FROM next_acceptances").use {
        check(it.moveToFirst());it.getString(0)
    }
    @Test fun hitCannotPublishStateChangedAfterContentMatch()=runBlocking<Unit> {
        seed(1);val original=receiptHash();certify();val packet=checkpointFile.readBytes();cold()
        val cp=checkpoint();val candidate=store(cp)
        candidate.afterCheckpointMatchForTest={ db.openHelper.writableDatabase.execSQL("UPDATE next_acceptances SET resultHash='wrong'") }
        val rejection=try { candidate.initialize(access(),trustedDomainSchema(true));null } catch(e:IllegalStateException) { e }
        assertEquals("G0_CHECKPOINT_SNAPSHOT_CHANGED",rejection?.message)
        assertTrue(cp.hit);assertArrayEquals(packet,checkpointFile.readBytes())
        rawReopen();assertEquals(original,receiptHash())
    }
    @Test fun missCannotSignThePreVerificationUnvalidatedSnapshot()=runBlocking<Unit> {
        seed(1);val original=receiptHash()
        db.openHelper.writableDatabase.execSQL("UPDATE next_acceptances SET resultHash='wrong'")
        cold();val cp=checkpoint();val candidate=store(cp)
        candidate.afterCheckpointMatchForTest={ db.openHelper.writableDatabase.execSQL("UPDATE next_acceptances SET resultHash=?",arrayOf(original)) }
        val rejection=try { candidate.initialize(access(),trustedDomainSchema(true));null } catch(e:IllegalStateException) { e }
        assertEquals("G0_CHECKPOINT_SNAPSHOT_CHANGED",rejection?.message)
        assertFalse(cp.hit);assertFalse(checkpointFile.exists())
        rawReopen();assertEquals("wrong",receiptHash())
    }
}
