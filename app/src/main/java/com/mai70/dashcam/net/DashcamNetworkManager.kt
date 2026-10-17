package com.mai70.dashcam.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Manages network binding for the 70mai Dashcam Wi-Fi connection.
 *
 * Because the 70mai Dashcam Wi-Fi access point provides local connectivity
 * without an internet connection, Android's default routing may send HTTP
 * traffic over cellular data (5G/LTE). This class explicitly requests a Wi-Fi
 * network lacking internet capability and binds the application process to it
 * using [ConnectivityManager.bindProcessToNetwork].
 */
class DashcamNetworkManager private constructor(context: Context) {

    private val applicationContext: Context = context.applicationContext
    private val connectivityManager: ConnectivityManager =
        applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val boundNetwork = AtomicReference<Network?>(null)
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    /**
     * Binds the process traffic to the Wi-Fi network associated with the dashcam.
     * Blocks until a suitable Wi-Fi network is acquired or until [timeoutMs] expires.
     *
     * @param timeoutMs Maximum time to wait for Wi-Fi network acquisition in milliseconds.
     * @return `true` if process successfully bound to the Wi-Fi network, `false` otherwise.
     */
    @Synchronized
    fun bindToDashcamNetwork(timeoutMs: Long = 10000): Boolean {
        // If already bound to a valid network, verify or return early
        val currentBound = connectivityManager.boundNetworkForProcess
        if (currentBound != null) {
            val caps = connectivityManager.getNetworkCapabilities(currentBound)
            if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                boundNetwork.set(currentBound)
                Log.d(TAG, "Process already bound to Wi-Fi network: $currentBound")
                return true
            }
        }

        // Unbind any previous callback before requesting a new network
        unbindNetworkInternal()

        val latch = CountDownLatch(1)
        val acquiredNetwork = AtomicReference<Network?>(null)

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.d(TAG, "Wi-Fi Network available for 70mai binding: $network")
                acquiredNetwork.set(network)
                if (connectivityManager.bindProcessToNetwork(network)) {
                    boundNetwork.set(network)
                    Log.i(TAG, "Successfully bound process to network: $network")
                } else {
                    Log.e(TAG, "Failed to bind process to network: $network")
                }
                latch.countDown()
            }

            override fun onLost(network: Network) {
                Log.w(TAG, "Wi-Fi Network lost: $network")
                if (boundNetwork.get() == network) {
                    connectivityManager.bindProcessToNetwork(null)
                    boundNetwork.set(null)
                }
            }

            override fun onUnavailable() {
                Log.w(TAG, "Requested Wi-Fi Network unavailable")
                latch.countDown()
            }
        }

        this.networkCallback = callback

        try {
            Log.d(TAG, "Requesting Wi-Fi network without internet capability...")
            connectivityManager.requestNetwork(request, callback, timeoutMs.toInt())
            val success = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            if (!success || boundNetwork.get() == null) {
                Log.e(TAG, "Timed out or failed to acquire Wi-Fi network for 70mai Dashcam.")
                return false
            }
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Exception while requesting Wi-Fi network binding", e)
            unbindNetworkInternal()
            return false
        }
    }

    /**
     * Unbinds the current process from the Wi-Fi network and unregisters network callbacks.
     */
    @Synchronized
    fun unbindNetwork() {
        unbindNetworkInternal()
    }

    private fun unbindNetworkInternal() {
        networkCallback?.let { callback ->
            try {
                connectivityManager.unregisterNetworkCallback(callback)
            } catch (e: Exception) {
                Log.w(TAG, "Error unregistering network callback", e)
            }
            networkCallback = null
        }
        try {
            connectivityManager.bindProcessToNetwork(null)
        } catch (e: Exception) {
            Log.w(TAG, "Error resetting bound process network", e)
        }
        boundNetwork.set(null)
        Log.d(TAG, "Unbound process from Wi-Fi network")
    }

    /**
     * Returns the currently bound [Network], or `null` if none is bound.
     */
    fun getBoundNetwork(): Network? = boundNetwork.get()

    /**
     * Checks if the process is currently bound to a Wi-Fi network.
     */
    fun isBound(): Boolean {
        val network = connectivityManager.boundNetworkForProcess ?: boundNetwork.get()
        if (network == null) return false
        val caps = connectivityManager.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    companion object {
        private const val TAG = "DashcamNetworkManager"

        @Volatile
        private var instance: DashcamNetworkManager? = null

        fun getInstance(context: Context): DashcamNetworkManager {
            return instance ?: synchronized(this) {
                instance ?: DashcamNetworkManager(context).also { instance = it }
            }
        }
    }
}
