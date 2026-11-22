package com.dayforge.data.repository

import com.dayforge.domain.model.isContractUuid
import java.util.Collections

/** Pure pending-directory transitions. Supplied states MUST already have exact catalog/native
 * proofs under the same authenticated root. This type performs no SQL, signing or authorization.
 * The whole list is established empty, then maintained inductively by only these transitions;
 * the durable root MAC binds head/tail/count together with the full catalog root.
 */
internal object CountCommandPendingRules {
    data class Frontier(val head: String?, val tail: String?, val count: Long) {
        init {
            require(count >= 0)
            require((count == 0L) == (head == null && tail == null))
            if (count > 0) require(head != null && tail != null && isContractUuid(head) && isContractUuid(tail))
            require(count != 1L || head == tail)
        }
    }
    data class Change(val before: CountCommandRecords.Status?, val after: CountCommandRecords.Status)
    class Plan internal constructor(val before: Frontier, val after: Frontier, changes: List<Change>) {
        val changes: List<Change> = Collections.unmodifiableList(changes.toList())
        init { require(this.changes.map { it.after.commandId }.distinct().size == this.changes.size) }
    }

    fun append(frontier: Frontier, created: CountCommandRecords.Status, oldTail: CountCommandRecords.Status?): Plan {
        require(created.execution == CountCommandRecords.Execution.PREPARED && created.revision == 1L &&
            created.delivery == CountCommandRecords.Delivery.PENDING_RESOLUTION && created.previous == null && created.next == null)
        require(created.parent == null && created.child == null) // Linked-submission composite is a separate rule.
        require((oldTail == null) == (frontier.count == 0L))
        val changes = arrayListOf<Change>()
        if (oldTail != null) {
            require(oldTail.commandId == frontier.tail && oldTail.pending && oldTail.next == null)
            require(created.commandId != oldTail.commandId)
            changes += Change(oldTail, oldTail.copy(revision = Math.addExact(oldTail.revision, 1L), next = created.commandId))
        }
        changes += Change(null, created.copy(previous = frontier.tail))
        return Plan(frontier, Frontier(frontier.head ?: created.commandId, created.commandId,
            Math.addExact(frontier.count, 1L)), changes)
    }

    fun retain(frontier: Frontier, old: CountCommandRecords.Status, fresh: CountCommandRecords.Status): Plan {
        require(old.pending && fresh.pending && old.commandId == fresh.commandId && old.inputHash == fresh.inputHash)
        require(fresh.revision == Math.addExact(old.revision, 1L) && fresh.previous == old.previous && fresh.next == old.next)
        require(frontier.count > 0)
        require((old.previous == null) == (frontier.head == old.commandId))
        require((old.next == null) == (frontier.tail == old.commandId))
        return Plan(frontier, frontier, listOf(Change(old, fresh)))
    }

    /** Child T0 is one atomic transition: register child, attach it to the exact parent,
     * append to pending. If the parent is the old tail its two changes compose into ONE CAS
     * and ONE revision increment. No intermediate parent row or second T0 commit exists.
     */
    fun appendChild(frontier: Frontier, parent: CountCommandRecords.Status,
        attachedParent: CountCommandRecords.Status, child: CountCommandRecords.Status,
        oldTail: CountCommandRecords.Status): Plan {
        require(frontier.count > 0 && child.parent == parent.commandId && parent.child == null &&
            attachedParent.child == child.commandId && child.commandId != parent.commandId)
        require(child.execution == CountCommandRecords.Execution.PREPARED && child.revision == 1L &&
            child.delivery == CountCommandRecords.Delivery.PENDING_RESOLUTION && child.previous == null && child.next == null)
        retain(frontier, parent, attachedParent)
        require(oldTail.commandId == frontier.tail && oldTail.pending && oldTail.next == null && oldTail.commandId != child.commandId)
        val changes = if (oldTail.commandId == parent.commandId) {
            require(oldTail == parent)
            listOf(Change(parent, attachedParent.copy(next = child.commandId)),
                Change(null, child.copy(previous = oldTail.commandId)))
        } else listOf(Change(parent, attachedParent),
            Change(oldTail, oldTail.copy(revision = Math.addExact(oldTail.revision, 1), next = child.commandId)),
            Change(null, child.copy(previous = oldTail.commandId)))
        return Plan(frontier, Frontier(frontier.head, child.commandId, Math.addExact(frontier.count, 1)), changes)
    }

    /** Metric T1 updates child execution and parent consumption together, without moving
     * either list entry. The immutable parent Outcome is never rewritten by this operation.
     */
    fun retainGroup(frontier: Frontier, changes: List<Change>): Plan {
        require(changes.isNotEmpty())
        changes.forEach { retain(frontier, requireNotNull(it.before), it.after) }
        return Plan(frontier, frontier, changes)
    }

