package com.dayforge.data.repository

import com.dayforge.data.history.CountTrustIndex
import com.dayforge.domain.model.isContractUuid
import java.util.Collections
import kotlinx.serialization.json.*

/** A logical ordered index made entirely of named values in the original authenticated
 * COUNT root. Child names are NOT physical hashes/Refs: existing GC reaches every value's
 * chunks through the main directory. Authority and native lifetime belong to the caller.
 */
internal object MetricTrustDirectory {
    const val METRIC_ONLY_KEY = "metric-display/metric-only-qualification"
    const val COVERAGE_KEY = "metric-display/coverage"
    const val COVERAGE = "metric-display/native-ranges-v1"
    const val METRICS_KEY = "metric-display/metrics"
    const val ROSTER_KEY = "metric-display/roster"
    private const val PREFIX = "metric-display/v1/"
    private const val LEAF_RECORDS = 128
    private const val VALUE_BYTES = 65_536
    private const val KEY_LENGTH = 48
    private val digits = "0123456789abcdef"

    fun manifestKey(uuid: String) = "$PREFIX$uuid/manifest"
    private fun nodeKey(uuid: String, prefix: String) = "$PREFIX$uuid/node/${prefix.ifEmpty { "root" }}"
    private fun digest(value: JsonElement) = CountTrustIndex.hash(value.toString().toByteArray(Charsets.UTF_8))
    internal fun orderedKey(date: Long, uuid: String): String {
        require(isContractUuid(uuid)) { "METRIC_DIRECTORY_ID" }
        return (date xor Long.MIN_VALUE).toULong().toString(16).padStart(16, '0') + uuid.replace("-", "")
    }
    private fun date(key: String): Long = java.lang.Long.parseUnsignedLong(key.take(16), 16) xor Long.MIN_VALUE
    private fun validKey(key: String) = key.length == KEY_LENGTH && key.all { it in digits }
    private fun validPrefix(value: String) = value.length <= KEY_LENGTH && value.all { it in digits }
    private fun fields(value: JsonObject, vararg names: String) {
        check(value.keys == names.toSet()) { "METRIC_DIRECTORY_FIELDS" }
    }
    private fun text(value: JsonObject, name: String): String = value.getValue(name).jsonPrimitive.let {
        check(it.isString) { "METRIC_DIRECTORY_TYPE" }; it.content
    }
    private fun number(value: JsonObject, name: String): Long = value.getValue(name).jsonPrimitive.let {
        check(!it.isString) { "METRIC_DIRECTORY_TYPE" }; it.long
    }
    private fun nullable(value: JsonObject, name: String): String? = value.getValue(name).let {
        if (it == JsonNull) null else text(value, name)
    }

