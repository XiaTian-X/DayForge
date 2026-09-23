package com.dayforge.data.api

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dayforge.data.api.dto.*
import com.dayforge.domain.model.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NextApiContractTest {
    private val json = Json
    private val fixture by lazy {
        InstrumentationRegistry.getInstrumentation().context.assets.open("next/api.json")
            .bufferedReader().use { json.parseToJsonElement(it.readText()).jsonObject }
    }
    private val declaration get() = json.decodeFromJsonElement<AssetDeclaration>(fixture.getValue("asset_declaration"))

    private fun parse(model: String, raw: JsonElement): Any = when (model) {
        "task" -> validateNextAppearancePayload(raw.jsonObject)
        "metric" -> validateNextAppearancePayload(raw.jsonObject, true)
        "push" -> json.decodeFromJsonElement<NextSyncPushRequest>(raw).also { it.operations.forEach(::validateNextSyncOperation) }
        "bootstrap" -> json.decodeFromJsonElement<NextSyncBootstrapResponse>(raw)
        "conflict", "accepted" -> json.decodeFromJsonElement<NextSyncOperationResult>(raw)
        "asset_declaration" -> json.decodeFromJsonElement<AssetDeclaration>(raw)
        "asset_record" -> json.decodeFromJsonElement<AssetRecord>(raw)
        "receipt" -> json.decodeFromJsonElement<AssetTransferReceipt>(raw)
        "pack_declaration" -> json.decodeFromJsonElement<PackDeclaration>(raw)
        "catalog" -> json.decodeFromJsonElement<AppearanceCatalogPage>(raw)
        "quota" -> json.decodeFromJsonElement<AppearanceQuota>(raw)
        else -> error("Unknown shared vector model $model")
    }

    @Test fun validWireValuesPreserveTaskAndMetadataMeaning() {
        listOf("task", "metric", "push", "bootstrap", "conflict", "accepted", "asset_declaration", "asset_record", "receipt", "pack_declaration", "catalog", "quota")
            .forEach { parse(it, fixture.getValue(it)) }
        val bootstrap = parse("bootstrap", fixture.getValue("bootstrap")) as NextSyncBootstrapResponse
        assertEquals(1, bootstrap.oneTimeCheckpoints.single().state.version)
        assertEquals(7L, bootstrap.nextCursor)
        assertEquals(128, declaration.asset.light.byteLength)
        assertEquals(3L, (parse("catalog", fixture.getValue("catalog")) as AppearanceCatalogPage).throughSequence)
        assertEquals(640L, (parse("quota", fixture.getValue("quota")) as AppearanceQuota).reservedBytes)
    }

    @Test fun invalidSharedVectorsAreRejected() {
        val cases = fixture.getValue("invalid").jsonArray
        assertEquals(45, cases.size)
        cases.forEach { raw ->
            val case = raw.jsonObject
            val model = case.getValue("model").jsonPrimitive.content
            assertThrows(case.getValue("name").toString(), RuntimeException::class.java) {
                parse(model, mutate(fixture.getValue(model), case))
            }
        }
    }

    @Test fun oneBadDomainOperationDoesNotRejectTheWholeEnvelope() {
        val original = json.decodeFromJsonElement<NextSyncPushRequest>(fixture.getValue("push"))
        val first = original.operations.single()
        val invalid = first.copy(operationId = declaration.context.deviceId,
            payload = JsonObject(first.payload + ("one_time_state_after" to JsonNull)))
        val envelope = original.copy(operations = listOf(first, invalid))
        assertEquals(2, json.decodeFromString<NextSyncPushRequest>(json.encodeToString(NextSyncPushRequest.serializer(), envelope)).operations.size)
        validateNextSyncOperation(envelope.operations[0])
        assertThrows(IllegalArgumentException::class.java) { validateNextSyncOperation(envelope.operations[1]) }
    }

    @Test fun responsesCannotSwitchEpochDeviceContentOrOperation() {
        val cases = fixture.getValue("bindings").jsonArray
        assertEquals(22, cases.size)
        cases.forEach { raw ->
            val case = raw.jsonObject
            val block = {
                when (case.getValue("type").jsonPrimitive.content) {
                    "asset" -> validateAssetRecordBinding(declaration, json.decodeFromJsonElement(mutate(fixture.getValue("asset_record"), case)))
                    "transfer" -> validateTransferBinding(declaration, "light", json.decodeFromJsonElement(mutate(fixture.getValue("receipt"), case)))
                    "pack" -> validatePackBinding(json.decodeFromJsonElement(fixture.getValue("pack_declaration")),
                        json.decodeFromJsonElement(mutate(fixture.getValue("pack_declaration"), case)))
                    "task" -> validateTaskResultBinding(json.decodeFromJsonElement<NextSyncPushRequest>(fixture.getValue("push")).operations.single(),
                        json.decodeFromJsonElement(mutate(fixture.getValue("conflict"), case)))
                    "accepted_task" -> validateTaskResultBinding(json.decodeFromJsonElement<NextSyncPushRequest>(fixture.getValue("push")).operations.single(),
                        json.decodeFromJsonElement(mutate(fixture.getValue("accepted"), case)))
                    "catalog" -> validateCatalogBinding(declaration.context, 0, null, json.decodeFromJsonElement(mutate(fixture.getValue("catalog"), case)))
                    else -> error("Unknown binding case")
                }
            }
            if (case.getValue("valid").jsonPrimitive.boolean) block()
            else assertThrows(case.getValue("name").toString(), RuntimeException::class.java, block)
        }
    }

    @Test fun catalogsRespectFrozenWatermarksAndFullLongRange() {
        val page = json.decodeFromJsonElement<AppearanceCatalogPage>(fixture.getValue("catalog"))
        val first = page.copy(entries = page.entries.take(1), nextCursor = 1, hasMore = true)
        validateCatalogBinding(declaration.context, 0, 3, first)
        listOf(1L to 3L, 0L to 4L, -1L to 3L, 4L to 3L).forEach { (after, through) ->
            assertThrows(IllegalArgumentException::class.java) { validateCatalogBinding(declaration.context, after, through, first) }
        }
        val last = page.copy(entries = emptyList(), nextCursor = Long.MAX_VALUE, throughSequence = Long.MAX_VALUE)
        assertEquals(last, json.decodeFromString<AppearanceCatalogPage>(json.encodeToString(AppearanceCatalogPage.serializer(), last)))
        val lowered = json.decodeFromJsonElement<AppearanceQuota>(fixture.getValue("quota")).copy(byteLimit = 1)
        assertEquals(640L, lowered.reservedBytes)
    }

    @Test fun variantReadinessMatchesDeclaredImmutableBlobs() {
        val same = declaration.asset.copy(dark = declaration.asset.light)
        AssetRecord(declaration.context, same, emptyList())
        AssetRecord(declaration.context, same, listOf("light", "dark"))
        assertThrows(IllegalArgumentException::class.java) { AssetRecord(declaration.context, same, listOf("light")) }
        val receipt = json.decodeFromJsonElement<AssetTransferReceipt>(fixture.getValue("receipt")).copy(variant = "dark")
        assertThrows(IllegalArgumentException::class.java) { validateTransferBinding(declaration, "dark", receipt) }
    }

    @Test fun newItemsRequireAnExplicitZeroCheckpoint() {
        val original = json.decodeFromJsonElement<NextSyncBootstrapResponse>(fixture.getValue("bootstrap"))
        val empty = original.copy(changes = original.changes.take(1), oneTimeCheckpoints = listOf(
            original.oneTimeCheckpoints.single().copy(state = OneTimeState(0, null, null))))
        assertEquals(0, empty.oneTimeCheckpoints.single().state.version)
        assertThrows(IllegalArgumentException::class.java) { empty.copy(oneTimeCheckpoints = emptyList()) }
        val node = original.changes.first()
        val body = node.payload.toMutableMap()
        body["activity"] = JsonObject(body.getValue("activity").jsonObject + mapOf(
            "completion_policy" to JsonPrimitive("recurring"), "recurrence_rule" to buildJsonObject { put("type", "daily"); put("schema_version", 1) }))
        body["appearance"] = JsonObject(body.getValue("appearance").jsonObject + ("icon" to buildJsonObject { put("kind", "role"); put("role", "habit.water") }))
        assertThrows(IllegalArgumentException::class.java) {
            original.copy(changes = listOf(node.copy(payload = JsonObject(body)), original.changes[1]), oneTimeCheckpoints = emptyList())
        }
    }

    @Test fun pullRejectsWrongProofButAllowsIndependentEntityOrder() {
        val bootstrap = json.decodeFromJsonElement<NextSyncBootstrapResponse>(fixture.getValue("bootstrap"))
        val event = bootstrap.changes[1]
        NextSyncPullResponse(listOf(event), 7, false, bootstrap.serverTime)
        assertThrows(IllegalArgumentException::class.java) {
            NextSyncPullResponse(listOf(event.copy(entityUuid = declaration.context.deviceId)), 7, false, bootstrap.serverTime)
        }
    }

    private fun mutate(source: JsonElement, case: JsonObject): JsonElement {
        val path = case.getValue("path").jsonArray
        if (path.isEmpty()) return source
        return replace(source, path, case.getValue("value"), case["operation"]?.jsonPrimitive?.content == "remove")
    }

    private fun replace(source: JsonElement, path: List<JsonElement>, value: JsonElement, remove: Boolean): JsonElement {
        if (path.isEmpty()) return value
        val key = path.first().jsonPrimitive
        if (source is JsonArray) return JsonArray(source.mapIndexed { index, item ->
            if (index == key.int) replace(item, path.drop(1), value, remove) else item
        })
        val map = source.jsonObject.toMutableMap()
        if (remove && path.size == 1) map.remove(key.content)
        else map[key.content] = replace(map[key.content] ?: JsonNull, path.drop(1), value, remove)
        return JsonObject(map)
    }
}
