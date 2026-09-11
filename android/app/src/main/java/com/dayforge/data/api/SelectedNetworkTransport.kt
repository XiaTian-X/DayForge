package com.dayforge.data.api

import android.net.Network
import java.net.InetAddress
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.SocketFactory
import okhttp3.Dns

/**
 * Routes API sockets and DNS through the Network selected by [EndpointResolver].
 *
 * A LAN-only Wi-Fi network may not be Android's default network when cellular
 * data is also enabled. Probing with the Wi-Fi socket factory is insufficient:
 * the subsequent authenticated request must use the same route as well.
 */
@Singleton
class SelectedNetworkTransport @Inject constructor() {
    @Volatile
    private var selectedNetwork: Network? = null

    fun select(network: Network?) {
        selectedNetwork = network
    }

    internal fun currentNetwork(): Network? = selectedNetwork

    val dns: Dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> =
            selectedNetwork?.getAllByName(hostname)?.toList()
                ?: Dns.SYSTEM.lookup(hostname)
    }

    val socketFactory: SocketFactory = object : SocketFactory() {
        private fun delegate(): SocketFactory =
            selectedNetwork?.socketFactory ?: SocketFactory.getDefault()

        override fun createSocket(): Socket = delegate().createSocket()

        override fun createSocket(host: String, port: Int): Socket =
            delegate().createSocket(host, port)

        override fun createSocket(
            host: String,
            port: Int,
            localHost: InetAddress,
            localPort: Int
        ): Socket = delegate().createSocket(host, port, localHost, localPort)

        override fun createSocket(host: InetAddress, port: Int): Socket =
            delegate().createSocket(host, port)

        override fun createSocket(
            address: InetAddress,
            port: Int,
            localAddress: InetAddress,
            localPort: Int
        ): Socket = delegate().createSocket(address, port, localAddress, localPort)
    }
}