    data class Entry(val key: String, val id: Long, val rowName: String, val rowHash: String) {
        init { check(validKey(key) && id > 0 && rowName.startsWith("row:metric_logs:") && CountTrustIndex.validHash(rowHash)) }
        fun json() = buildJsonObject { put("key", key); put("id", id); put("row", rowName); put("hash", rowHash) }
        companion object {
            fun from(row: CountTrustRows.Row): Entry {
                check(row.table.name == "metric_logs")
                return Entry(orderedKey(row.long("date"), row.text("uuid")), row.long("id"), row.name, digest(row.value))
            }
            fun decode(value: JsonObject): Entry {
                fields(value, "key", "id", "row", "hash")
                return Entry(text(value, "key"), number(value, "id"), text(value, "row"), text(value, "hash"))
            }
        }
    }
    data class Summary(val prefix: String, val count: Long, val min: String?, val max: String?) {
        init {
            check(validPrefix(prefix) && count >= 0 && (count == 0L) == (min == null && max == null)) { "METRIC_DIRECTORY_SUMMARY" }
            if (count > 0) check(min != null && max != null && validKey(min) && validKey(max) &&
                min.startsWith(prefix) && max.startsWith(prefix) && min <= max) { "METRIC_DIRECTORY_BOUNDS" }
        }
        fun json() = buildJsonObject { put("prefix", prefix); put("count", count)
            put("min", min?.let(::JsonPrimitive) ?: JsonNull); put("max", max?.let(::JsonPrimitive) ?: JsonNull) }
        companion object {
            fun decode(value: JsonObject): Summary {
                fields(value, "prefix", "count", "min", "max")
                return Summary(text(value, "prefix"), number(value, "count"), nullable(value, "min"), nullable(value, "max"))
            }
        }
    }
    private class Node(val uuid: String, val summary: Summary, val entries: List<Entry>?, val children: List<Summary>?) {
        init {
            check(isContractUuid(uuid) && (entries == null) != (children == null))
            if (entries != null) {
                check(entries.size <= LEAF_RECORDS && entries.map { it.key } == entries.map { it.key }.distinct().sorted())
                check(entries.all { it.key.startsWith(summary.prefix) } && summary == summarize(summary.prefix, entries)) { "METRIC_DIRECTORY_LEAF" }
            } else {
                val values = requireNotNull(children)
                check(summary.prefix.length < KEY_LENGTH && values.size in 1..16 &&
                    values.map { it.prefix } == values.map { it.prefix }.distinct().sorted())
                check(values.all { it.prefix.length == summary.prefix.length + 1 && it.prefix.startsWith(summary.prefix) })
                check(summary == combine(summary.prefix, values)) { "METRIC_DIRECTORY_BRANCH" }
            }
        }
        fun json(): JsonObject = buildJsonObject {
            put("format", 1); put("metric", uuid); put("summary", summary.json())
            put("kind", if (entries != null) "leaf" else "branch")
            put("items", JsonArray(entries?.map(Entry::json) ?: requireNotNull(children).map(Summary::json)))
        }.also { check(it.toString().toByteArray(Charsets.UTF_8).size <= VALUE_BYTES) { "METRIC_DIRECTORY_VALUE_BUDGET" } }
        companion object {
            fun decode(uuid: String, prefix: String, value: JsonElement): Node {
                val v = value.jsonObject; fields(v, "format", "metric", "summary", "kind", "items")
                check(number(v, "format") == 1L && text(v, "metric") == uuid) { "METRIC_DIRECTORY_ID" }
                val summary = Summary.decode(v.getValue("summary").jsonObject)
                check(summary.prefix == prefix) { "METRIC_DIRECTORY_PREFIX" }
                val items = v.getValue("items").jsonArray
                return when (text(v, "kind")) {
                    "leaf" -> Node(uuid, summary, items.map { Entry.decode(it.jsonObject) }, null)
                    "branch" -> Node(uuid, summary, null, items.map { Summary.decode(it.jsonObject) })
                    else -> error("METRIC_DIRECTORY_KIND")
                }.also { it.json() }
            }
        }
    }
    data class Manifest(val uuid: String, val metricId: Long, val definitionHash: String,
        val revision: Long, val root: Summary) {
        init { check(isContractUuid(uuid) && metricId > 0 && CountTrustIndex.validHash(definitionHash) && revision > 0 && root.prefix.isEmpty()) }
        fun json() = buildJsonObject { put("format", 1); put("metric", uuid); put("metric_id", metricId)
            put("definition", definitionHash); put("revision", revision); put("root", root.json()) }
        companion object {
            fun decode(uuid: String, value: JsonElement): Manifest {
                val v = value.jsonObject; fields(v, "format", "metric", "metric_id", "definition", "revision", "root")
                check(number(v, "format") == 1L && text(v, "metric") == uuid) { "METRIC_DIRECTORY_MANIFEST" }
                return Manifest(uuid, number(v, "metric_id"), text(v, "definition"), number(v, "revision"), Summary.decode(v.getValue("root").jsonObject))
            }
        }
    }
    private fun summarize(prefix: String, values: List<Entry>) = Summary(prefix, values.size.toLong(), values.firstOrNull()?.key, values.lastOrNull()?.key)
    private fun combine(prefix: String, children: List<Summary>): Summary = Summary(prefix,
        children.fold(0L) { n, c -> Math.addExact(n, c.count) }, children.firstNotNullOfOrNull { it.min }, children.lastOrNull { it.max != null }?.max)

    /** Cold-only optional representation. Unsupported original legacy IDs/duplicates do not
     * become a new product rejection. Once declared, a missing/broken value is never a miss. */
    fun issue(metrics: List<CountTrustRows.Row>, logs: List<CountTrustRows.Row>, initialRevision: Long = 1, poll: () -> Unit = {}): Map<String, JsonElement> {
        require(initialRevision > 0)
        val values = linkedMapOf<String, JsonElement>()
        val eligible = arrayListOf<String>()
        val byMetric = logs.groupBy { poll(); it.long("metricId") }
        val duplicateMetricIds = metrics.groupBy { it.text("uuid") }.filterValues { it.size != 1 }.keys
        for (metric in metrics) {
            poll()
            val uuid = metric.text("uuid")
            if (!isContractUuid(uuid) || uuid in duplicateMetricIds || metric.long("id") <= 0) continue
            val selected = byMetric[metric.long("id")].orEmpty()
            if (selected.any { !isContractUuid(it.text("uuid")) || it.long("id") <= 0 } ||
                selected.map { it.text("uuid") }.distinct().size != selected.size) continue
            val entries = selected.map { poll(); Entry.from(it) }.sortedBy { it.key }
            val pending = linkedMapOf<String, JsonElement>()
            val root = build(uuid, "", entries, pending)
            pending[manifestKey(uuid)] = Manifest(uuid, metric.long("id"), digest(metric.value), initialRevision, root).json()
            values.putAll(pending); eligible += uuid
        }
        values[ROSTER_KEY] = JsonArray(metrics.map { it.name }.sorted().map(::JsonPrimitive))
        values[COVERAGE_KEY] = JsonPrimitive(COVERAGE)
        values[METRICS_KEY] = JsonArray(eligible.sorted().map(::JsonPrimitive))
        return values
    }
    private fun build(uuid: String, prefix: String, entries: List<Entry>, values: MutableMap<String, JsonElement>): Summary {
        val summary = summarize(prefix, entries)
        val node = if (entries.size <= LEAF_RECORDS) Node(uuid, summary, entries, null) else {
            check(prefix.length < KEY_LENGTH) { "METRIC_DIRECTORY_DUPLICATE_KEY" }
            val children = entries.groupBy { it.key[prefix.length] }.toSortedMap().map { (digit, rows) -> build(uuid, prefix + digit, rows, values) }
            Node(uuid, summary, null, children)
        }
        values[nodeKey(uuid, prefix)] = node.json()
        return summary
    }

