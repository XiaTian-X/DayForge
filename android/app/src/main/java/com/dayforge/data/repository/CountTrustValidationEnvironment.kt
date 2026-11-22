package com.dayforge.data.repository

import android.icu.util.TimeZone
import android.icu.util.VersionInfo
import android.os.Build
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.ObjectOutputStream
import java.security.MessageDigest
import java.time.ZoneId
import java.time.zone.ZoneRules
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

/** Read-only semantic inputs of a persistent business-validation result. No database, authority,
 * signing or publication capability is held here. The controlled application uses Android's
 * static platform provider: production must not register/refresh a dynamic provider in-process.
 * A new process captures the effective rules again, including independent TZ data updates.
 */
internal object CountTrustValidationEnvironment {
    const val KEY = "environment/semantic-v1"
    private const val FORMAT = "count-trust-environment/v1"
    private const val RULES_ENCODING = "java-zone-rules-object-stream/v1"

    /** Immutable canonical text; decoding/encoding never shares a caller-owned mutable map. */
    class Snapshot private constructor(private val canonical: String) {
        fun toJson(): JsonObject = Json.parseToJsonElement(canonical).jsonObject
        override fun equals(other: Any?): Boolean = other is Snapshot && canonical == other.canonical
        override fun hashCode(): Int = canonical.hashCode()

        companion object {
            fun decode(value: JsonElement): Snapshot {
                val body = value as? JsonObject ?: error("COUNT_TRUST_ENVIRONMENT_INVALID")
                require(body.keys == setOf("format", "rulesEncoding", "zoneCount", "zonesDigest", "tzdb", "icu",
                    "androidSdk", "buildFingerprint", "buildIncremental", "javaRuntimeVersion", "javaVmName", "javaVmVersion")) {
                    "COUNT_TRUST_ENVIRONMENT_INVALID"
                }
                fun text(key: String): String {
                    val item = body.getValue(key)
                    require(item is JsonPrimitive && item.isString && item.content.isNotEmpty()) {
                        "COUNT_TRUST_ENVIRONMENT_INVALID"
                    }
                    return item.content
                }
                fun optionalText(key: String): String? = if (body.getValue(key) == JsonNull) null else text(key)
                fun positiveInt(key: String): Int {
                    val item = body.getValue(key)
                    require(item is JsonPrimitive && !item.isString) { "COUNT_TRUST_ENVIRONMENT_INVALID" }
                    val number = requireNotNull(item.intOrNull) { "COUNT_TRUST_ENVIRONMENT_INVALID" }
                    require(number > 0 && item.content == number.toString()) { "COUNT_TRUST_ENVIRONMENT_INVALID" }
                    return number
                }
                require(text("format") == FORMAT && text("rulesEncoding") == RULES_ENCODING) {
                    "COUNT_TRUST_ENVIRONMENT_INVALID"
                }
                val digest = text("zonesDigest")
                require(digest.length == 64 && digest.all { it in "0123456789abcdef" }) { "COUNT_TRUST_ENVIRONMENT_INVALID" }
                return Snapshot(buildJsonObject {
                    put("format", FORMAT); put("rulesEncoding", RULES_ENCODING)
                    put("zoneCount", positiveInt("zoneCount")); put("zonesDigest", digest)
                    put("tzdb", text("tzdb")); put("icu", text("icu"))
                    put("androidSdk", positiveInt("androidSdk"))
                    put("buildFingerprint", text("buildFingerprint")); put("buildIncremental", text("buildIncremental"))
                    for (key in listOf("javaRuntimeVersion", "javaVmName", "javaVmVersion"))
                        put(key, optionalText(key)?.let(::JsonPrimitive) ?: JsonNull)
                }.toString())
            }
        }
    }

    // Waiting callers and each platform scan remain cancellable. A cancelled capture is never
    // installed; a real platform failure disables optional reuse without creating an unknown stamp.
    @Volatile private var captured: Result<Snapshot>? = null
    private val captureMutex = Mutex()

