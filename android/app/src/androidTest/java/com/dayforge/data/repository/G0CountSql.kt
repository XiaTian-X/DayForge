package com.dayforge.data.repository

/** Explicit G0 count capability. Disabled for every existing metric owner. No DAO wildcard. */
internal object G0CountSql {
    const val insert = "INSERT OR ABORT INTO completions (id,habitId,date,value,actualCompletedAt,uuid,habitUuid,createdAt,recordedTimezone,recordedLocalDate,timeMetadataSource,oneTimeAction,oneTimeExpectedVersion,oneTimeExpectedHeadEventUuid,oneTimeRevertsEventUuid) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"
    const val rate = "UPDATE habits SET activityRate=?,activityRateUpdatedAt=? WHERE id=?"
    const val acceptedTime = "UPDATE completions SET createdAt=? WHERE id=?"
    val writes = mapOf(insert to "VVVVVVVVVVVVVVV", rate to "VII", acceptedTime to "II")
}