    class Reader(private val get: (String) -> JsonElement?) {
        fun declared(uuid: String): Boolean {
            val coverage = get(COVERAGE_KEY); val declaration = get(METRICS_KEY)
            if (coverage == null && declaration == null) {
                check(get(manifestKey(uuid)) == null && get(nodeKey(uuid, "")) == null) { "METRIC_DIRECTORY_ORPHAN" }
                return false
            }
            check(coverage == JsonPrimitive(COVERAGE) && declaration is JsonArray) { "METRIC_DIRECTORY_COVERAGE" }
            val ids = declaration.map { it.jsonPrimitive.also { p -> check(p.isString && isContractUuid(p.content)) }.content }
            check(ids == ids.distinct().sorted()) { "METRIC_DIRECTORY_MEMBERS" }
            val declared = uuid in ids
            if (!declared) check(get(manifestKey(uuid)) == null && get(nodeKey(uuid, "")) == null) { "METRIC_DIRECTORY_ORPHAN" }
            return declared
        }
        fun manifest(metric: CountTrustRows.Row): Manifest? {
            val uuid = metric.text("uuid")
            if (!declared(uuid)) return null
            return Manifest.decode(uuid, requireNotNull(get(manifestKey(uuid))) { "METRIC_DIRECTORY_MANIFEST_MISSING" }).also {
                check(it.metricId == metric.long("id") && it.definitionHash == digest(metric.value)) { "METRIC_DIRECTORY_DEFINITION_CHANGED" }
                node(uuid, it.root)
            }
        }
        private fun node(uuid: String, expected: Summary): Node = Node.decode(uuid, expected.prefix,
            requireNotNull(get(nodeKey(uuid, expected.prefix))) { "METRIC_DIRECTORY_CHILD_MISSING" }).also {
            check(it.summary == expected) { "METRIC_DIRECTORY_CHILD_CHANGED" }
        }
        fun point(manifest: Manifest, row: CountTrustRows.Row) {
            check(row.long("metricId") == manifest.metricId)
            val wanted = Entry.from(row)
            var current = node(manifest.uuid, manifest.root)
            while (current.children != null) {
                val next = current.children.singleOrNull { wanted.key.startsWith(it.prefix) }
                    ?: error("METRIC_DIRECTORY_MEMBER_MISSING")
                current = node(manifest.uuid, next)
            }
            check(current.entries!!.singleOrNull { it.key == wanted.key } == wanted) { "METRIC_DIRECTORY_CONTENT_CHANGED" }
        }
        fun latest(manifest: Manifest, row: CountTrustRows.Row?) {
            if (manifest.root.count == 0L) { check(row == null && manifest.root.max == null); return }
            check(row != null && row.long("date") == date(requireNotNull(manifest.root.max))) { "METRIC_DIRECTORY_LATEST_CHANGED" }
            point(manifest, row)
        }
        fun range(manifest: Manifest, start: Long, end: Long, actual: List<CountTrustRows.Row>) = interval(manifest, start, end, actual)
        fun interval(manifest: Manifest, start: Long?, end: Long?, actual: List<CountTrustRows.Row>) {
            require(start == null || end == null || start <= end)
            val expected = arrayListOf<Entry>()
            fun walk(summary: Summary) {
                if (summary.count == 0L || start != null && date(requireNotNull(summary.max)) < start || end != null && date(requireNotNull(summary.min)) >= end) return
                val next = node(manifest.uuid, summary)
                if (next.entries != null) expected += next.entries.filter { (start == null || date(it.key) >= start) && (end == null || date(it.key) < end) }
                else next.children!!.forEach(::walk)
            }
            if (start == null || end == null || start != end) walk(manifest.root)
            check(actual.all { it.long("metricId") == manifest.metricId && (start == null || it.long("date") >= start) && (end == null || it.long("date") < end) })
            val observed = actual.map(Entry::from).sortedBy { it.key }
            check(observed.map { it.key }.distinct().size == observed.size && observed == expected) { "METRIC_DIRECTORY_RANGE_CHANGED" }
        }
        fun verifyAll(uuid: String) {
            check(declared(uuid)); val manifest = Manifest.decode(uuid, requireNotNull(get(manifestKey(uuid))))
            fun walk(summary: Summary) { node(uuid, summary).children?.forEach(::walk) }
            walk(manifest.root)
        }
    }

