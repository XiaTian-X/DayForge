package com.dayforge.data.appearance

import android.content.ContentResolver
import android.content.res.AssetFileDescriptor
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor

/** Bounded SAF transport; preview is immutable, confirmation must consume it, not reopen the URI. */
internal class ConfigBundleDocuments internal constructor(
    openRead: (Uri, CancellationSignal) -> AssetFileDescriptor?,
    openWrite: (Uri, CancellationSignal) -> ParcelFileDescriptor?,
    timeoutMillis: Long = 15_000
) {
    constructor(resolver: ContentResolver) : this(
        { uri, signal -> resolver.openAssetFileDescriptor(uri, "r", signal) },
        { uri, signal -> resolver.openFileDescriptor(uri, "wt", signal) }
    )

    private val documents = AppearanceDocuments(openRead, openWrite, "CONFIG_DOCUMENT", timeoutMillis)

    suspend fun preview(uri: Uri): ValidatedConfigBundle =
        documents.read(uri, ::freezeConfigArchive, ValidatedConfigBundle::parse)

    /** Only a fully generated/validated frozen bundle can reach the provider writer. */
    suspend fun write(uri: Uri, bundle: ValidatedConfigBundle) = documents.write(uri, bundle::exportBytes)
}
