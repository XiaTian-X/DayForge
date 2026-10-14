package com.dayforge.data.repository

import com.dayforge.domain.model.isContractUuid
import java.util.Collections
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable

/** Identity only, never a queue index, entity identity, permission or acceptance proof. */
@Serializable
internal data class NextRecoveryKey(val kind: String, val requestId: String) {
    init { require(kind in setOf(NEXT_OPERATION, NEXT_TIMER) && isContractUuid(requestId)) }
    internal val sortKey get() = "$kind:$requestId"
}

@Serializable internal enum class NextRecoveryRequestState { PERMANENT_REJECTION, NEVER_SENT, UNKNOWN_RESULT, ACCEPTED, SUPERSEDED, ABANDONED }
@Serializable internal enum class NextRecoveryCategory { STRUCTURE, TIMER, FACT, ONE_TIME, RESTART, CONFIG_IMPORT }
@Serializable internal enum class NextRecoveryProtection { REAL_FACT, ACTIVE_TIMER, COUNT_DAY, ONE_TIME_STATE, ROUND_STATE, IMPORT_GROUP }
@Serializable internal enum class NextRecoveryDependencyReason {
    STRUCTURAL_PREDECESSOR, GOAL_CHILD_FRONTIER, TIMER_SEQUENCE, TIMER_POLICY, COUNT_POLICY,
    FIRST_COUNT_FACT, ENTITY_REFERENCE, REVERT_TARGET, ONE_TIME_CAS, RESTART_FRONTIER, IMPORT_PHASE
}

// Analysis-only resource budget. Pending work still uses the existing 10,000-request admission;
// logical aliases and referenced accepted ancestors must not consume that business capacity.
internal const val NEXT_RECOVERY_PROOF_NODE_LIMIT = 100_000

/** The transaction reader, not this pure planner, must establish these classifications. */
@Serializable
internal data class NextRecoveryNode(
    val key: NextRecoveryKey,
    val category: NextRecoveryCategory,
    val state: NextRecoveryRequestState,
    val entityType: String,
    val entityUuid: String,
    val evidenceHash: String,
    val protections: Set<NextRecoveryProtection> = emptySet()
) {
    init {
        require(entityType in setOf("plan_node", "metric", "activity_metric_link", "activity_event",
            "metric_observation", "timer_session", "challenge_restart"))
        require(isContractUuid(entityUuid) && evidenceHash.matches(Regex("[0-9a-f]{64}")))
        require((key.kind == NEXT_TIMER) == (category == NextRecoveryCategory.TIMER))
        require(category != NextRecoveryCategory.STRUCTURE || entityType in setOf("plan_node", "metric", "activity_metric_link"))
    }
}

@Serializable
internal data class NextRecoveryDependency(
    val predecessor: NextRecoveryKey,
    val successor: NextRecoveryKey,
    val reason: NextRecoveryDependencyReason,
    val evidenceHash: String
) {
    init { require(predecessor != successor && evidenceHash.matches(Regex("[0-9a-f]{64}"))) }
}

/** A compact all-predecessors gate. Does not replace the reader's individual ACK audits. */
@Serializable
internal data class NextRecoveryDependencyGroup(
    val predecessors: List<NextRecoveryKey>,
    val successors: List<NextRecoveryKey>,
    val reason: NextRecoveryDependencyReason,
    val evidenceHash: String
) {
    init {
        require(predecessors.isNotEmpty() && successors.isNotEmpty() &&
            predecessors.distinct().size == predecessors.size && successors.distinct().size == successors.size &&
            predecessors.toSet().intersect(successors.toSet()).isEmpty() && evidenceHash.matches(Regex("[0-9a-f]{64}")))
    }
}

internal enum class NextRecoveryBlockReason { ROOT_NOT_PERMANENT_REJECTION, UNSUPPORTED_CATEGORY,
    UNKNOWN_RESULT, ACCEPTED_DATA, PROTECTED_LOCAL_DATA, RETIRED_DEPENDENCY }
internal data class NextRecoveryBlocker(val key: NextRecoveryKey, val reason: NextRecoveryBlockReason)

internal class NextRecoveryDependencyPreview internal constructor(
    val root: NextRecoveryKey, affected: List<NextRecoveryNode>, blockers: List<NextRecoveryBlocker>,
    val graphHash: String, internal val archive: NextRecoveryGraphArchive
) {
    val affected: List<NextRecoveryNode> = Collections.unmodifiableList(affected.toList())
    val blockers: List<NextRecoveryBlocker> = Collections.unmodifiableList(blockers.toList())
    val canConfirm get() = blockers.isEmpty()
}

