package com.dayforge.data.repository

import android.os.SystemClock
import com.dayforge.data.appearance.MaterialSocketServer
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlinx.serialization.json.*

/** Fixed GET-only replica peer. SO_REUSEADDR precedes the FIRST port0 bind as well as rebinds. */
internal class ReplicaCompletionProcessPeer(
    port: Int,
    private val respond: (MaterialSocketServer.Input) -> MaterialSocketServer.Reply
) : Closeable {
    private val listener = ServerSocket().apply {
        reuseAddress = true
        bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 16)
    }
    val origin = "http://127.0.0.1:${listener.localPort}/".toHttpUrl()
    val boundPort: Int = listener.localPort
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private val workers = Executors.newFixedThreadPool(4)
    private val failure = AtomicReference<Throwable?>(null)
    private val closeStarted = AtomicBoolean(false)
    private val records = CopyOnWriteArrayList<JsonObject>()
    private val recordLock = Any()
    @Volatile private var closing = false
    var closed = false
        private set
    private val acceptor = Thread({
        try {
            while (!closing) {
                val socket = listener.accept(); sockets.add(socket)
                workers.submit { serve(socket) }
            }
        } catch (error: IOException) { if (!closing) failure.compareAndSet(null, error) }
    }, "replica-final-http-peer").apply { start() }

    init { require(port in 0..65535); check(port == 0 || boundPort == port) }

    private fun serve(socket: Socket) {
        try {
            socket.soTimeout = 5000
            socket.use {
                val input = socket.getInputStream()
                fun line(): String {
                    val out = java.io.ByteArrayOutputStream()
                    while (out.size() <= 16_384) {
                        val byte = input.read(); check(byte >= 0)
                        if (byte == 10) return out.toString(Charsets.US_ASCII.name()).removeSuffix("\r")
                        out.write(byte)
                    }
                    error("Oversized fixed replica peer request")
                }
                val start = line().split(' ')
                check(start.size == 3 && start[0] == "GET" && start[2] == "HTTP/1.1")
                val headers = linkedMapOf<String, String>()
                while (true) {
                    val header = line(); if (header.isEmpty()) break
                    val parts = header.split(':', limit = 2); check(parts.size == 2)
                    check(headers.put(parts[0].lowercase(java.util.Locale.ROOT), parts[1].trim()) == null)
                }
                check("transfer-encoding" !in headers)
                check((headers["content-length"]?.toInt() ?: 0) == 0)
                val request = MaterialSocketServer.Input(start[0], start[1], headers, byteArrayOf())
                val index = synchronized(recordLock) {
                    check(records.size < 128)
                    records.add(buildJsonObject {
                        put("method", request.method); put("path", request.path); put("target", request.target)
                        put("received_ns", SystemClock.elapsedRealtimeNanos()); put("response_flushed", false)
                    })
                    records.lastIndex
                }
                val reply = respond(request)
                check(!reply.chunked && !reply.allowClientClose && reply.length == reply.bytes.size)
                val output = socket.getOutputStream()
                output.write(("HTTP/1.1 ${reply.status} Fixture\r\nContent-Type: ${reply.type}\r\n" +
                    "Content-Length: ${reply.length}\r\nConnection: close\r\n" +
                    reply.headers.entries.joinToString("") { "${it.key}: ${it.value}\r\n" } + "\r\n").toByteArray(Charsets.US_ASCII))
                output.write(reply.bytes); output.flush()
                records[index] = JsonObject(records[index] + buildJsonObject {
                    put("status", reply.status); put("response_flushed", true)
                    put("response_sha256", ReplicaCompletionProcessProtocol.sha256(reply.bytes))
                    put("flushed_ns", SystemClock.elapsedRealtimeNanos())
                })
            }
        } catch (error: Throwable) {
            if (!closing || (error !is IOException && error !is InterruptedException)) failure.compareAndSet(null, error)
        } finally { sockets.remove(socket) }
    }

    fun evidence(): JsonArray {
        check(closed)
        return JsonArray(records.toList())
    }

    override fun close() {
        check(closeStarted.compareAndSet(false, true)) { "REPLICA_PEER_CLOSE_REPEATED" }
        closing = true
        listener.close(); sockets.forEach(Socket::close)
        acceptor.join(5000); check(!acceptor.isAlive)
        workers.shutdownNow(); check(workers.awaitTermination(5, TimeUnit.SECONDS))
        check(sockets.isEmpty())
        failure.get()?.let { throw AssertionError("Fixed real replica HTTP failed", it) }
        check(records.all { it["response_flushed"] == JsonPrimitive(true) })
        closed = true
    }
}
