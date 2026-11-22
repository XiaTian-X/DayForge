package com.dayforge.data.local

import com.dayforge.data.repository.ObjectCreationAuthority

/** A form's original local replica/source. Legacy navigation legitimately has no next
 * protocol authority, but still needs this input to avoid moving a draft to a new replica. */
class DatabaseCreationInput internal constructor(
    internal val publication: DatabaseEpochPublication,
    val authority: ObjectCreationAuthority?,
    internal val parent: DatabaseObjectSelection? = null
)
