package com.dayforge.domain.util

import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * Check if the device currently has internet connectivity.
 * Synchronous — safe to call from any thread.
 */
fun ConnectivityManager.isOnline(): Boolean {
    val network = activeNetwork ?: return false
    val capabilities = getNetworkCapabilities(network) ?: return false
    return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}
