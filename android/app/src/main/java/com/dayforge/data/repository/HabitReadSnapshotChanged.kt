package com.dayforge.data.repository

/** Only a same-identity row changing during a read. Never an authentication/corruption error.
 * Write/recovery callers still reject this as IllegalStateException; UI observers may reopen
 * their source a bounded number of times to capture a new coherent input.
 */
internal class HabitReadSnapshotChanged(message: String) : IllegalStateException(message)
