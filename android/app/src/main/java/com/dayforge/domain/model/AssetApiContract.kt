@file:kotlinx.serialization.UseSerializers(ContractStringSerializer::class, ContractLongSerializer::class, ContractBooleanSerializer::class)

package com.dayforge.domain.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/** Transport binding, not authorization. Account ownership comes from authenticated repositories. */
@Serializable
data class AssetSyncContext(
    @SerialName("server_instance_id") val serverInstanceId: String,
    @SerialName("sync_epoch") val syncEpoch: String,
    @SerialName("device_id") val deviceId: String
) {
    init { require(listOf(serverInstanceId, syncEpoch, deviceId).all(::isContractUuid)) }
}

@Serializable
data class AssetDeclaration(val context: AssetSyncContext, val asset: IconAsset)

@Serializable
data class PackDeclaration(val context: AssetSyncContext, val pack: IconPack)

@Serializable
data class AssetRecord(
    val context: AssetSyncContext,
    val asset: IconAsset,
    @SerialName("ready_variants") val readyVariants: List<String>
) {
    init {
        require(readyVariants.size <= 2 && readyVariants.distinct().size == readyVariants.size)
        require(readyVariants.all { it == "light" || it == "dark" })
        require(asset.dark != null || "dark" !in readyVariants)
        require(asset.dark != asset.light || (("light" in readyVariants) == ("dark" in readyVariants)))
    }
}

@Serializable
data class AssetTransferReceipt(
    val context: AssetSyncContext,
    @SerialName("asset_id") val assetId: String,
    val variant: String,
    val blob: IconBlob
) {
    init { require(isContractUuid(assetId) && variant in setOf("light", "dark")) }
}

@Serializable
data class AppearanceQuota(
    @SerialName("byte_limit") val byteLimit: Long,
    @SerialName("reserved_bytes") val reservedBytes: Long,
    @SerialName("asset_limit") val assetLimit: Long,
    @SerialName("reserved_assets") val reservedAssets: Long,
    @SerialName("metadata_byte_limit") val metadataByteLimit: Long,
    @SerialName("reserved_metadata_bytes") val reservedMetadataBytes: Long
) {
    init { require(listOf(byteLimit, reservedBytes, assetLimit, reservedAssets, metadataByteLimit, reservedMetadataBytes).all { it >= 0 }) }
}

@Serializable
@OptIn(ExperimentalSerializationApi::class)
@JsonClassDiscriminator("kind")
sealed interface AppearanceCatalogEntry {
    val sequence: Long

    @Serializable @SerialName("asset")
    data class Asset(override val sequence: Long, val asset: IconAsset) : AppearanceCatalogEntry {
        init { require(sequence > 0) }
    }

    @Serializable @SerialName("pack")
    data class Pack(override val sequence: Long, val pack: IconPack) : AppearanceCatalogEntry {
        init { require(sequence > 0) }
    }
}

@Serializable
data class AppearanceCatalogPage(
    val context: AssetSyncContext,
    val entries: List<AppearanceCatalogEntry>,
    @SerialName("next_cursor") val nextCursor: Long,
    @SerialName("through_sequence") val throughSequence: Long,
    @SerialName("has_more") val hasMore: Boolean
) {
    init {
        val sequences = entries.map { it.sequence }
        require(entries.size <= 100 && sequences == sequences.distinct().sorted())
        val identities = entries.map {
            when (it) {
                is AppearanceCatalogEntry.Asset -> "asset:${it.asset.assetId}"
                is AppearanceCatalogEntry.Pack -> "pack:${it.pack.packId}:${it.pack.revision}"
            }
        }
        require(identities.distinct().size == identities.size)
        require(nextCursor >= 0 && throughSequence >= nextCursor && sequences.all { it <= nextCursor })
        if (hasMore) require(sequences.isNotEmpty() && nextCursor == sequences.last() && nextCursor < throughSequence)
        else require(nextCursor == throughSequence)
    }
}

fun validateCatalogBinding(context: AssetSyncContext, after: Long, through: Long?, page: AppearanceCatalogPage) {
    require(page.context == context && after in 0..page.throughSequence)
    require(through == null || through == page.throughSequence)
    require(page.entries.all { it.sequence > after })
}

fun validateAssetRecordBinding(request: AssetDeclaration, response: AssetRecord) {
    require(request.context == response.context && request.asset == response.asset)
}

fun validatePackBinding(request: PackDeclaration, response: PackDeclaration) {
    require(request == response)
}

fun validateTransferBinding(request: AssetDeclaration, variant: String, receipt: AssetTransferReceipt) {
    require(variant in setOf("light", "dark"))
    val expected = if (variant == "light") request.asset.light else request.asset.dark
    require(request.context == receipt.context && request.asset.assetId == receipt.assetId &&
        receipt.variant == variant && expected != null && expected == receipt.blob)
}
