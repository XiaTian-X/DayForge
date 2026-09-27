package com.dayforge.data.api

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest

/** Android observation boundary; it neither caches paths nor decides reachability. */
internal interface NetworkObservationSource {
    val activeNetwork: Network?
    fun getNetworkCapabilities(network: Network): NetworkCapabilities?
    fun registerNetworkCallback(request: NetworkRequest, callback: ConnectivityManager.NetworkCallback)
    fun registerDefaultNetworkCallback(callback: ConnectivityManager.NetworkCallback)
    fun unregisterNetworkCallback(callback: ConnectivityManager.NetworkCallback)
}

internal class AndroidNetworkObservationSource(private val connectivity: ConnectivityManager) : NetworkObservationSource {
    override val activeNetwork: Network? get() = connectivity.activeNetwork
    override fun getNetworkCapabilities(network: Network): NetworkCapabilities? = connectivity.getNetworkCapabilities(network)
    override fun registerNetworkCallback(request: NetworkRequest, callback: ConnectivityManager.NetworkCallback) =
        connectivity.registerNetworkCallback(request, callback)
    override fun registerDefaultNetworkCallback(callback: ConnectivityManager.NetworkCallback) =
        connectivity.registerDefaultNetworkCallback(callback)
    override fun unregisterNetworkCallback(callback: ConnectivityManager.NetworkCallback) =
        connectivity.unregisterNetworkCallback(callback)
}
