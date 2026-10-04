package com.dayforge.data.appearance

import android.content.ContentResolver
import android.content.res.AssetFileDescriptor
import android.net.Uri
import android.os.CancellationSignal

/** One bounded document open, full native validation and an account-bound frozen preview. */
internal class AccountIconDocuments internal constructor(
    private val imports: AccountIconImport,
    openRead: (Uri, CancellationSignal) -> AssetFileDescriptor?,
    timeoutMillis: Long = 15_000
) {
    constructor(imports: AccountIconImport, resolver: ContentResolver) : this(
        imports, { uri, signal -> resolver.openAssetFileDescriptor(uri, "r", signal) }
    )

    private val documents = AppearanceDocuments(openRead, { _, _ -> error("Icon preview cannot write a document") },
        "ICON_DOCUMENT", timeoutMillis)

    suspend fun preview(uri: Uri): AccountIconPackPreview = imports.previewArchive {
        documents.read(uri, ::freezeIconArchive, ValidatedIconPack::parse)
    }
}
