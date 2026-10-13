package com.dayforge.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.api.MaterialHttpRoute
import com.dayforge.data.appearance.AccountIconContext
import com.dayforge.data.appearance.AccountIconHttp
import com.dayforge.data.appearance.AccountIconRemoteCatalog
import com.dayforge.data.appearance.MaterialSocketServer
import com.dayforge.data.appearance.ReplicaMaterialAccess
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real destination lease, production auth interceptors and persistent HTTP/1.1 sockets.
 * The close is injected after OkHttp selected its connection, not by sleeping for keepalive.
 */
@RunWith(AndroidJUnit4::class)
class NextReplicaMaterialKeepAliveTest : NextReplicaDestinationFixture() {
    private fun response(input: MaterialSocketServer.Input): MaterialSocketServer.Reply =
        if (input.path == MaterialHttpRoute.IDENTITY_PATH) reply(input).let {
            it.copy(bytes = it.bytes.toString(Charsets.UTF_8).replace(id(3), id(99)).toByteArray())
        } else materialResponse(input, emptyList())

    private fun transport(server: KeepAliveServer, context: AccountIconContext,
        boundary: Interceptor = Interceptor { it.proceed(it.request()) }): AccountIconHttp =
        AccountIconHttp(client.newBuilder().addNetworkInterceptor(boundary).build(), tokens, server.origin)
            .onReplicaRoute(MaterialHttpRoute(server.origin, null).forReplicaRecovery()) {
                requireNotNull(context.recovery).authorize(tokens)
            }

    @Test fun recoveryCatalogUsesNewConnectionWhenIdentitySocketClosesAfterConnectionSelection() = runBlocking<Unit> {
        initialize(); val destination = workflow().bindDestination(committed())
        KeepAliveServer { input, _ -> response(input) }.use { server ->
            ReplicaMaterialAccess.verified(db, tokens, sessions, ReplicaPrivateArchiveStore(directory), destination) { context ->
                val catalog = AccountIconRemoteCatalog(iconDb, icons, transfers)
                val attempt = catalog.next(context)
                val boundary = Interceptor { chain ->
                    if (chain.request().url.encodedPath == "/api/v2/appearance/catalog") {
                        assertNotNull(chain.connection()) // Past connection acquisition/health check.
                        server.closeIdentityConnection()
                    }
                    chain.proceed(chain.request())
                }
                val page = requireNotNull(transport(server, context, boundary).session(context) { it.catalog(attempt) })
                assertTrue(page.entries.isEmpty())
                assertEquals(0L, page.nextCursor)
                catalog.accept(attempt, page)
                assertEquals(0L, catalog.next(context).after)
            }
            assertEquals(listOf(MaterialHttpRoute.IDENTITY_PATH, "/api/v2/appearance/catalog"),
                server.requests.map { it.input.path })
            assertEquals(2, server.accepted.get())
            assertNotEquals(server.requests[0].connection, server.requests[1].connection)
            assertTrue(server.requests.all { it.input.method == "GET" })
        }
        assertFrozen(destination)
    }

    @Test fun recoveryCatalogDisconnectIsNotRetriedAndDoesNotAdvanceCatalog() = runBlocking<Unit> {
        initialize(); val destination = workflow().bindDestination(committed())
        KeepAliveServer { input, _ ->
            if (input.path == "/api/v2/appearance/catalog") null else response(input)
        }.use { server ->
            ReplicaMaterialAccess.verified(db, tokens, sessions, ReplicaPrivateArchiveStore(directory), destination) { context ->
                val catalog = AccountIconRemoteCatalog(iconDb, icons, transfers)
                val attempt = catalog.next(context)
                val error = rejected { transport(server, context).session(context) { it.catalog(attempt) } }
                assertTrue(error is IOException)
                assertEquals(attempt.checkpoint, catalog.next(context).checkpoint)
                assertTrue(destinationJobs().isEmpty())
            }
            assertEquals(2, server.accepted.get())
            assertEquals(1, server.requests.count { it.input.path == "/api/v2/appearance/catalog" })
            assertEquals(1, server.requests.count { it.input.path == MaterialHttpRoute.IDENTITY_PATH })
        }
        assertFrozen(destination)
    }

    @Test fun freshRecoveryConnectionStillRejectsRevocationAtFinalNetworkBoundary() = runBlocking<Unit> {
        initialize(); val destination = workflow().bindDestination(committed())
        KeepAliveServer { input, _ -> response(input) }.use { server ->
            val revocations = AtomicInteger()
            var captured: AccountIconContext? = null
            assertNotNull(rejected {
                ReplicaMaterialAccess.verified(db, tokens, sessions, ReplicaPrivateArchiveStore(directory), destination) { context ->
                    captured = context
                    val attempt = AccountIconRemoteCatalog(iconDb, icons, transfers).next(context)
                    val boundary = Interceptor { chain ->
                        if (chain.request().url.encodedPath == "/api/v2/appearance/catalog") {
                            assertNotNull(chain.connection())
                            server.awaitSecondConnection()
                            revocations.incrementAndGet()
                            runBlocking { tokens.clearAuthenticationTokens() }
                        }
                        chain.proceed(chain.request())
                    }
                    transport(server, context, boundary).session(context) { it.catalog(attempt) }
                }
            })
            assertEquals(1, revocations.get())
            assertEquals(2, server.accepted.get())
            assertEquals(listOf(MaterialHttpRoute.IDENTITY_PATH), server.requests.map { it.input.path })
            assertNull(tokens.accessToken.first())
            assertNotNull(rejected { requireNotNull(captured?.recovery).authorize(tokens) })
            assertTrue(destinationJobs().isEmpty())
            assertEquals(destination, tokens.activeReplicaStage())
            assertTrue(tokens.replicaSwitchPending())
            assertNull(tokens.localCoreWriteAccess())
        }
    }