    suspend fun current(): Snapshot? {
        val caller = currentCoroutineContext()
        caller.ensureActive()
        captured?.let { return it.getOrNull() }
        return captureMutex.withLock {
            caller.ensureActive()
            captured?.let { return@withLock it.getOrNull() }
            val result = try { Result.success(readPlatform()) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { Result.failure<Snapshot>(error) }
            caller.ensureActive()
            captured = result
            result.getOrNull()
        }
    }

    // Observing a diagnostic must not start a second platform capture.
    val failure: String? get() = captured?.exceptionOrNull()?.let { it.javaClass.simpleName + ":" + it.message }

    /** Always reads the actual platform. This helper does not replace/reset the process current
     * value and accepts no injected rules, environment or test callback.
     */
    suspend fun readPlatform(): Snapshot {
        val caller = currentCoroutineContext()
        caller.ensureActive()
        val before = metadata()
        val ids = ZoneId.getAvailableZoneIds().sorted()
        require(ids.isNotEmpty()) { "COUNT_TRUST_ENVIRONMENT_UNAVAILABLE" }
        val inventory = ArrayList<RuleEntry>(ids.size)
        for (id in ids) {
            caller.ensureActive()
            inventory.add(RuleEntry(id, ZoneId.of(id).rules))
            caller.ensureActive()
        }
        val hex = digestRules(inventory)
        caller.ensureActive()
        check(before == metadata() && ids == ZoneId.getAvailableZoneIds().sorted()) {
            "COUNT_TRUST_ENVIRONMENT_CHANGED"
        }
        caller.ensureActive()
        return Snapshot.decode(buildJsonObject {
            put("format", FORMAT); put("rulesEncoding", RULES_ENCODING)
            put("zoneCount", ids.size); put("zonesDigest", hex)
            before.forEach { (key, value) -> put(key, value) }
        }).also { caller.ensureActive() }
    }

    /** Ordinary immutable rule values, not platform identity or certificate/signing authority. */
    data class RuleEntry(val id: String, val rules: ZoneRules)

    /** Pure full-content codec. Produces only a digest, never a Snapshot or process-current update.
     * readPlatform exclusively supplies its own actual ZoneId rules; no caller can inject a list
     * into the private process capture. The explicit values are also usable with public SDK APIs.
     */
    suspend fun digestRules(inventory: List<RuleEntry>): String {
        val caller = currentCoroutineContext()
        caller.ensureActive()
        require(inventory.isNotEmpty()) { "COUNT_TRUST_RULE_INVENTORY_EMPTY" }
        val framed = ByteArrayOutputStream()
        DataOutputStream(framed).use { output ->
            fun text(value: String) {
                val bytes = value.toByteArray(Charsets.UTF_8)
                output.writeInt(bytes.size); output.write(bytes)
            }
            text("DayForge/count-trust-zone-rules"); text(RULES_ENCODING); output.writeInt(inventory.size)
            var previous: String? = null
            for (position in inventory.indices) {
                caller.ensureActive()
                val entry = inventory[position]
                caller.ensureActive()
                require(entry.id.isNotEmpty() && (previous == null || requireNotNull(previous) < entry.id)) {
                    "COUNT_TRUST_RULE_INVENTORY_ORDER"
                }
                previous = entry.id
                // Independent streams exclude object identity/reference compression across zones.
                val encoded = ByteArrayOutputStream()
                ObjectOutputStream(encoded).use { it.writeObject(entry.rules) }
                caller.ensureActive()
                text(entry.id)
                output.write(MessageDigest.getInstance("SHA-256").digest(encoded.toByteArray()))
            }
        }
        caller.ensureActive()
        val digest = MessageDigest.getInstance("SHA-256").digest(framed.toByteArray())
        val alphabet = "0123456789abcdef"
        return CharArray(digest.size * 2).also { result -> digest.forEachIndexed { index, byte ->
            val value = byte.toInt() and 255
            result[index * 2] = alphabet[value ushr 4]; result[index * 2 + 1] = alphabet[value and 15]
        } }.concatToString().also { caller.ensureActive() }
    }

    private fun metadata(): JsonObject = buildJsonObject {
        put("tzdb", TimeZone.getTZDataVersion()); put("icu", VersionInfo.ICU_VERSION.toString())
        put("androidSdk", Build.VERSION.SDK_INT)
        put("buildFingerprint", Build.FINGERPRINT); put("buildIncremental", Build.VERSION.INCREMENTAL)
        put("javaRuntimeVersion", System.getProperty("java.runtime.version")?.let(::JsonPrimitive) ?: JsonNull)
        put("javaVmName", System.getProperty("java.vm.name")?.let(::JsonPrimitive) ?: JsonNull)
        put("javaVmVersion", System.getProperty("java.vm.version")?.let(::JsonPrimitive) ?: JsonNull)
    }
}
