package com.dayforge.data.appearance

import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import okhttp3.HttpUrl.Companion.toHttpUrl

/** Real loopback HTTP/1.1 framing; no fake OkHttp chain or MockWebServer dependency. */
internal class MaterialSocketServer(
    private val respond: (Input, Socket) -> Reply?
) : Closeable {
    data class Input(val method: String, val target: String, val headers: Map<String, String>, val body: ByteArray) {
        val path get() = target.substringBefore('?')
    }
    data class Reply(val bytes: ByteArray, val status: Int = 200, val type: String = "application/json",
        val headers: Map<String, String> = emptyMap(), val length: Int = bytes.size,
        val allowClientClose: Boolean = false, val chunked: Boolean = false)
    private val listener = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
    val origin = "http://127.0.0.1:${listener.localPort}/".toHttpUrl()
    val requests = CopyOnWriteArrayList<Input>()
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private val workers = Executors.newFixedThreadPool(4)
    private val failure = AtomicReference<Throwable?>(null)
    @Volatile private var closing = false
    private val acceptor = Thread({
        try {
            while (!closing) {
                val socket = listener.accept(); sockets.add(socket)
                workers.submit { serve(socket) }
            }
        } catch (error: IOException) { if (!closing) failure.compareAndSet(null, error) }
    }, "material-http-fixture").apply { start() }

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
                    error("Oversized fixture request")
                }
                val start = line().split(' '); check(start.size == 3 && start[2] == "HTTP/1.1")
                val headers = linkedMapOf<String, String>()
                while (true) {
                    val line = line(); if (line.isEmpty()) break
                    val parts = line.split(':', limit = 2); check(parts.size == 2)
                    check(headers.put(parts[0].lowercase(java.util.Locale.ROOT), parts[1].trim()) == null)
                }
                check("transfer-encoding" !in headers)
                val length = headers["content-length"]?.toInt() ?: 0; check(length in 0..2_097_152)
                val body = ByteArray(length); var offset = 0
                while (offset < length) { val count = input.read(body, offset, length - offset); check(count > 0); offset += count }
                val request = Input(start[0], start[1], headers, body); requests.add(request)
                val reply = respond(request, socket) ?: return
                try {
                    val output = socket.getOutputStream()
                    output.write(("HTTP/1.1 ${reply.status} Fixture\r\nContent-Type: ${reply.type}\r\n" +
                        (if (reply.chunked) "Transfer-Encoding: chunked\r\n" else "Content-Length: ${reply.length}\r\n") + "Connection: close\r\n" +
                        reply.headers.entries.joinToString("") { "${it.key}: ${it.value}\r\n" } + "\r\n").toByteArray(Charsets.US_ASCII))
                    if (reply.chunked) {
                        output.write((reply.bytes.size.toString(16) + "\r\n").toByteArray())
                        output.write(reply.bytes); output.write("\r\n0\r\n\r\n".toByteArray())
                    } else output.write(reply.bytes)
                    output.flush()
                } catch (error: IOException) { if (!reply.allowClientClose) throw error }
            }
        } catch (error: Throwable) {
            if (!closing || (error !is IOException && error !is InterruptedException)) failure.compareAndSet(null, error)
        }
        finally { sockets.remove(socket) }
    }

    override fun close() {
        closing = true; listener.close(); sockets.forEach(Socket::close)
        acceptor.join(5000); check(!acceptor.isAlive)
        workers.shutdownNow(); check(workers.awaitTermination(5, TimeUnit.SECONDS))
        check(sockets.isEmpty()); failure.get()?.let { throw AssertionError("Real HTTP fixture failed", it) }
    }
}
