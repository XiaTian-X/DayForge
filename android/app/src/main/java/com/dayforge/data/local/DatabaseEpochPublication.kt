package com.dayforge.data.local

/** Memory-only lifetime gate. This is never account, business, read-proof or write authority. */
class DatabaseEpochPublication internal constructor(
    private val owner: Any,
    private val current: () -> Boolean,
    private val publishCurrent: (() -> Unit) -> Boolean,
    private val sourceFence: (() -> Boolean) -> Boolean
) {
    internal fun belongsTo(expectedOwner: Any): Boolean = owner === expectedOwner
    internal fun isCurrentInMemory(): Boolean = current()
    /** Assignment must be synchronous and must not enter SQL, account locks or user callbacks. */
    internal fun publish(assignment: () -> Unit): Boolean = publishCurrent(assignment)
    /** Stateful producers acquire the original account memory fence before their own monitor. */
    internal fun withSourceFence(assignment: () -> Boolean): Boolean = sourceFence(assignment)
}

/** Carries the old epoch through buffers; a new epoch cannot bless an old Flow emission.
 * Wrapper identity also distinguishes consecutive same-value prompts in the same epoch. */
class DatabaseEpochValue<T> internal constructor(val value: T, val publication: DatabaseEpochPublication)
