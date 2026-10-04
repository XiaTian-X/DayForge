package com.dayforge.data.appearance

import android.content.ContentResolver
import android.content.res.AssetFileDescriptor
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor

/** Theme format and errors remain separate; shared mechanics join provider I/O on cancellation. */
internal class ThemeDocuments internal constructor(
    openRead: (Uri, CancellationSignal) -> AssetFileDescriptor?,
    openWrite: (Uri, CancellationSignal) -> ParcelFileDescriptor?,
    timeoutMillis: Long = 15_000
) {
    constructor(resolver: ContentResolver) : this(
        { uri, signal -> resolver.openAssetFileDescriptor(uri, "r", signal) },
        { uri, signal -> resolver.openFileDescriptor(uri, "wt", signal) }
    )

    private val documents = AppearanceDocuments(openRead, openWrite, "THEME_DOCUMENT", timeoutMillis)

    suspend fun preview(uri: Uri): ValidatedTheme = documents.read(uri, ::readThemeBytes, ValidatedTheme::parse)

    suspend fun write(uri: Uri, theme: ValidatedTheme) = documents.write(uri, theme::exportBytes)
}