    /** Pure edits only. Original owner already checked every actual native event and OLD/NEW
     * sequence. All same-identity intermediate events remain in that owner's full journal. */
    fun update(get: (String) -> JsonElement?, changes: List<Pair<CountTrustRows.Row?, CountTrustRows.Row?>>,
        metrics: List<CountTrustRows.Row>): Map<String, JsonElement?> {
        val reader = Reader(get)
        val metricChanges = changes.filter { (old, fresh) -> (old ?: requireNotNull(fresh)).table.name == "metric_logs" }
        if (metricChanges.isEmpty()) return emptyMap()
        val edits = linkedMapOf<String, JsonElement?>()
        for (metric in metrics) {
            val original = reader.manifest(metric) ?: continue
            val uuid = original.uuid
            val selected = metricChanges.filter { (old, fresh) -> listOfNotNull(old, fresh).any { it.long("metricId") == original.metricId } }
            if (selected.isEmpty()) continue
            check(reader.declared(uuid))
            fun read(prefix: String, expected: Summary): Node = Node.decode(uuid, prefix,
                requireNotNull(edits[nodeKey(uuid, prefix)] ?: get(nodeKey(uuid, prefix))) { "METRIC_DIRECTORY_CHILD_MISSING" }).also {
                check(it.summary == expected) { "METRIC_DIRECTORY_CHILD_CHANGED" }
            }
            fun change(summary: Summary, old: Entry?, fresh: Entry?): Summary {
                val current = read(summary.prefix, summary)
                val node = if (current.entries != null) {
                    val next = current.entries.toMutableList()
                    if (old != null) check(next.remove(old)) { "METRIC_DIRECTORY_OLD_MEMBER_CHANGED" }
                    if (fresh != null) { check(next.none { it.key == fresh.key }) { "METRIC_DIRECTORY_DUPLICATE_KEY" }; next += fresh }
                    val sorted = next.sortedBy { it.key }
                    if (sorted.size > LEAF_RECORDS) {
                        val built = linkedMapOf<String, JsonElement>(); val root = build(uuid, summary.prefix, sorted, built)
                        edits.putAll(built); return root
                    }
                    Node(uuid, summarize(summary.prefix, sorted), Collections.unmodifiableList(sorted), null)
                } else {
                    val children = current.children!!.associateBy { it.prefix }.toMutableMap()
                    val keys = listOfNotNull(old?.key, fresh?.key).map { summary.prefix + it[summary.prefix.length] }.distinct()
                    for (prefix in keys) {
                        val oldChild = children[prefix]
                        if (oldChild == null) {
                            check(old == null || !old.key.startsWith(prefix)) { "METRIC_DIRECTORY_OLD_MEMBER_MISSING" }
                            val added = listOfNotNull(fresh?.takeIf { it.key.startsWith(prefix) })
                            val created = Node(uuid, summarize(prefix, added), added, null)
                            edits[nodeKey(uuid, prefix)] = created.json(); children[prefix] = created.summary
                        } else children[prefix] = change(oldChild, old?.takeIf { it.key.startsWith(prefix) }, fresh?.takeIf { it.key.startsWith(prefix) })
                    }
                    val ordered = children.values.sortedBy { it.prefix }
                    Node(uuid, combine(summary.prefix, ordered), null, ordered)
                }
                edits[nodeKey(uuid, summary.prefix)] = node.json(); return node.summary
            }
            var root = original.root
            selected.forEach { (old, fresh) ->
                val before = old?.takeIf { it.long("metricId") == original.metricId }?.let(Entry::from)
                val after = fresh?.takeIf { it.long("metricId") == original.metricId }?.let(Entry::from)
                root = change(root, before, after)
            }
            edits[manifestKey(uuid)] = original.copy(revision = Math.addExact(original.revision, 1), root = root).json()
        }
        return Collections.unmodifiableMap(edits)
    }
}
