package com.dayforge.data.repository

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.geometry.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Pixel decoder validation on device; these synthetic frames are never latency evidence. */
@RunWith(AndroidJUnit4::class)
class G0JointObservationTest {
    private fun frame(observers:List<G0ScreenObservation>,first:Long,second:Long):Bitmap=Bitmap.createBitmap(260,40,Bitmap.Config.ARGB_8888).apply {
        listOf(observers[0].encodedVersion(first),observers[1].encodedVersion(second)).forEachIndexed { row,version ->
            for(i in 0 until 26) {
                val color=when(i) { 0->Color.MAGENTA;25->Color.CYAN
                    else->if((version shr(i-1)) and 1L==1L) Color.WHITE else Color.BLACK }
                for(x in i*10 until (i+1)*10) for(y in row*20 until row*20+10) setPixel(x,y,color)
            }
        }
    }
    private fun observers()=listOf(G0ScreenObservation().apply { placed(Rect(0f,0f,260f,10f)) },
        G0ScreenObservation().apply { placed(Rect(0f,20f,260f,30f)) })
    @Test fun bothCorrectVersionsMustBePresentInOneFrame() {
        val observers=observers();val targets=observers.mapIndexed { i,o -> G0ScreenObservation.Target(o,if(i==0) 37 else 58) }
        val correct=frame(observers,37,58);val staleFirst=frame(observers,36,58);val staleSecond=frame(observers,37,57)
        try {
            assertTrue(G0ScreenObservation.matchesTogether(correct,targets))
            assertFalse(G0ScreenObservation.matchesTogether(staleFirst,targets))
            assertFalse(G0ScreenObservation.matchesTogether(staleSecond,targets))
        } finally { correct.recycle();staleFirst.recycle();staleSecond.recycle() }
    }
    @Test fun revokedPublicationCannotUsePixelsFromAnEarlierFrame() {
        val observers=observers();var valid=true
        val targets=listOf(G0ScreenObservation.Target(observers[0],37),G0ScreenObservation.Target(observers[1],58) { valid })
        val bitmap=frame(observers,37,58)
        try {
            assertTrue(G0ScreenObservation.matchesTogether(bitmap,targets))
            valid=false;assertFalse(G0ScreenObservation.matchesTogether(bitmap,targets))
        } finally { bitmap.recycle() }
    }
    @Test fun missingAndDuplicateObserversCannotCountAsTwoConsumers() {
        val observers=observers();val observer=observers[0];val bitmap=frame(observers,37,58)
        try {
            assertFalse(G0ScreenObservation.matchesTogether(bitmap,emptyList()))
            val target=G0ScreenObservation.Target(observer,37)
            assertFalse(G0ScreenObservation.matchesTogether(bitmap,listOf(target,target)))
            assertFalse(G0ScreenObservation.matchesTogether(bitmap,listOf(target,G0ScreenObservation.Target(G0ScreenObservation(),58))))
        } finally { bitmap.recycle() }
    }
    @Test fun clippedOrDamagedMarkerCannotPassJointObservation() {
        val observers=observers();val bitmap=frame(observers,37,58)
        val targets=listOf(G0ScreenObservation.Target(observers[0],37),G0ScreenObservation.Target(observers[1],58))
        try {
            bitmap.setPixel(5,25,Color.BLACK)
            assertFalse(G0ScreenObservation.matchesTogether(bitmap,targets))
            observers[1].placed(Rect(0f,40f,260f,50f))
            assertFalse(G0ScreenObservation.matchesTogether(bitmap,targets))
        } finally { bitmap.recycle() }
    }
    @Test fun recreatedOwnersCannotBorrowOldFramesWithResetVersions() {
        val old=observers();val fresh=observers()
        val prior=frame(old,2,2);val current=frame(fresh,2,2)
        val targets=fresh.map { G0ScreenObservation.Target(it,2) }
        try {
            assertFalse(G0ScreenObservation.matchesTogether(prior,targets))
            assertTrue(G0ScreenObservation.matchesTogether(current,targets))
        } finally { prior.recycle();current.recycle() }
    }

}