    /** Private fixture: shared material tests deliberately retain their Connection: close policy. */
    private class KeepAliveServer(private val respond: (MaterialSocketServer.Input, Socket) -> MaterialSocketServer.Reply?) : Closeable {
        data class Seen(val connection: Int, val input: MaterialSocketServer.Input)
        private val listener = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        val origin = "http://127.0.0.1:${listener.localPort}/".toHttpUrl()
        val requests = CopyOnWriteArrayList<Seen>()
        val accepted = AtomicInteger()
        private val connected = CountDownLatch(2)
        private val identity = AtomicReference<Socket?>()
        private val sockets = ConcurrentHashMap.newKeySet<Socket>()
        private val closedByFault = ConcurrentHashMap.newKeySet<Socket>()
        private val workers = Executors.newFixedThreadPool(2)
        private val failure = AtomicReference<Throwable?>()
        @Volatile private var closing = false
        private val acceptor = Thread({
            try {
                while (!closing) {
                    val socket = listener.accept(); sockets.add(socket)
                    val number = accepted.incrementAndGet()
                    connected.countDown()
                    workers.submit { serve(socket, number) }
                }
            } catch (error: IOException) { if (!closing) failure.compareAndSet(null, error) }
        }, "replica-keepalive-fixture").apply { start() }

        fun closeIdentityConnection() {
            val socket = requireNotNull(identity.get())
            closedByFault.add(socket); socket.close()
        }

        fun awaitSecondConnection() { check(connected.await(5, TimeUnit.SECONDS)) }

        private fun serve(socket: Socket, number: Int) {
            try {
                socket.soTimeout = 45_000
                socket.use {
                    val input = socket.getInputStream()
                    fun line(): String? {
                        val bytes = ByteArrayOutputStream()
                        while (bytes.size() <= 16_384) {
                            val byte = input.read()
                            if (byte == -1) {
                                if (bytes.size() == 0) return null
                                throw EOFException("Incomplete fixture request")
                            }
                            if (byte == 10) return bytes.toString(Charsets.US_ASCII.name()).removeSuffix("\r")
                            bytes.write(byte)
                        }
                        error("Oversized fixture request")
                    }
                    while (!closing && !socket.isClosed) {
                        val start = (line() ?: return).split(' ')
                        check(start.size == 3 && start[2] == "HTTP/1.1")
                        val headers = linkedMapOf<String, String>()
                        while (true) {
                            val value = line() ?: throw EOFException("Incomplete fixture headers")
                            if (value.isEmpty()) break
                            val parts = value.split(':', limit = 2); check(parts.size == 2)
                            check(headers.put(parts[0].lowercase(Locale.ROOT), parts[1].trim()) == null)
                        }
                        check("transfer-encoding" !in headers)
                        val length = headers["content-length"]?.toInt() ?: 0
                        check(length in 0..2_097_152)
                        val body = ByteArray(length); var offset = 0
                        while (offset < length) {
                            val count = input.read(body, offset, length - offset)
                            if (count <= 0) throw EOFException("Incomplete fixture body")
                            offset += count
                        }
                        val request = MaterialSocketServer.Input(start[0], start[1], headers, body)
                        requests.add(Seen(number, request))
                        if (request.path == MaterialHttpRoute.IDENTITY_PATH) check(identity.compareAndSet(null, socket))
                        val reply = respond(request, socket) ?: return
                        check(!reply.chunked && reply.length == reply.bytes.size)
                        val output = socket.getOutputStream()
                        output.write(("HTTP/1.1 ${reply.status} Fixture\r\nContent-Type: ${reply.type}\r\n" +
                            "Content-Length: ${reply.length}\r\nConnection: keep-alive\r\n" +
                            reply.headers.entries.joinToString("") { "${it.key}: ${it.value}\r\n" } + "\r\n")
                            .toByteArray(Charsets.US_ASCII))
                        output.write(reply.bytes); output.flush()
                    }
                }
            } catch (error: Throwable) {
                if (error !is IOException || (!closing && socket !in closedByFault)) failure.compareAndSet(null, error)
            } finally { sockets.remove(socket); closedByFault.remove(socket) }
        }

        override fun close() {
            closing = true; listener.close()
            acceptor.join(5000); check(!acceptor.isAlive)
            sockets.forEach(Socket::close)
            workers.shutdownNow(); check(workers.awaitTermination(5, TimeUnit.SECONDS))
            check(sockets.isEmpty())
            failure.get()?.let { throw AssertionError("Keepalive fixture failed", it) }
        }
    }
}
