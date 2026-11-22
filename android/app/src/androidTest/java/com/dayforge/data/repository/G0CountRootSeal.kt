package com.dayforge.data.repository

import com.dayforge.data.history.HistoryBlockPlanner
import com.dayforge.data.history.HistoryBlockCodec

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.dayforge.data.history.HistoryBlockCodec.Ref
import com.dayforge.data.history.HistoryBlockPlanner.LeafRange
import com.dayforge.data.history.HistoryBlockPlanner.Partition
import com.dayforge.data.history.HistoryBlockPlanner.Scope
import com.dayforge.data.history.HistoryBlockPlanner.UnitKey
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.MessageDigest
import java.time.YearMonth
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey

/** Test-only authentication of one B2 Q snapshot. A Packet is data, never an account,
 * connection, P-currentness, publication or write permit. Only the closed B2 issuer
 * may prepare from its independently verified P/Q commit candidate; only an owner
 * observing actual known END may qualify that candidate. Matching never enrolls a key.
 * This is not a monotonic counter or selective/whole-database rollback defense.
 */
internal class G0CountRootSeal(private val alias:String) {
    init { require(alias.startsWith(KEY_PREFIX) && alias.length<=200) }

    class Unavailable(cause:Throwable?=null):IllegalStateException("G0_COUNT_ROOT_SEAL_UNAVAILABLE",cause)

    /** Every mutable byte is owned. Even values for SQL binding are fresh copies. */
    class Packet internal constructor(val binding:String,val root:Ref,val revision:Long,
        val generation:Long,authentication:ByteArray) {
        private val content=body(binding,root,revision,generation)
        private val mac=authentication.copyOf().also { require(it.size==MAC_BYTES) }
        val retainedBytes:Int get()=content.size+mac.size
        fun headerValues():List<Any?> = listOf(1L,FORMAT.toLong(),VALIDATOR,binding,
            root.height.toLong(),root.count,root.digest,revision,generation,mac.copyOf())
        fun bodyBytes():ByteArray=content.copyOf()
        fun authenticationBytes():ByteArray=mac.copyOf()
    }

    private fun existingKey():SecretKey?=KeyStore.getInstance("AndroidKeyStore").apply {load(null)}
        .getKey(alias,null) as? SecretKey
    private fun signingKey():SecretKey=existingKey() ?: KeyGenerator.getInstance(
        KeyProperties.KEY_ALGORITHM_HMAC_SHA256,"AndroidKeyStore").run {
        init(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
            .setDigests(KeyProperties.DIGEST_SHA256).build())
        generateKey()
    }
    private fun tag(body:ByteArray,key:SecretKey):ByteArray=Mac.getInstance("HmacSHA256").run {
        init(key);doFinal(body)
    }

    /** Pure packet preparation plus Keystore signing. No storage or authority is installed.
     * The issuer must atomically store P/Q/header/marker and validate all actual changes,
     * fresh final authority and reliable END before using this result as a qualified root.
     */
    fun prepare(binding:String,root:Ref,revision:Long,generation:Long):Packet {
        val bytes=body(binding,root,revision,generation) // reject shape BEFORE creating a key
        val authentication=try {tag(bytes,signingKey())}
            catch(error:GeneralSecurityException) {throw Unavailable(error)}
            catch(error:IOException) {throw Unavailable(error)}
        return Packet(binding,root,revision,generation,authentication)
    }

    /** No generateKey, SQL, repair, or read/write qualification occurs in this method. */
    fun match(packet:Packet,expectedBinding:String):Boolean {
        if(!validHash(expectedBinding) || packet.binding!=expectedBinding)return false
        return try {
            val key=existingKey() ?: return false
            MessageDigest.isEqual(packet.authenticationBytes(),tag(packet.bodyBytes(),key))
        } catch(_:GeneralSecurityException) {false} catch(_:IOException) {false}
    }

    companion object {
        const val FORMAT=1
        const val VALIDATOR="G0-count-Q-root/plain-B2-v1/ondemand-read-v1/2026-10-21"
        const val KEY_PREFIX="dayforge.test.g0.count.root."
        const val MAC_BYTES=32
        const val MAX_HEADER_BYTES=1024
        const val INDEX_DOMAIN="count-incremental-index-v1"
        private const val AUTH_DOMAIN="DayForge/G0/count-Q-root-seal"
        private const val HEX="0123456789abcdef"
        private val indexMonth=YearMonth.of(1970,1)

        internal fun validHash(value:String):Boolean=value.length==64 && value.all {it in HEX}
        internal fun fixedRoot(binding:String,height:Int,members:Long,digest:String):Ref {
            require(validHash(binding) && validHash(digest) && validMetadata(height.toLong(),members))
            return Ref(LeafRange(UnitKey(Partition(Scope(binding),INDEX_DOMAIN,"scope",null),indexMonth),null,null),
                height,members,digest)
        }
        internal fun validMetadata(height:Long,members:Long):Boolean {
            if(height !in 0L..HistoryBlockCodec.MAX_DEPTH.toLong() || members<=0)return false
            var maximum=HistoryBlockCodec.MAX_RECORDS.toLong()
            repeat(height.toInt()) {maximum=Math.multiplyExact(maximum,HistoryBlockCodec.MAX_CHILDREN.toLong())}
            return members<=maximum
        }
        /** For strict typed SQL capture, not authenticated enrollment. */
        internal fun fromStored(format:Long,validator:String,binding:String,height:Long,members:Long,
            digest:String,revision:Long,generation:Long,authentication:ByteArray):Packet? {
            if(format!=FORMAT.toLong() || validator!=VALIDATOR || !validHash(binding) || !validHash(digest) ||
                !validMetadata(height,members) || revision<=0 || generation<0 ||
                authentication.size!=MAC_BYTES)return null
            return Packet(binding,fixedRoot(binding,height.toInt(),members,digest),revision,generation,authentication)
        }
        private fun text(out:DataOutputStream,value:String) {
            out.writeInt(value.length);value.forEach {out.writeChar(it.code)}
        }
        private fun body(binding:String,root:Ref,revision:Long,generation:Long):ByteArray {
            require(revision>0 && generation>=0)
            require(root==fixedRoot(binding,root.height,root.count,root.digest)) {"G0_COUNT_ROOT_CONTEXT"}
            return ByteArrayOutputStream().also {buffer->DataOutputStream(buffer).use {out->
                text(out,AUTH_DOMAIN);out.writeInt(FORMAT);text(out,VALIDATOR)
                out.writeInt(HistoryBlockPlanner.KEY_ENCODING_VERSION)
                text(out,binding)
                // Full fixed B2 Ref metadata is authenticated, including fields which
                // do not need redundant storage in the ten-column small header.
                val unit=root.range.unit;val partition=unit.partition
                text(out,partition.scope.localBindingId);text(out,partition.domain);text(out,partition.objectId)
                out.writeByte(0) // exact null round
                out.writeInt(unit.month.year);out.writeInt(unit.month.monthValue)
                out.writeByte(0);out.writeByte(0) // exact open lower and upper bounds
                out.writeInt(root.height);out.writeLong(root.count);text(out,root.digest)
                out.writeLong(revision);out.writeLong(generation)
            }}.toByteArray().also {require(it.size+MAC_BYTES<=MAX_HEADER_BYTES)}
        }
    }
}
