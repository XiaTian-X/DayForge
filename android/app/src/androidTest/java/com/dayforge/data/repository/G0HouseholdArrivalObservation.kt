package com.dayforge.data.repository

import android.os.SystemClock
import java.util.Collections

/** Optional bounded drawing diagnostics. These callbacks witness draw execution,
 * not presentation on a physical display. No button behavior, pixels or admission
 * change. A missing handler requires separate contemporaneous screenshot evidence;
 * a phase or one of these records alone must never be called visible busy feedback.
 */
internal class G0HouseholdArrivalObservation {
    data class ButtonDraw(val habitUuid:String,val stateVersion:Long,val commandVersion:Long?,
        val phase:G0HouseholdCountPage.Phase,val enabled:Boolean,val current:Boolean,val at:Long)
    data class PrivateDraw(val runtimeVersion:Long,val stateVersion:Long,val commandVersion:Long?,
        val phase:G0HouseholdCountPage.Phase,val allowed:Boolean,val at:Long)
    private val monitor=Any()
    private val buttons=mutableListOf<ButtonDraw>()
    private val privateFrames=mutableListOf<PrivateDraw>()
    private val buttonKeys=mutableMapOf<String,ButtonDraw>()
    private var privateKey:PrivateDraw?=null
    @Volatile var overflow=false;private set
    val buttonDraws:List<ButtonDraw> get()=synchronized(monitor) {Collections.unmodifiableList(buttons.toList())}
    val privateDraws:List<PrivateDraw> get()=synchronized(monitor) {Collections.unmodifiableList(privateFrames.toList())}
    internal fun buttonDrawn(habitUuid:String,state:G0HouseholdCountPage.State,commandVersion:Long?,enabled:Boolean,current:Boolean)=synchronized(monitor) {
        val key=ButtonDraw(habitUuid,state.version,commandVersion,state.phase,enabled,current,0)
        if(buttonKeys[habitUuid]!=key) {
            if(buttons.size+privateFrames.size>=4096) {overflow=true;return@synchronized}
            buttonKeys[habitUuid]=key;buttons+=key.copy(at=SystemClock.elapsedRealtimeNanos())
        }
    }
    internal fun privateDrawn(runtimeVersion:Long,state:G0HouseholdCountPage.State,commandVersion:Long?,allowed:Boolean)=synchronized(monitor) {
        val key=PrivateDraw(runtimeVersion,state.version,commandVersion,state.phase,allowed,0)
        if(privateKey!=key) {
            if(buttons.size+privateFrames.size>=4096) {overflow=true;return@synchronized}
            privateKey=key;privateFrames+=key.copy(at=SystemClock.elapsedRealtimeNanos())
        }
    }
}
