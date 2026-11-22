package com.dayforge.data.local

import android.content.Context
import java.util.Collections
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Pure expected metadata, never a source-database observation or permission to restore. */
internal data class ReplicaReferenceSchema(
    val objects: Map<String, ReplicaSqlSchema.SchemaObject>,
    val version: Int,
    val roomIdentity: String
)

/** An independently owned cache makes failure lifecycle tests possible without a global hook.
 * The loader remains a local argument. Only deeply owned metadata survives a successful call;
 * no Room handle, Context, runtime, source proof, authentication or caller Job is retained.
 * Until it returns a Reference, the loader owns cleanup of partially constructed resources.
 */
internal class ReplicaReferenceSchemaCache {
    interface Reference {
        /** Must return only after the reference read transaction's actual END succeeds. */
        suspend fun readCommitted(): ReplicaReferenceSchema
        fun close()
    }

    private val mutex = Mutex()
    private var published: ReplicaReferenceSchema? = null

    suspend fun get(open: suspend () -> Reference): ReplicaReferenceSchema = mutex.withLock {
        val caller = currentCoroutineContext()
        caller.ensureActive()
        published?.let { return@withLock it }
        val reference = open()
        var failure: Throwable? = null
        val candidate = try {
            coroutineScope { reference.readCommitted() }
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            try { reference.close() } catch (close: Throwable) {
                if (failure == null) throw close else failure.addSuppressed(close)
            }
        }
        // END and close both precede publication. Cancellation during construction, reading,
        // actual END or close cannot leave a reusable candidate behind.
        caller.ensureActive()
        val owned = LinkedHashMap<String, ReplicaSqlSchema.SchemaObject>(candidate.objects.size)
        for ((key, value) in candidate.objects) {
            caller.ensureActive()
            val definition = value.definition.map { clause ->
                caller.ensureActive()
                Collections.unmodifiableList(ArrayList(clause))
            }
            owned[key] = ReplicaSqlSchema.SchemaObject(value.type, value.name, value.owner,
                Collections.unmodifiableList(ArrayList(definition)))
        }
        val frozen = ReplicaReferenceSchema(Collections.unmodifiableMap(owned), candidate.version, candidate.roomIdentity)
        caller.ensureActive()
        published = frozen
        frozen
    }
}

/** The loaded app's generated Room schema, factory/callback and Android SQLite implementation
 * are fixed for this process. App/OS updates require a new process, which constructs a new
 * template. This is not a cache keyed by an arbitrary source file or its claimed Room hash.
 * Every actual database still undergoes fresh schema/version/identity/type/storage checks.
 */
internal object ReplicaBusinessReferenceSchema {
    private val reader = DatabaseOperationSource.GeneratedReferenceSchemaReader()

    suspend fun read(context: Context): ReplicaReferenceSchema = reader.read(context)
}
