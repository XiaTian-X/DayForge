@file:kotlinx.serialization.UseSerializers(ContractStringSerializer::class)

package com.dayforge.domain.model

import java.time.LocalDate
import java.time.ZoneId
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

@Serializable
@OptIn(ExperimentalSerializationApi::class)
@JsonClassDiscriminator("type")
sealed interface ConfigSchedule {
    @Serializable @SerialName("daily")
    data class Daily(@SerialName("start_date") val startDate: String?) : ConfigSchedule {
        init { require(isConfigDay(startDate)) }
    }

    @Serializable @SerialName("weekly")
    data class Weekly(
        @SerialName("start_date") val startDate: String?,
        val weekdays: List<@Serializable(with = ContractIntegerSerializer::class) Int>
    ) : ConfigSchedule {
        init {
            require(isConfigDay(startDate))
            require(weekdays.isNotEmpty() && weekdays.size <= 7)
            require(weekdays.all { it in 1..7 } && weekdays.distinct().size == weekdays.size)
        }
    }

    @Serializable @SerialName("monthly")
    data class Monthly(
        @SerialName("start_date") val startDate: String?,
        @Serializable(with = ContractIntegerSerializer::class)
        @SerialName("day_of_month") val dayOfMonth: Int
    ) : ConfigSchedule {
        init { require(isConfigDay(startDate) && dayOfMonth in 1..31) }
    }

    @Serializable @SerialName("interval")
    data class Interval(
        @SerialName("start_date") val startDate: String,
        @Serializable(with = ContractIntegerSerializer::class)
        @SerialName("every_days") val everyDays: Int
    ) : ConfigSchedule {
        init { require(isConfigDay(startDate) && everyDays in 1..3650) }
    }

    @Serializable @SerialName("once")
    data class Once(@SerialName("due_date") val dueDate: String?) : ConfigSchedule {
        init { require(isConfigDay(dueDate)) }
    }
}

@Serializable
data class ConfigAppearance(
    val icon: IconReference,
    @SerialName("accent_color") val accentColor: String,
    @SerialName("icon_tint") val iconTint: String
) {
    init {
        require(isAccentColor(accentColor))
        require(iconTint == "theme" || iconTint == "object")
    }
}

@Serializable
data class ConfigActivity(
    @SerialName("tracking_mode") val trackingMode: String,
    @SerialName("completion_policy") val completionPolicy: String,
    @Serializable(with = ContractBooleanSerializer::class)
    @SerialName("is_countdown") val isCountdown: Boolean,
    @Serializable(with = ContractIntegerSerializer::class)
    @SerialName("target_value") val targetValue: Int,
    @Serializable(with = ContractIntegerSerializer::class)
    @SerialName("target_cycles") val targetCycles: Int?,
    @SerialName("fail_mode") val failMode: String,
    @Serializable(with = ContractIntegerSerializer::class)
    @SerialName("preferred_minute") val preferredMinute: Int?,
    val timezone: String,
    val schedule: ConfigSchedule
) {
    init {
        require(trackingMode in setOf("check", "count", "duration"))
        require(completionPolicy in setOf("recurring", "one_and_done"))
        require(failMode == "strict" || failMode == "loose")
        require(targetValue >= 0 && (targetCycles == null || targetCycles > 0))
        require(preferredMinute == null || preferredMinute in 0..1439)
        require(timezone.length in 1..64 && timezone in ZoneId.getAvailableZoneIds())
        val once = completionPolicy == "one_and_done"
        require(once == (schedule is ConfigSchedule.Once))
        require(trackingMode != "check" || !isCountdown)
        require(trackingMode == "check" || targetValue > 0)
        require(trackingMode != "duration" || (targetValue % 60 == 0 && targetValue <= 2_147_483_640))
        require(!once || (trackingMode == "check" && targetValue == 1 && targetCycles == null &&
            failMode == "loose" && preferredMinute == null))
    }
}

@Serializable
data class ConfigGoal(
    @SerialName("start_date") val startDate: String?,
    @SerialName("due_date") val dueDate: String?,
    @Serializable(with = ContractIntegerSerializer::class)
    @SerialName("target_cycles") val targetCycles: Int?,
    @SerialName("fail_mode") val failMode: String
) {
    init {
        require(isConfigDay(startDate) && isConfigDay(dueDate))
        require(startDate == null || dueDate == null || startDate <= dueDate)
        require(targetCycles == null || targetCycles > 0)
        require(failMode == "strict" || failMode == "loose")
    }
}

@Serializable
data class ConfigNode(
    val key: String,
    val kind: String,
    val name: String,
    val description: String,
    @Serializable(with = ContractBooleanSerializer::class)
    @SerialName("is_active") val isActive: Boolean,
    @SerialName("parent_key") val parentKey: String?,
    val appearance: ConfigAppearance,
    val goal: ConfigGoal?,
    val activity: ConfigActivity?
) {
    init {
        require(isLocalKey(key) && (parentKey == null || isLocalKey(parentKey)))
        require(isContractName(name, 100) && description.codePointCount(0, description.length) <= 1000)
        require(kind == "goal" || kind == "activity")
        require(if (kind == "goal") parentKey == null && goal != null && activity == null
            else activity != null && goal == null)
    }
}

