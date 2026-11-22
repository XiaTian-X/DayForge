package com.dayforge.data.repository

import android.database.Cursor
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.dayforge.data.local.LocalCoreWriteAccess
import com.dayforge.data.local.LocalSyncAccess
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.MessageDigest
import java.time.LocalDate
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey

/** Optional, device-local authenticated Q/H packet. It grants no DB, account, or publication
 * capability. Only the cold owner may combine a match with original dynamic checks and END.
 * The packet lives in two fixed SQLite tables; there is no sidecar or credential cache.
 */
internal class G0CountCertificate(private val alias:String) {
    init { require(alias.startsWith("dayforge.test.g0.count.")) }
    class Packet internal constructor(val contextHash:String,val inputHash:String,val textHash:String,
        val imageHash:String,image:ByteArray,mac:ByteArray) {
        private val content=image.copyOf()
        private val authentication=mac.copyOf()
        val imageBytes:Int get()=content.size
        val chunkCount:Int get()=(content.size+CHUNK_BYTES-1)/CHUNK_BYTES
        val retainedBytes:Long get()=content.size.toLong()+authentication.size
        fun headerValues():List<Any?> = listOf(1L,FORMAT.toLong(),contextHash,inputHash,textHash,
            imageBytes.toLong(),chunkCount.toLong(),imageHash,authentication.copyOf())
        fun chunkValues(index:Int):List<Any?> {
            require(index in 0 until chunkCount)
            return listOf(index.toLong(),content.copyOfRange(index*CHUNK_BYTES,minOf(content.size,(index+1)*CHUNK_BYTES)))
        }
    }
    private fun key():SecretKey?=KeyStore.getInstance("AndroidKeyStore").apply {load(null)}.getKey(alias,null) as? SecretKey
    private fun signingKey():SecretKey=key() ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256,"AndroidKeyStore").run {
        init(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
            .setDigests(KeyProperties.DIGEST_SHA256).build());generateKey()
    }
    private fun tag(body:ByteArray,key:SecretKey)=Mac.getInstance("HmacSHA256").run {init(key);doFinal(body)}

    /** Caller supplies an immutable known-committed candidate. Authentication failures lose
     * only acceleration; cancellation, SQL and authority failure are handled by the owner.
     */
    fun prepare(contextHash:String,inputHash:String,textHash:String,image:ByteArray,availableBytes:Long):Packet? {
        require(listOf(contextHash,inputHash,textHash).all(::validHash))
        if(image.isEmpty() || image.size.toLong()>MAX_CHUNKS*CHUNK_BYTES || image.size.toLong()+32>availableBytes)return null
        // Own the exact bytes before hashing/signing, even if the caller mutates its array.
        val frozen=image.copyOf();val imageHash=hash(frozen)
        val count=(frozen.size+CHUNK_BYTES-1)/CHUNK_BYTES
        val body=body(contextHash,inputHash,textHash,imageHash,frozen.size,count)
        val mac=try {tag(body,signingKey())} catch(_:GeneralSecurityException) {return null} catch(_:IOException) {return null}
        return Packet(contextHash,inputHash,textHash,imageHash,frozen,mac)
    }

    /** Complete capture is mandatory. Missing/invalid optional Q/H means miss; never repair P.
     * No key is created during matching. All chunks, membership, order, sizes and root are bound.
     */
    fun match(input:G0FrozenDomainInputs,contextHash:String,availableBytes:Long):ByteArray? {
        require(input.countCertificateScope && validHash(contextHash))
        // The original watermark fold observes sqlite_sequence traversal order.
        // A multiset root cannot preserve that observation when names repeat.
        // Lose only acceleration; the unchanged full validator decides acceptance.
        val sequence=input.tables.getValue("sqlite_sequence")
        if(sequence.columns!=listOf("name","seq"))return null
        val names=hashSetOf<String>()
        for(row in sequence.rows) {
            if(row.cells.size!=2)return null
            val name=row.cells[0].takeIf {it.type==Cursor.FIELD_TYPE_STRING}?.text ?: return null
            val value=row.cells[1].takeIf {it.type==Cursor.FIELD_TYPE_INTEGER}?.number ?: return null
            if(value<0 || !names.add(name))return null
        }
        val headers=input.tables.getValue(G0CountCertificateSql.table)
        val chunks=input.tables.getValue(G0CountCertificateSql.chunkTable)
        if(headers.columns!=G0CountCertificateSql.headerColumns || chunks.columns!=G0CountCertificateSql.chunkColumns || headers.rows.size!=1)return null
        val cells=headers.rows.single().cells
        if(cells.size!=9)return null
        fun integer(index:Int):Long?=cells[index].takeIf {it.type==Cursor.FIELD_TYPE_INTEGER}?.number
        fun text(index:Int):String?=cells[index].takeIf {it.type==Cursor.FIELD_TYPE_STRING}?.text
        if(integer(0)!=1L || integer(1)!=FORMAT.toLong())return null
        val foundContext=text(2) ?: return null;val foundInput=text(3) ?: return null;val foundText=text(4) ?: return null
        val length=integer(5) ?: return null;val count=integer(6) ?: return null;val imageHash=text(7) ?: return null
        if(!listOf(foundContext,foundInput,foundText,imageHash).all(::validHash) || foundContext!=contextHash ||
            foundInput!=input.digestHex || foundText!=input.textDigestHex)return null
        if(length<=0 || length>availableBytes || length>Int.MAX_VALUE || count<=0 || count>MAX_CHUNKS ||
            count!=(length+CHUNK_BYTES-1)/CHUNK_BYTES || chunks.rows.size.toLong()!=count)return null
        val authentication=cells[8].takeIf {it.type==Cursor.FIELD_TYPE_BLOB}?.bytes() ?: return null
        if(authentication.size!=32)return null
        val body=body(foundContext,foundInput,foundText,imageHash,length.toInt(),count.toInt())
        val authentic=try {key()?.let {MessageDigest.isEqual(authentication,tag(body,it))} ?: false}
            catch(_:GeneralSecurityException) {false} catch(_:IOException) {false}
        if(!authentic)return null
        val ordered=arrayOfNulls<G0FrozenDomainInputs.Cell>(count.toInt())
        for(row in chunks.rows) {
            if(row.cells.size!=2)return null
            val index=row.cells[0].takeIf {it.type==Cursor.FIELD_TYPE_INTEGER}?.number ?: return null
            val content=row.cells[1].takeIf {it.type==Cursor.FIELD_TYPE_BLOB} ?: return null
            if(index !in 0 until count || ordered[index.toInt()]!=null)return null
            ordered[index.toInt()]=content
        }
        val image=ByteArray(length.toInt());var offset=0
        for(cell in ordered) {
            val bytes=cell?.bytes() ?: return null
            val expected=minOf(CHUNK_BYTES,image.size-offset)
            if(bytes.size!=expected || expected<=0)return null
            bytes.copyInto(image,offset);offset+=bytes.size
        }
        if(offset!=image.size || hash(image)!=imageHash)return null
        return image
    }
    companion object {
        const val FORMAT=1
        const val CHUNK_BYTES=64*1024
        private const val MAX_CHUNKS=512L
        private const val VALIDATOR="G0-count-plain-C01-C20-H01-H07-A01-A05-2026-10-21-v2"
        private val hexAlphabet="0123456789abcdef"
        fun hash(bytes:ByteArray):String=MessageDigest.getInstance("SHA-256").digest(bytes).let {digest->
            buildString(digest.size*2) {digest.forEach {byte->val n=byte.toInt() and 255;append(hexAlphabet[n ushr 4]);append(hexAlphabet[n and 15])}}
        }
        private fun validHash(value:String)=value.length==64 && value.all {it in hexAlphabet}
        private fun text(out:DataOutputStream,value:String?) {
            if(value==null)out.writeInt(-1) else {out.writeInt(value.length);value.forEach {out.writeChar(it.code)}}
        }
        private fun body(context:String,input:String,interpretation:String,result:String,length:Int,count:Int):ByteArray=
            ByteArrayOutputStream().also {bytes->DataOutputStream(bytes).use {out->
                text(out,"G0-count-authenticated-blocks");out.writeInt(FORMAT)
                text(out,context);text(out,input);text(out,interpretation);text(out,result)
                out.writeInt(length);out.writeInt(count);out.writeInt(CHUNK_BYTES)
            }}.toByteArray()
        fun context(core:LocalCoreWriteAccess,sync:LocalSyncAccess,today:LocalDate,defaultZone:String,
            maxFacts:Int,selected:List<Pair<Long,String>>):String {
            require(core.session==sync.session && core.capturedDeviceId==sync.deviceId && core.capabilities==sync.capabilities)
            return hash(ByteArrayOutputStream().also {bytes->DataOutputStream(bytes).use {out->
                text(out,VALIDATOR);text(out,"P-native-plus-UTF16-v1/Q-image-v1/fixed-two-table-schema-v1")
                for(value in listOf(core.session.authentication.userId,core.session.authentication.generation,
                    core.session.serverInstanceId,core.session.syncEpoch,core.capturedDeviceId,sync.deviceId,
                    sync.capabilityRevision?.toString(),today.toString(),defaultZone))text(out,value)
                out.writeInt(sync.capabilities.size);sync.capabilities.sorted().forEach {text(out,it)}
                out.writeInt(maxFacts);out.writeInt(selected.size)
                selected.sortedBy {it.first}.forEach {(id,uuid)->out.writeLong(id);text(out,uuid)}
            }}.toByteArray())
        }
    }
}
