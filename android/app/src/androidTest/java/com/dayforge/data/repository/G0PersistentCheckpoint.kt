package com.dayforge.data.repository

import android.database.Cursor
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import androidx.sqlite.db.SupportSQLiteDatabase
import com.dayforge.data.local.LocalSyncAccess
import java.io.*
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Trusted-owner-only optional proof memo. No database handle, token, SQL callback or decoded
 * business result is retained. A valid MAC alone is NEVER a cold database certificate. */
internal class G0PersistentCheckpoint(private val file: File, private val alias: String, private val dataset: String, private val bufferDigests: Boolean = true) {
    var hit = false; private set
    var persisted = false; private set
    var scannedRows = 0L; private set
    var failPublishForTest = false
    var beforePublishForTest: () -> Unit = {}
    private val atomic get() = AtomicFile(file)
    private fun key(): SecretKey? = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.getKey(alias,null) as? SecretKey
    private fun signingKey(): SecretKey = key() ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256,"AndroidKeyStore").run {
        init(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
            .setDigests(KeyProperties.DIGEST_SHA256).build());generateKey()
    }
    private fun tag(body:ByteArray,key:SecretKey):ByteArray = Mac.getInstance("HmacSHA256").run { init(key);doFinal(body) }
    private fun body(digest:ByteArray,scope:ByteArray)=ByteArrayOutputStream().apply {
        DataOutputStream(this).use { it.writeInt(0x47304350);it.writeInt(1);it.write(scope);it.write(digest) }
    }.toByteArray()
    fun context(access:LocalSyncAccess,metric:String):ByteArray = hash { out ->
        for(value in listOf("G0-accepted-metric-proof-2026-10-18-v1",dataset,metric,
            access.session.authentication.userId,access.session.authentication.generation,access.session.serverInstanceId,
            access.session.syncEpoch,access.deviceId,access.capabilityRevision?.toString())) text(out,value)
        out.writeInt(access.capabilities.size);access.capabilities.sorted().forEach { text(out,it) }
    }
    @Synchronized fun matches(digest:ByteArray,scope:ByteArray):Boolean {
        hit=false
        // Read only the fixed-size packet, never allocate based on an untrusted file length.
        val packet=try { atomic.openRead().use { input ->
            val bytes=ByteArray(104);DataInputStream(input).readFully(bytes)
            if(input.read()!=-1) return false
            bytes
        } } catch(_:IOException) { return false }
        val expected=body(digest,scope)
        if(!MessageDigest.isEqual(packet.copyOfRange(0,72),expected)) return false
        val valid=try { key()?.let { MessageDigest.isEqual(packet.copyOfRange(72,104),tag(expected,it)) } ?: false }
            catch(_:java.security.GeneralSecurityException) { false }
            catch(_:IOException) { false }
        hit=valid;return valid
    }
    /** Called ONLY with the current fully verified or continuously certified snapshot, AFTER
     * known SQLite completion. Failed sidecar publication cannot turn a saved write into failure. */
    @Synchronized fun publish(digest:ByteArray,scope:ByteArray):Boolean {
        persisted=false
        val payload=body(digest,scope)
        val authenticated=try { payload+tag(payload,signingKey()) }
            catch(_:java.security.GeneralSecurityException) { return false }
            catch(_:IOException) { return false }
        var stream:FileOutputStream?=null
        try {
            stream=atomic.startWrite();stream.write(authenticated)
            beforePublishForTest()
            if(failPublishForTest) throw IOException("G0_CHECKPOINT_PUBLISH_FAULT")
            stream.fd.sync();atomic.finishWrite(stream);persisted=true
        } catch(_:IOException) { stream?.let { atomic.failWrite(it) } }
        return persisted
    }
    suspend fun capture(sql:SupportSQLiteDatabase,expectedSchema:List<List<String?>>):ByteArray {
        check(sql.inTransaction())
        val actual=sql.query(SCHEMA_SQL).use { c -> buildList { while(c.moveToNext())
            add(listOf("main")+(0..3).map { if(c.isNull(it)) null else c.getString(it) }) } }
        check(actual==expectedSchema.filter { it[0]=="main" }) { "G0_CHECKPOINT_SCHEMA" }
        check(actual.filter { it[1]=="table" }.map { it[2] }.toSet()==columns.keys) { "G0_CHECKPOINT_TABLES" }
        val trace=G0PhaseTrace.current.get()
        suspend fun integrity() { sql.query(INTEGRITY_SQL).use {
            check(it.moveToFirst() && it.getString(0)=="ok" && !it.moveToNext()) { "G0_CHECKPOINT_INTEGRITY" }
        } }
        if(trace==null) integrity() else trace.stage("checkpoint_integrity") { integrity() }
        val digest=MessageDigest.getInstance("SHA-256")
        val out=digestStream(digest)
        val rowDigest=MessageDigest.getInstance("SHA-256")
        val rowOut=digestStream(rowDigest)
        text(out,"G0-full-native-content-v1")
        out.writeInt(actual.size)
        for(row in actual) { out.writeInt(row.size);row.forEach { text(out,it) } }
        sql.query(VERSION_SQL).use { check(it.moveToFirst());out.writeLong(it.getLong(0)) }
        scannedRows=0
        out.writeInt(columns.size)
        for((table,fields) in columns) {
            currentCoroutineContext().ensureActive();text(out,table);out.writeInt(fields.size);fields.forEach { text(out,it) }
            val hashes=mutableListOf<String>()
            sql.query(select(table)).use { c ->
                check(c.columnNames.toList()==fields) { "G0_CHECKPOINT_COLUMNS" }
                while(c.moveToNext()) {
                    currentCoroutineContext().ensureActive();scannedRows++
                    run {
                        val row=rowOut
                        row.writeInt(c.columnCount)
                        for(i in 0 until c.columnCount) {
                            val type=c.getType(i);row.writeInt(type)
                            when(type) {
                                Cursor.FIELD_TYPE_NULL -> Unit
                                Cursor.FIELD_TYPE_INTEGER -> row.writeLong(c.getLong(i))
                                Cursor.FIELD_TYPE_FLOAT -> row.writeLong(java.lang.Double.doubleToRawLongBits(c.getDouble(i)))
                                Cursor.FIELD_TYPE_STRING -> bytes(row,c.getBlob(i))
                                Cursor.FIELD_TYPE_BLOB -> bytes(row,c.getBlob(i))
                                else -> error("G0_CHECKPOINT_TYPE")
                            }
                        }
                    }
                    // Flush exact original framing before digest() resets the hash for the next row.
                    rowOut.flush();hashes+=hex(rowDigest.digest())
                }
            }
            out.writeInt(hashes.size);hashes.sorted().forEach { text(out,it) }
        }
        out.flush();return digest.digest()
    }
    // Scratch only: no row, proof, authority or database state survives this call.
    private fun digestStream(digest:MessageDigest):DataOutputStream {
        val stream=java.security.DigestOutputStream(sink,digest)
        return DataOutputStream(if(bufferDigests) BufferedOutputStream(stream,4096) else stream)
    }
    private fun hash(write:(DataOutputStream)->Unit):ByteArray {
        val digest=MessageDigest.getInstance("SHA-256")
        digestStream(digest).use(write)
        return digest.digest()
    }
    companion object {
        private val sink=object:OutputStream() { override fun write(b:Int) {} ; override fun write(b:ByteArray,off:Int,len:Int) {} }
        private fun hex(value:ByteArray):String {
            val alphabet="0123456789abcdef";val result=CharArray(value.size*2)
            value.forEachIndexed { i,b -> val n=b.toInt() and 255;result[i*2]=alphabet[n ushr 4];result[i*2+1]=alphabet[n and 15] }
            return String(result)
        }
        const val SCHEMA_SQL="SELECT type,name,tbl_name,sql FROM main.sqlite_master ORDER BY type,name"
        const val INTEGRITY_SQL="PRAGMA main.integrity_check"
        const val VERSION_SQL="PRAGMA main.user_version"
        val columns=G0DomainSqlCatalog.columns+mapOf("android_metadata" to listOf("locale"),
            "room_master_table" to listOf("id","identity_hash"),"sqlite_sequence" to listOf("name","seq"))
        fun select(table:String):String { require(table in columns);return "SELECT * FROM main.`$table`" }
        private fun bytes(out:DataOutputStream,value:ByteArray) { out.writeInt(value.size);out.write(value) }
        private fun text(out:DataOutputStream,value:String?) { if(value==null) out.writeInt(-1) else bytes(out,value.toByteArray(Charsets.UTF_8)) }
    }
}
