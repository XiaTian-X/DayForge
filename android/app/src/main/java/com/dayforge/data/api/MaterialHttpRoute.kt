package com.dayforge.data.api

import android.net.Network
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import javax.net.SocketFactory

/** Transient verified-at-admission routing; never credentials or a durable queue value. */
internal class MaterialHttpRoute(val origin: HttpUrl, private val network: Network?) {
    init {
        require(origin.encodedPath == "/" && origin.query == null && origin.fragment == null &&
            origin.username.isEmpty() && origin.password.isEmpty())
    }

    fun matches(url: HttpUrl) = origin.scheme == url.scheme && origin.host == url.host && origin.port == url.port

    fun bind(builder: OkHttpClient.Builder): OkHttpClient.Builder = builder
        .socketFactory(network?.socketFactory ?: SocketFactory.getDefault())
        .dns(network?.let { captured -> object : Dns {
            override fun lookup(hostname: String) = captured.getAllByName(hostname).toList()
        } } ?: Dns.SYSTEM)

    fun authBaseUrl() = origin.newBuilder().encodedPath("/api/v1/").build()

    companion object { const val IDENTITY_PATH = "/api/v2/system/identity" }
}