/**
 * Pure dependency analysis; it neither reads authority nor writes/retire requests.
 * The caller supplies one completely audited transaction snapshot. All classifications, aliases,
 * edges and gates are bound into the preview hash, including unchanged-count content changes.
 * Explicit confirmation must separately revalidate account/checkpoint and the actual SQL proofs.
 */
internal class NextRecoveryDependencyGraph(
    nodes: List<NextRecoveryNode>, dependencies: List<NextRecoveryDependency>,
    groups: List<NextRecoveryDependencyGroup> = emptyList(),
    aliases: Map<NextRecoveryKey, NextRecoveryKey> = emptyMap()
) {
    private val nodes = nodes.map { it.copy(protections = Collections.unmodifiableSet(it.protections.toSet())) }
        .sortedBy { it.key.sortKey }
    private val byKey = this.nodes.associateBy { it.key }
    private val aliases = aliases.toMap()
    private fun actual(key: NextRecoveryKey) = aliases[key] ?: key
    private val dependencies = dependencies.toList().sortedWith(compareBy(
        { it.predecessor.sortKey }, { it.successor.sortKey }, { it.reason.name }, { it.evidenceHash }))
    private val groups = groups.map { it.copy(predecessors = it.predecessors.sortedBy { k -> k.sortKey },
        successors = it.successors.sortedBy { k -> k.sortKey }) }
        .sortedBy { groupKey(it) }
    private val outgoing = mutableMapOf<NextRecoveryKey, MutableSet<NextRecoveryKey>>()
    private val groupsByPredecessor = mutableMapOf<NextRecoveryKey, MutableSet<Int>>()

    init {
        require(this.nodes.size in 1..NEXT_RECOVERY_PROOF_NODE_LIMIT && byKey.size == this.nodes.size) {
            "SYNC_RECOVERY_PROOF_LIMIT"
        }
        require(this.dependencies.distinct().size == this.dependencies.size && this.groups.distinct().size == this.groups.size)
        for ((original, replacement) in this.aliases) {
            val old = requireNotNull(byKey[original]); val next = requireNotNull(byKey[replacement])
            require(original != replacement && replacement !in this.aliases && old.state == NextRecoveryRequestState.SUPERSEDED &&
                next.state != NextRecoveryRequestState.SUPERSEDED && old.category == next.category &&
                old.category == NextRecoveryCategory.STRUCTURE && old.entityType == next.entityType && old.entityUuid == next.entityUuid)
        }
        require(this.aliases.values.distinct().size == this.aliases.size)
        for (edge in this.dependencies) {
            require(edge.predecessor in byKey && edge.successor in byKey)
            val from = actual(edge.predecessor); val to = actual(edge.successor)
            require(from != to)
            outgoing.getOrPut(from) { linkedSetOf() }.add(to)
        }
        for ((index, group) in this.groups.withIndex()) {
            require((group.predecessors + group.successors).all { it in byKey })
            val from = group.predecessors.map(::actual).toSet(); val to = group.successors.map(::actual).toSet()
            require(from.intersect(to).isEmpty())
            from.forEach { groupsByPredecessor.getOrPut(it) { linkedSetOf() }.add(index) }
        }
    }

    /** Iterative DAG audit using virtual gates, never the predecessor×successor product. */
    private suspend fun requireAcyclic() {
        val incoming = byKey.keys.associateWith { 0 }.toMutableMap()
        val gates = IntArray(groups.size)
        for (targets in outgoing.values) for (target in targets) incoming[target] = incoming.getValue(target) + 1
        for ((index, group) in groups.withIndex()) {
            gates[index] = group.predecessors.map(::actual).distinct().size
            for (target in group.successors.map(::actual).distinct()) incoming[target] = incoming.getValue(target) + 1
        }
        val ready = ArrayDeque(incoming.filterValues { it == 0 }.keys)
        var visited = 0
        fun release(key: NextRecoveryKey) {
            val remaining = incoming.getValue(key) - 1
            check(remaining >= 0); incoming[key] = remaining
            if (remaining == 0) ready.addLast(key)
        }
        while (ready.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val key = ready.removeFirst(); visited++
            outgoing[key].orEmpty().forEach(::release)
            for (index in groupsByPredecessor[key].orEmpty()) {
                gates[index]--
                if (gates[index] == 0) groups[index].successors.map(::actual).distinct().forEach(::release)
            }
        }
        require(visited == byKey.size) { "SYNC_RECOVERY_DEPENDENCY_CYCLE" }
    }

    suspend fun preview(root: NextRecoveryKey): NextRecoveryDependencyPreview {
        require(root in byKey && root !in aliases)
        requireAcyclic()
        val affected = linkedSetOf<NextRecoveryKey>()
        val ready = ArrayDeque<NextRecoveryKey>(); ready.addLast(root)
        val expandedGroups = mutableSetOf<Int>()
        while (ready.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val key = ready.removeFirst()
            if (!affected.add(key)) continue
            outgoing[key].orEmpty().forEach { ready.addLast(it) }
            for (index in groupsByPredecessor[key].orEmpty()) if (expandedGroups.add(index)) {
                groups[index].successors.map(::actual).distinct().forEach { ready.addLast(it) }
            }
        }
        val members = nodes.filter { it.key in affected }
        val blockers = buildList {
            if (byKey.getValue(root).state != NextRecoveryRequestState.PERMANENT_REJECTION)
                add(NextRecoveryBlocker(root, NextRecoveryBlockReason.ROOT_NOT_PERMANENT_REJECTION))
            for (node in members) {
                if (node.category != NextRecoveryCategory.STRUCTURE)
                    add(NextRecoveryBlocker(node.key, NextRecoveryBlockReason.UNSUPPORTED_CATEGORY))
                if (node.protections.isNotEmpty()) add(NextRecoveryBlocker(node.key, NextRecoveryBlockReason.PROTECTED_LOCAL_DATA))
                when (node.state) {
                    NextRecoveryRequestState.UNKNOWN_RESULT -> add(NextRecoveryBlocker(node.key, NextRecoveryBlockReason.UNKNOWN_RESULT))
                    NextRecoveryRequestState.ACCEPTED -> add(NextRecoveryBlocker(node.key, NextRecoveryBlockReason.ACCEPTED_DATA))
                    NextRecoveryRequestState.SUPERSEDED, NextRecoveryRequestState.ABANDONED ->
                        add(NextRecoveryBlocker(node.key, NextRecoveryBlockReason.RETIRED_DEPENDENCY))
                    else -> Unit
                }
            }
        }
        val caller = currentCoroutineContext()
        caller.ensureActive()
        val fingerprint = buildString {
            append("recovery_graph:1\n"); append(root.sortKey); append('\n')
            for (node in nodes) {
                caller.ensureActive()
                append(node.key.sortKey); append('|'); append(node.category); append('|'); append(node.state); append('|')
                append(node.entityType); append('|'); append(node.entityUuid); append('|'); append(node.evidenceHash); append('|')
                append(node.protections.map { it.name }.sorted().joinToString(",")); append('\n')
            }
            for ((from, to) in aliases.entries.sortedBy { it.key.sortKey }) {
                caller.ensureActive()
                append("alias|"); append(from.sortKey); append('|'); append(to.sortKey); append('\n')
            }
            for (edge in dependencies) {
                caller.ensureActive()
                append("edge|"); append(edge.predecessor.sortKey); append('|'); append(edge.successor.sortKey)
                append('|'); append(edge.reason); append('|'); append(edge.evidenceHash); append('\n')
            }
            for (group in groups) { caller.ensureActive(); append("gate|"); append(groupKey(group)); append('\n') }
        }
        return NextRecoveryDependencyPreview(root, members, blockers, nextRequestHash(fingerprint.toByteArray(Charsets.UTF_8)),
            NextRecoveryGraphArchive(nodes, dependencies, groups, aliases.entries.map { NextRecoveryAlias(it.key, it.value) }))
    }

    private fun groupKey(group: NextRecoveryDependencyGroup) = group.reason.name + "|" + group.evidenceHash + "|" +
        group.predecessors.joinToString(",") { it.sortKey } + "|" + group.successors.joinToString(",") { it.sortKey }
}

/** Complete frozen graph, not just affected IDs. Rebuilding it rechecks aliases, DAG and closure. */
@Serializable
internal data class NextRecoveryAlias(val logical: NextRecoveryKey, val actual: NextRecoveryKey)

@Serializable
internal data class NextRecoveryGraphArchive(val nodes: List<NextRecoveryNode>, val dependencies: List<NextRecoveryDependency>,
    val groups: List<NextRecoveryDependencyGroup>, val aliases: List<NextRecoveryAlias>) {
    suspend fun preview(root: NextRecoveryKey): NextRecoveryDependencyPreview {
        require(aliases.map { it.logical }.distinct().size == aliases.size)
        return NextRecoveryDependencyGraph(nodes, dependencies, groups, aliases.associate { it.logical to it.actual }).preview(root)
    }
}
