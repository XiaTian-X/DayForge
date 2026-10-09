package com.dayforge.data.local

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Exact committed final schema identity; frozen migration source identities stay literal. */
internal fun currentHabitIdentity(): String = Json.parseToJsonElement(
    InstrumentationRegistry.getInstrumentation().context.assets
        .open("com.dayforge.data.local.HabitDatabase/16.json").bufferedReader().use { it.readText() })
    .jsonObject.getValue("database").jsonObject.getValue("identityHash").jsonPrimitive.content