    fun remove(frontier: Frontier, old: CountCommandRecords.Status, terminal: CountCommandRecords.Status,
        previous: CountCommandRecords.Status?, next: CountCommandRecords.Status?): Plan {
        require(frontier.count > 0 && old.pending && !terminal.pending)
        require(old.commandId == terminal.commandId && old.inputHash == terminal.inputHash &&
            terminal.revision == Math.addExact(old.revision, 1L) && terminal.previous == null && terminal.next == null)
        require((old.previous == null) == (previous == null) && (old.next == null) == (next == null))
        require((previous == null) == (frontier.head == old.commandId))
        require((next == null) == (frontier.tail == old.commandId))
        val changes = arrayListOf(Change(old, terminal))
        if (previous != null) {
            require(previous.commandId == old.previous && previous.pending && previous.next == old.commandId)
            changes += Change(previous, previous.copy(revision = Math.addExact(previous.revision, 1L), next = old.next))
        }
        if (next != null) {
            require(next.commandId == old.next && next.pending && next.previous == old.commandId)
            changes += Change(next, next.copy(revision = Math.addExact(next.revision, 1L), previous = old.previous))
        }
        val after = if (frontier.count == 1L) {
            require(previous == null && next == null)
            Frontier(null, null, 0)
        } else Frontier(if (previous == null) old.next else frontier.head,
            if (next == null) old.previous else frontier.tail, frontier.count - 1)
        return Plan(frontier, after, changes)
    }

    /** Complete discovery on one protected root. No LIMIT/scan of delivered history. The
     * owner may expose bounded pages, but a continuation must retain this exact root+ordinal
     * and cannot claim completeness until count/tail/null and all visited proofs match.
     */
    fun verifyComplete(frontier: Frontier, lookup: (String) -> CountCommandRecords.Status,
        poll: () -> Unit): List<CountCommandRecords.Status> {
        val seen = hashSetOf<String>(); val result = arrayListOf<CountCommandRecords.Status>()
        var previous: String? = null; var id = frontier.head
        while (id != null) {
            poll(); require(seen.add(id) && result.size.toLong() < frontier.count) { "COMMAND_PENDING_CYCLE_OR_COUNT" }
            val status = lookup(id)
            require(status.commandId == id && status.pending && status.previous == previous) { "COMMAND_PENDING_LINK_CHANGED" }
            result += status; previous = id; id = status.next
        }
        require(result.size.toLong() == frontier.count && previous == frontier.tail) { "COMMAND_PENDING_INCOMPLETE" }
        return Collections.unmodifiableList(result)
    }

    /** Ephemeral continuation: never deserialized from UI/SavedState. Owner must verify the
     * same authenticated root revision/MAC before every page, or restart discovery. It is not
     * an authorization or a durable absence proof. Page limits bound memory, not product history.
     */
    class Pager internal constructor(private val rootIdentity: String, private val frontier: Frontier) {
        private var ordinal = 0L
        private var previous: String? = null
        private var id = frontier.head
        private var complete = false
        /** Work on a private cursor copy; the transaction owner publishes this copy only
         * after its actual END and final fresh authorization. A later cancelled/failed page
         * must not advance an already issued continuation.
         */
        internal fun fork(): Pager = Pager(rootIdentity, frontier).also {
            it.ordinal = ordinal; it.previous = previous; it.id = id; it.complete = complete
        }
        fun page(currentRootIdentity: String, maximumRows: Int,
            lookup: (String) -> CountCommandRecords.Status, poll: () -> Unit): Page {
            require(currentRootIdentity == rootIdentity) { "COMMAND_DISCOVERY_ROOT_CHANGED" }
            require(!complete && maximumRows in 1..256)
            val rows = ArrayList<CountCommandRecords.Status>()
            var nextOrdinal = ordinal; var nextPrevious = previous; var nextId = id
            while (nextId != null && rows.size < maximumRows) {
                poll(); require(nextOrdinal < frontier.count) { "COMMAND_PENDING_CYCLE_OR_COUNT" }
                val current = requireNotNull(nextId)
                val row = lookup(current)
                require(row.commandId == current && row.pending && row.previous == nextPrevious) { "COMMAND_PENDING_LINK_CHANGED" }
                rows += row; nextOrdinal = Math.addExact(nextOrdinal, 1); nextPrevious = current; nextId = row.next
            }
            val finished = nextId == null
            if (finished) require(nextOrdinal == frontier.count && nextPrevious == frontier.tail) { "COMMAND_PENDING_INCOMPLETE" }
            else require(nextOrdinal < frontier.count) { "COMMAND_PENDING_CYCLE_OR_COUNT" }
            // Failed/cancelled page does not consume any entries. This is not the durable T2
            // delivery marker; successful enumeration alone never removes an entry either.
            poll(); ordinal = nextOrdinal; previous = nextPrevious; id = nextId; complete = finished
            return Page(Collections.unmodifiableList(rows), finished)
        }
    }
    data class Page(val rows: List<CountCommandRecords.Status>, val complete: Boolean)
}
