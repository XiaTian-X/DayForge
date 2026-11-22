package com.dayforge.data.repository

import com.dayforge.data.local.entity.MetricEntity
import com.dayforge.domain.model.*
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.json.*

/** G0-only fused equivalent of readMetric(payload, uuid, revision, previous=null).
 * Validate and project each field once. One bounded, exact JSON appearance memo contains only
 * pure domain values, never SQL evidence or authority. The cold owner replaces this per block.
 */
internal class G0ColdMetricNormalization(private val reusePureValues: Boolean = true) {
    private val instants = linkedMapOf<String, Long>()
    private fun instant(text: String): Long {
        if (reusePureValues) instants[text]?.let { return phase("instant_reuse") { it } }
        val result = phase("instant_parse") {
            Instant.parse(text).also { require(it.atZone(ZoneId.of("UTC")).year in 1..9999) }.toEpochMilli()
        }
        // Pure exact-string result only: no normalized alias, failed parse or unbounded key.
        if (reusePureValues && text.length <= 64) {
            if (instants.size == 4) instants.remove(instants.keys.first())
            instants[text] = result
        }
        return result
    }
    private var appearanceInput: JsonElement? = null
    private var appearanceValue: ObjectAppearance? = null
    private val fields = setOf("public_id","revision","created_at","updated_at","deleted_at",
        "name","description","unit","decimal_places","aggregation_type","target_direction",
        "target_value","target_value_upper","status","appearance")

    private fun <T> phase(name: String, block: () -> T): T {
        val trace=G0PhaseTrace.current.get()
        return if(trace==null) block() else trace.call("cold_normalize_$name",block)
    }
    fun read(payload: JsonObject, uuid: String, revision: Long): MetricEntity {
        val times=phase("header") {
            require(isContractUuid(uuid) && revision>0 && payload.keys==fields)
            require(payload.text("public_id")==uuid && contractLongOrNull(payload.getValue("revision"))==revision)
            require(payload["deleted_at"]==JsonNull)
            instant(payload.text("created_at")) to instant(payload.text("updated_at"))
        }
        val name=payload.text("name");val unit=payload.text("unit");val description=payload.text("description")
        val places=requireNotNull(contractLongOrNull(payload.getValue("decimal_places")))
        val aggregation=payload.text("aggregation_type");val direction=payload.optionalText("target_direction")
        val status=payload.text("status")
        phase("fields") {
            require(isContractName(name,100) && isContractName(unit,50) && description.codePointCount(0,description.length)<=1000)
            require(places in 0..6 && aggregation in setOf("average","sum","by_time"))
            require(direction==null || direction in setOf("increase","decrease","range"))
            require(status in setOf("active","archived"))
        }
        val numbers=phase("numbers") {
            fun decimal(key:String):BigDecimal? = payload.getValue(key).let {
                if(it==JsonNull) null else { require(it is JsonPrimitive);requireNotNull(it.content.toBigDecimalOrNull()) }
            }
            fun exact(value:BigDecimal?)=value?.let { n -> n.toDouble().also { require(it.isFinite() && BigDecimal.valueOf(it).compareTo(n)==0) } }
            val lower=decimal("target_value");val upper=decimal("target_value_upper")
            val values=exact(lower) to exact(upper)
            if(direction=="range") require(lower!=null && upper!=null && lower<=upper)
            values
        }
        val appearance=phase("appearance") {
            val input=payload.getValue("appearance")
            if(input==appearanceInput) requireNotNull(appearanceValue).also {
                G0PhaseTrace.current.get()?.call("cold_normalize_appearance_reuse") { Unit }
            } else Json.decodeFromJsonElement<ObjectAppearance>(input).also { value ->
                if(value.icon is IconReference.Role) require(iconAllowed(value.icon,false))
                if(input.toString().toByteArray(Charsets.UTF_8).size<=8192) { appearanceInput=input;appearanceValue=value }
            }
        }
        return MetricEntity(uuid=uuid,name=name,description=description,unit=unit,decimalPlaces=places.toInt(),
            aggregationType=aggregation,targetDirection=direction,targetValue=numbers.first,targetValueUpper=numbers.second,
            iconResId=0,colorHex="#000000",isActive=status=="active",createdAt=times.first,updatedAt=times.second,appearance=appearance)
    }
    private fun JsonObject.text(key:String):String=getValue(key).let { require(it is JsonPrimitive && it.isString);it.content }
    private fun JsonObject.optionalText(key:String):String?=if(getValue(key)==JsonNull) null else text(key)
}