@Serializable
data class ConfigMetric(
    val key: String,
    val name: String,
    val description: String,
    val unit: String,
    @Serializable(with = ContractBooleanSerializer::class)
    @SerialName("is_active") val isActive: Boolean,
    @Serializable(with = ContractIntegerSerializer::class)
    @SerialName("decimal_places") val decimalPlaces: Int,
    @SerialName("aggregation_type") val aggregationType: String,
    @SerialName("target_direction") val targetDirection: String?,
    @Serializable(with = ContractNumberSerializer::class)
    @SerialName("target_value") val targetValue: Double?,
    @Serializable(with = ContractNumberSerializer::class)
    @SerialName("target_value_upper") val targetValueUpper: Double?,
    val appearance: ConfigAppearance
) {
    init {
        require(isLocalKey(key) && isContractName(name, 100) && isContractName(unit, 50))
        require(description.codePointCount(0, description.length) <= 1000 && decimalPlaces in 0..6)
        require(aggregationType in setOf("average", "sum", "by_time"))
        require(targetDirection == null || targetDirection in setOf("increase", "decrease", "range"))
        require(targetValue == null || targetValue.isFinite())
        require(targetValueUpper == null || targetValueUpper.isFinite())
        require(targetDirection != "range" || (targetValue != null && targetValueUpper != null && targetValueUpper >= targetValue))
    }
}

@Serializable
data class ConfigLink(
    val key: String,
    @SerialName("activity_key") val activityKey: String,
    @SerialName("metric_key") val metricKey: String,
    @Serializable(with = ContractNumberSerializer::class)
    val coefficient: Double,
    @Serializable(with = ContractBooleanSerializer::class)
    @SerialName("show_in_detail") val showInDetail: Boolean,
    @Serializable(with = ContractBooleanSerializer::class)
    @SerialName("prompt_on_complete") val promptOnComplete: Boolean,
    @Serializable(with = ContractBooleanSerializer::class)
    @SerialName("is_active") val isActive: Boolean
) {
    init { require(isLocalKey(key) && isLocalKey(activityKey) && isLocalKey(metricKey) && coefficient.isFinite()) }
}

/** File-local identities are references only, never authorization to upsert an account's existing data. */
@Serializable
data class ConfigBundle(
    val format: String,
    @Serializable(with = ContractIntegerSerializer::class)
    @SerialName("format_version") val formatVersion: Int,
    val nodes: List<ConfigNode>,
    val metrics: List<ConfigMetric>,
    val links: List<ConfigLink>,
    @SerialName("icon_pack") val iconPack: IconPack?,
    @SerialName("unresolved_roles") val unresolvedRoles: List<String>,
    val themes: List<ThemeDefinition>
) {
    init {
        require(format == "dayforge.config" && formatVersion == 2)
        require(nodes.size <= 1000 && metrics.size <= 1000 && links.size <= 5000)
        require(unresolvedRoles.size <= 256 && themes.size <= 16)
        val keys = nodes.map { it.key } + metrics.map { it.key } + links.map { it.key }
        require(keys.distinct().size == keys.size)
        require(nodes.map { it.name }.distinct().size == nodes.size)
        require(metrics.map { it.name }.distinct().size == metrics.size)
        val byKey = nodes.associateBy { it.key }
        val metricKeys = metrics.map { it.key }.toSet()
        require(nodes.all { it.parentKey == null || byKey[it.parentKey]?.kind == "goal" })
        require(links.map { it.activityKey to it.metricKey }.distinct().size == links.size)
        require(links.all { byKey[it.activityKey]?.kind == "activity" && it.metricKey in metricKeys })
        val assets = iconPack?.assets?.associateBy { it.assetId }.orEmpty()
        val roles = iconPack?.roles.orEmpty()
        val unresolved = unresolvedRoles.toSet()
        require(unresolved.size == unresolvedRoles.size && unresolved.intersect(roles.keys).isEmpty())
        unresolved.forEach { IconReference.Role(it) }
        val usedRoles = mutableSetOf<String>()
        fun validateIcon(icon: IconReference, once: Boolean) {
            when (icon) {
                is IconReference.Role -> {
                    usedRoles += icon.role
                    require(icon.role in roles || icon.role in unresolved)
                    require(iconAllowed(icon, once))
                }
                is IconReference.Asset -> require(iconAllowed(icon, once, assets[icon.assetId]))
            }
        }
        nodes.forEach { validateIcon(it.appearance.icon, it.activity?.completionPolicy == "one_and_done") }
        metrics.forEach { validateIcon(it.appearance.icon, false) }
        require(unresolved.all { it in usedRoles })
        require(themes.map { it.themeId to it.revision }.distinct().size == themes.size)
    }
}

private val localKey = Regex("[a-z][a-z0-9_-]{0,63}")
private val configDay = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")
private fun isLocalKey(value: String): Boolean = localKey.matches(value)
private fun isConfigDay(value: String?): Boolean = value == null ||
    (configDay.matches(value) && runCatching { LocalDate.parse(value).year in 1..9999 }.getOrDefault(false))
