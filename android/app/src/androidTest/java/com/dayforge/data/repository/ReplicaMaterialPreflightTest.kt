package com.dayforge.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.api.dto.*
import com.dayforge.data.appearance.*
import com.dayforge.domain.model.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Metadata rules in the physical testbed, not server/native byte or activation proof. */
@RunWith(AndroidJUnit4::class)
class ReplicaMaterialPreflightTest : NextCoreRequestFixture() {
    private val blob = IconBlob("a".repeat(64), 96, "image/png", 24, 24)
    private val asset get() = IconAsset(id(100), "Exact asset", "general", "template", blob, blob)
    private val pack get() = IconPack("dayforge.icon-pack", 1, id(101), 1, "Exact style", listOf(asset), emptyMap(), null)
    private fun original(selection: AccountIconSelection = AccountIconSelection(0, null),
        packs: List<IconPack> = emptyList()) = AccountIconRecoveryMetadata(selection, emptyList(), packs, emptyList())
    private suspend fun response(selected: IconPack? = null, requested: RecoveryPackVersion? = selected?.let {
        RecoveryPackVersion(it.packId, it.revision)
    }, assets: List<AssetRecord> = selected?.assets.orEmpty().map { record(it) }, missing: List<String> = emptyList(),
        direct: String? = null): ReplicaRecoveryPreviewResponse {
        val body = NextStructureMapper.writeMetric(if (direct == null) metric else metric.copy(
            appearance = metric.appearance!!.copy(icon = IconReference.Asset(direct))))
        val change = SyncV2Change(0, "metric", metric.uuid, "upsert", 1, JsonObject(body + mapOf(
            "public_id" to JsonPrimitive(metric.uuid), "revision" to JsonPrimitive(1),
            "created_at" to JsonPrimitive(time), "updated_at" to JsonPrimitive(time), "deleted_at" to JsonNull)), time)
        return ReplicaRecoveryPreviewResponse(1, 5, id(1), id(2), id(99),
            RecoveryDeviceProof(id(4), tokens.getOrCreateInstallationId(), "android", "interactive", null, null,
                false, false, 1, listOf("sync.read"), time),
            RoundSyncBootstrapResponse(listOf(change), 20, time, emptyList(), 1, emptyList(), emptyList()), null,
            ReplicaRecoveryMaterialPreview(1, requested, selected, assets, missing))
    }
    private fun record(value: IconAsset = asset, ready: List<String> = listOf("light", "dark")) =
        AssetRecord(AssetSyncContext(id(2), id(99), id(4)), value, ready)

    @Test fun defaultAndExplicitNoPackKeepTheirIntentAndUnresolvedRoles() = runBlocking<Unit> {
        for (generation in listOf(0L, 7L)) {
            val selection = AccountIconSelection(generation, null)
            val proof = ReplicaMaterialPreflight.checked(response(), original(selection))
            proof.requireAvailable(); assertEquals(selection, proof.selection)
            assertEquals(setOf("metric.custom"), proof.unresolvedRoles)
            assertFalse(proof.activationAuthorized); assertFalse(proof.nativeFilesVerified)
        }
    }
    @Test fun exactSelectedPackRequiresAllAssetsEvenWhenNotReferenced() = runBlocking<Unit> {
        val selection = AccountIconSelection(9, IconPackVersion(pack.packId, 1))
        val proof = ReplicaMaterialPreflight.checked(response(pack), original(selection, listOf(pack)))
        proof.requireAvailable(); assertEquals(selection, proof.selection)
        assertTrue(proof.pendingVariants.isEmpty()); assertFalse(proof.nativeFilesVerified)
    }
    @Test fun missingSelectedVersionCannotFallBackToNoPackOrAnotherRevision() = runBlocking<Unit> {
        val selection = AccountIconSelection(2, IconPackVersion(pack.packId, 1))
        val proof = ReplicaMaterialPreflight.checked(response(requested = RecoveryPackVersion(pack.packId, 1)),
            original(selection, listOf(pack)))
        assertTrue(proof.missingPack); assertEquals(selection, proof.selection)
        assertEquals("ICON_RECOVERY_PACK_MISSING", rejected { proof.requireAvailable() }.message)
    }
    @Test fun sameVersionWithChangedFullMetadataIsNotAnEquivalentPack() = runBlocking<Unit> {
        val selection = AccountIconSelection(1, IconPackVersion(pack.packId, 1))
        val proof = ReplicaMaterialPreflight.checked(response(pack.copy(name = "Changed style")), original(selection, listOf(pack)))
        assertTrue(proof.changedPack)
        assertEquals("ICON_RECOVERY_PACK_CHANGED", rejected { proof.requireAvailable() }.message)
    }
    @Test fun directAndPackReferencesCannotDisappearFromThePartition() = runBlocking<Unit> {
        val direct = response(direct = asset.assetId, missing = listOf(asset.assetId))
        val proof = ReplicaMaterialPreflight.checked(direct, original())
        assertEquals(setOf(asset.assetId), proof.missingAssets)
        val failure = rejected { proof.requireAvailable() }
        assertEquals("ICON_RECOVERY_REFERENCE_MISSING", failure.message)
        assertSame(proof, (failure as ReplicaMaterialUnavailable).diagnostics)
        assertNotNull(rejected { direct.copy(materialPreview = direct.materialPreview!!.copy(missingAssets = emptyList())) })
    }
    @Test fun declaredDarkVariantMustBeReadyAndMetadataStillIsNotByteProof() = runBlocking<Unit> {
        val separate = asset.copy(dark = blob.copy(sha256 = "b".repeat(64)))
        val selected = pack.copy(assets = listOf(separate))
        val selection = AccountIconSelection(1, IconPackVersion(pack.packId, 1))
        val proof = ReplicaMaterialPreflight.checked(response(selected, assets = listOf(record(separate, listOf("light")))),
            original(selection, listOf(selected)))
        assertEquals(mapOf(asset.assetId to setOf("dark")), proof.pendingVariants)
        assertEquals("ICON_RECOVERY_CONTENT_PENDING", rejected { proof.requireAvailable() }.message)
        assertFalse(proof.nativeFilesVerified)
    }
    @Test fun taskAssetCannotBeUsedByAnOrdinaryMetric() = runBlocking<Unit> {
        val task = asset.copy(purpose = "task")
        assertEquals("ICON_RECOVERY_PURPOSE_INVALID", rejected {
            ReplicaMaterialPreflight.checked(response(assets = listOf(record(task)), direct = task.assetId), original())
        }.message)
    }
    @Test fun diagnosticsOwnTheirCollectionsAndCannotBeTurnedIntoAuthority() = runBlocking<Unit> {
        val proof = ReplicaMaterialPreflight.checked(response(direct = asset.assetId, missing = listOf(asset.assetId)), original())
        assertTrue(rejected { (proof.missingAssets as MutableSet<String>).clear() } is UnsupportedOperationException)
        assertTrue(rejected { (proof.pendingVariants as MutableMap<String, Set<String>>).clear() } is UnsupportedOperationException)
        assertTrue(rejected { (proof.unresolvedRoles as MutableSet<String>).clear() } is UnsupportedOperationException)
        assertEquals(setOf(asset.assetId), proof.missingAssets)
        assertFalse(proof.activationAuthorized)
    }
}
