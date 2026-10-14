package com.dayforge.data.repository

import android.content.Intent
import android.net.Uri
import java.security.MessageDigest

/** Intent delivery retains the original scope; this payload is never authentication. */
internal object LegacyWidgetIdentityIntent {
    private const val EXTRA = "com.dayforge.widget.legacy.identity"
    fun read(intent: Intent): LegacyWidgetRestartClaim? = intent.getStringExtra(EXTRA)?.let(LegacyWidgetRestartClaim::decode)
    fun attach(intent: Intent, identity: LegacyWidgetRestartClaim) {
        val payload = identity.encode()
        val digest = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray()).joinToString("") { "%02x".format(it) }
        intent.putExtra(EXTRA, payload)
        intent.data = Uri.Builder().scheme("dayforge-widget-legacy").authority(digest)
            .appendPath(intent.component?.className ?: "follow-up").build()
    }
}
