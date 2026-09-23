package com.dayforge.domain.model

private val contractUuid = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
private val rgbColor = Regex("#[0-9A-Fa-f]{6}")
private val accentColor = Regex("#(?:[0-9A-Fa-f]{6}|[0-9A-Fa-f]{8})")

internal fun isContractUuid(value: String): Boolean = contractUuid.matches(value)
internal fun isRgbColor(value: String): Boolean = rgbColor.matches(value)
internal fun isAccentColor(value: String): Boolean = accentColor.matches(value)
internal fun isContractName(value: String, maximum: Int = 80): Boolean = value.isNotBlank() &&
    value.codePointCount(0, value.length) <= maximum && value.none { it.isISOControl() }
