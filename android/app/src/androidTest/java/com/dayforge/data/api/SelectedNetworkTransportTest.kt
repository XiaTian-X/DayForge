package com.dayforge.data.api

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith
import android.net.Network
import io.mockk.every
import io.mockk.mockk
import java.net.InetAddress
import java.net.Socket
import javax.net.SocketFactory
import org.junit.Assert.assertSame
import org.junit.Test

@RunWith(AndroidJUnit4::class)
class SelectedNetworkTransportTest {
    @Test
    fun selected_Android_network_supplies_sockets_and_DNS_to_API_clients() {
        val expectedSocket = mockk<Socket>()
        val expectedAddress = InetAddress.getByName("192.0.2.10")
        val networkFactory = mockk<SocketFactory>()
        every { networkFactory.createSocket() } returns expectedSocket
        val network = mockk<Network>()
        every { network.socketFactory } returns networkFactory
        every { network.getAllByName("nas.local") } returns arrayOf(expectedAddress)
        val transport = SelectedNetworkTransport()

        transport.select(network)

        assertSame(expectedSocket, transport.socketFactory.createSocket())
        assertSame(expectedAddress, transport.dns.lookup("nas.local").single())
    }
}
