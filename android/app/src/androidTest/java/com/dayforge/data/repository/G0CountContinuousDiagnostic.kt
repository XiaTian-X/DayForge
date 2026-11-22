package com.dayforge.data.repository

import android.util.Log
import java.lang.reflect.Modifier
import kotlinx.serialization.json.*

/** Field names and counters only; never log personal DTO values or credentials. */
internal fun diagnoseContinuousPage(flow:G0CountJourney,home:G0CountDashboard) {
    val state=flow.state.value;val page=home.state.value
    val a=state.view;val b=page.batch?.habits?.singleOrNull {it.id==a?.habit?.id}
    fun differences(x:Any?,y:Any?):List<String> {
        if(x==null || y==null) return listOf("missing")
        check(x.javaClass==y.javaClass)
        return x.javaClass.declaredFields.filter { !it.isSynthetic && !Modifier.isStatic(it.modifiers) }.mapNotNull {
            it.isAccessible=true
            if(it.get(x)!=it.get(y)) it.name else null
        }
    }
    Log.i("G0CountContinuousMismatch",buildJsonObject {
        put("command_phase",state.phase.name);put("command_renderable",state.renderable());put("home_valid",page.valid())
        put("home_error",page.error?.javaClass?.simpleName ?: "none")
        put("habit_fields",JsonArray(differences(a?.habit,b).map(::JsonPrimitive)))
        put("history_fields",JsonArray(differences(a?.countHistory,page.batch?.counts?.get(a?.habit?.id)).map(::JsonPrimitive)))
        val oldFacts=a?.countHistory?.completions.orEmpty()
        val newFacts=page.batch?.counts?.get(a?.habit?.id)?.completions.orEmpty()
        put("fact_order_matches",oldFacts.map {it.uuid}==newFacts.map {it.uuid})
        put("fact_members_match",oldFacts.map {it.uuid}.toSet()==newFacts.map {it.uuid}.toSet())
        put("fact_fields",JsonArray(oldFacts.flatMap {old->
            differences(old,newFacts.singleOrNull {it.uuid==old.uuid})
        }.distinct().map(::JsonPrimitive)))
        put("home_reads",home.reads.get());put("home_publications",home.publications.get());put("home_invalidations",home.invalidations.get())
        put("home_events",buildJsonArray {home.events.takeLast(8).forEach {e->add(buildJsonObject {
            put("kind",e.kind);put("expected",e.expected);put("current",e.current)
        })}})
    }.toString())
}
