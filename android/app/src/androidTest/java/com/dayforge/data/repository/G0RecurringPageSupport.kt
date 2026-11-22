package com.dayforge.data.repository

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import com.dayforge.data.local.entity.CompletionEntity
import com.dayforge.data.local.toDisplayMillis
import com.dayforge.data.model.HabitType
import java.time.ZonedDateTime

internal suspend fun G0SealedDomainFixture.seedPageFacts(history:Int):List<Long> {
    freshGateForColdOpen(recurringReads=true);storage.reopen()
    val now=ZonedDateTime.now();var checkId=0L
    producer().write(local()) { checkId=db.habitDao().insert(habit.copy(id=0,uuid=id(6201),name="Real check",habitType=HabitType.CHECK_IN)) }
    for((id,value) in (0 until history).map { habit.id to (it+2) } + listOf(checkId to 1)) {
        val h=requireNotNull(db.habitDao().getHabitById(id))
        producer().write(local()) {
            val fact=CompletionEntity(habitId=h.id,habitUuid=h.uuid,date=now.toLocalDate().toDisplayMillis(),actualCompletedAt=now.toInstant().toEpochMilli(),value=value)
            if(h.habitType==HabitType.COUNTING) NextCountDayStore(db).capture(h,fact)
            db.completionDao().insertForSync(fact)
        }
    }
    return listOf(habit.id,checkId)
}

/** Independent native, typed snapshot. Used only by the fixture, never the screen/owner. */
internal fun G0SealedDomainFixture.pageNativeState():Map<String,List<String>> = SQLiteDatabase.openDatabase(
    app.getDatabasePath("habit_database").path,null,SQLiteDatabase.OPEN_READONLY).use { raw ->
    val tables=raw.rawQuery("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name",null).use { c ->
        buildList { while(c.moveToNext()) add(c.getString(0)) }
    }
    tables.associateWith { table -> raw.rawQuery("SELECT * FROM `$table`",null).use { c -> buildList {
        while(c.moveToNext()) add((0 until c.columnCount).joinToString("|") { i -> when(c.getType(i)) {
            Cursor.FIELD_TYPE_NULL -> "N"
            Cursor.FIELD_TYPE_INTEGER -> "I${c.getLong(i)}"
            Cursor.FIELD_TYPE_FLOAT -> "F${java.lang.Double.doubleToRawLongBits(c.getDouble(i))}"
            Cursor.FIELD_TYPE_BLOB -> "B${android.util.Base64.encodeToString(c.getBlob(i),android.util.Base64.NO_WRAP)}"
            else -> "S${android.util.Base64.encodeToString(c.getString(i).toByteArray(),android.util.Base64.NO_WRAP)}"
        } })
    }.sorted() } }
}

@Composable
internal fun G0RecurringPane(session:G0RecurringInteractionSession,observation:G0ScreenObservation) {
    val state=session.state.collectAsState().value
    MaterialTheme {
        Column(Modifier.fillMaxWidth().height(136.dp).padding(horizontal=20.dp).drawWithContent {
            if(state.renderable()) drawContent() else drawRect(Color.Black)
        }) {
            Box(Modifier.fillMaxWidth().height(12.dp).onGloballyPositioned { observation.placed(it.boundsInWindow()) }
                .drawWithContent {
                    if(state.renderable()) {
                        val width=size.width/26
                        for(i in 0 until 26) {
                            val color=when(i) { 0->Color.Magenta;25->Color.Cyan
                                else->if((observation.encodedVersion(state.version) shr(i-1)) and 1L==1L) Color.White else Color.Black }
                            drawRect(color,Offset(i*width,0f),Size(width+0.1f,size.height))
                        }
                        observation.drawn(state.version)
                    }
                })
            val content=state.publication?.takeIf { state.renderable() }?.content
            if(content!=null) {
                Text("计数今日：${content.counts.values.single().todayQuantity}")
                Text("打卡今日：${content.checks.values.single().todayQuantity}")
            } else Text(when(state.phase) {
                G0RecurringInteractionSession.Phase.RECOVERY->"需要重新核验"
                G0RecurringInteractionSession.Phase.CANCELLED->"读取已取消"
                G0RecurringInteractionSession.Phase.CLOSED->"尚未打开"
                else->"正在核验计数与打卡"
            })
            Row {
                Button(onClick={observation.clicked("open");session.open()},
                    modifier=Modifier.onGloballyPositioned { observation.bounds["open"]=it.boundsInWindow() }) { Text("打开记录") }
                Button(onClick={observation.clicked("cancel");session.cancel()},
                    modifier=Modifier.onGloballyPositioned { observation.bounds["cancel"]=it.boundsInWindow() }) { Text("取消读取") }
            }
        }
    }
}

/** Compose virtual text nodes are traversed like the existing editor's real text input.
 * Accessibility propagation may follow drawing; this check never substitutes DTOs for visible text.
 */
internal suspend fun requireVisiblePageText(expected:Set<String>) {
    var observed=emptySet<String>()
    val found=kotlinx.coroutines.withTimeoutOrNull(2_000) {
        while(true) {
            val texts=mutableSetOf<String>()
            fun visit(node:android.view.accessibility.AccessibilityNodeInfo) {
                if(node.isVisibleToUser) node.text?.toString()?.let(texts::add)
                for(i in 0 until node.childCount) node.getChild(i)?.let(::visit)
            }
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow?.let(::visit)
            observed=texts
            if(texts.containsAll(expected)) return@withTimeoutOrNull true
            kotlinx.coroutines.delay(16)
        }
        @Suppress("UNREACHABLE_CODE") false
    }
    check(found==true) { "G0_VISIBLE_TEXT_MISSING expected=$expected observed=$observed" }
}
