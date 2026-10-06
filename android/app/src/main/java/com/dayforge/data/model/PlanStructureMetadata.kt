package com.dayforge.data.model

import com.dayforge.domain.model.isContractUuid
import com.dayforge.domain.model.ContractLongSerializer
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.serialization.Serializable

/** Structure fields not represented by the legacy editor; never infer them from the current device. */
@Serializable
data class PlanStructureMetadata(
    val creationTimestamp: String,
    @Serializable(with = ContractLongSerializer::class)
    val sortOrder: Long,
    val startDate: String?,
    val goalDueDate: String?,
    val timezone: String?,
    val targetUnit: String?,
    val preferredLocalTime: String?,
    val originAssignmentId: String?
) {
    init {
        require(Instant.parse(creationTimestamp).atZone(ZoneId.of("UTC")).year in 1..9999)
        require(validPlanDate(startDate) && validPlanDate(goalDueDate))
        require(startDate == null || goalDueDate == null || startDate <= goalDueDate)
        require(timezone == null || (timezone.length <= 64 && timezone in ZoneId.getAvailableZoneIds()))
        require(targetUnit == null || targetUnit.codePointCount(0, targetUnit.length) <= 50)
        preferredLocalTime?.let { require(runCatching { parsePlanTime(it) }.isSuccess) }
        require(originAssignmentId == null || isContractUuid(originAssignmentId))
    }
}

// Preserve the original ISO time (including any supplied offset); never apply the device timezone.
internal fun parsePlanTime(value: String): LocalTime = LocalTime.parse(value, DateTimeFormatter.ISO_TIME)

internal fun validPlanDate(value: String?): Boolean = value == null ||
    (Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}").matches(value) &&
        runCatching { LocalDate.parse(value).year in 1..9999 }.getOrDefault(false))
