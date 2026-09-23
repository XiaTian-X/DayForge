package com.dayforge.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Immutable metadata contract. Parsing this does not authorize or validate the image bytes. */
@Serializable
sealed interface IconReference {
    @Serializable
    @SerialName("role")
    data class Role(val role: String) : IconReference {
        init { require(isIconRole(role)) }
    }

    @Serializable
    @SerialName("asset")
    data class Asset(@SerialName("asset_id") val assetId: String) : IconReference {
        init { require(isContractUuid(assetId)) }
    }
}

@Serializable
data class IconBlob(
    val sha256: String,
    @Serializable(with = ContractIntegerSerializer::class)
    @SerialName("byte_length") val byteLength: Int,
    @SerialName("media_type") val mediaType: String,
    @Serializable(with = ContractIntegerSerializer::class)
    val width: Int,
    @Serializable(with = ContractIntegerSerializer::class)
    val height: Int
) {
    init {
        require(Regex("[0-9a-f]{64}").matches(sha256))
        require(mediaType == "image/png" || mediaType == "image/svg+xml")
        require(byteLength in 1..if (mediaType == "image/png") 2_097_152 else 524_288)
        require(width in 1..1024 && height in 1..1024)
    }
}

@Serializable
data class IconAsset(
    @SerialName("asset_id") val assetId: String,
    val name: String,
    val purpose: String,
    @SerialName("color_mode") val colorMode: String,
    val light: IconBlob,
    val dark: IconBlob?
) {
    init {
        require(isContractUuid(assetId))
        require(isIconName(name))
        require(purpose == "general" || purpose == "task")
        require(colorMode == "template" || colorMode == "original")
    }
}

@Serializable
data class IconPack(
    val format: String,
    @Serializable(with = ContractIntegerSerializer::class)
    @SerialName("format_version") val formatVersion: Int,
    @SerialName("pack_id") val packId: String,
    @Serializable(with = ContractIntegerSerializer::class)
    val revision: Int,
    val name: String,
    val assets: List<IconAsset>,
    val roles: Map<String, String>,
    @SerialName("placeholder_asset_id") val placeholderAssetId: String?
) {
    init {
        require(format == "dayforge.icon-pack" && formatVersion == 1)
        require(isContractUuid(packId) && revision > 0)
        require(isIconName(name))
        require(assets.size in 1..128 && roles.size <= 256)
        val byId = assets.associateBy { it.assetId }
        require(byId.size == assets.size)
        val blobs = assets.flatMap { listOfNotNull(it.light, it.dark) }.groupBy { it.sha256 }
        require(blobs.values.all { variants -> variants.all { it == variants.first() } })
        require(blobs.values.sumOf { it.first().byteLength.toLong() } <= 67_108_864L)
        require(roles.all { (role, id) ->
            val asset = byId[id]
            isIconRole(role) && asset != null && role.startsWith("task.") == (asset.purpose == "task")
        })
        require(placeholderAssetId == null || byId[placeholderAssetId]?.purpose == "general")
    }
}

/** Missing metadata is unresolved. The repository must also validate authenticated ownership. */
fun iconAllowed(icon: IconReference, oneTime: Boolean, asset: IconAsset? = null): Boolean = when (icon) {
    is IconReference.Role -> icon.role.startsWith("task.") == oneTime
    is IconReference.Asset -> asset != null && asset.assetId == icon.assetId &&
        (asset.purpose == "task") == oneTime
}

private val iconRole = Regex("(habit|metric|goal|task)\\.[a-z][a-z0-9_]{0,47}")

private fun isIconRole(value: String): Boolean = iconRole.matches(value)

private fun isIconName(value: String): Boolean = value.isNotBlank() &&
    value.codePointCount(0, value.length) <= 80 && value.none { it.code < 32 }
