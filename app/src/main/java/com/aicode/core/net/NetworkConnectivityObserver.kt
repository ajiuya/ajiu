package com.aicode.core.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import com.aicode.core.util.FileLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NetworkConnectivityObserver internal constructor(
    private val context: Context,
    private val status: NetworkStatusStore
) {
    @Inject
    constructor(@ApplicationContext context: Context) : this(context, NetworkStatus.store)

    private val connectivityManager by lazy {
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }

    private val lock = Any()
    private var currentNetwork: Network? = null
    private var hasInternet: Boolean? = null
    private var validated: Boolean? = null
    private var blocked: Boolean? = null
    private var transports: Set<NetworkStatus.Transport> = emptySet()

    private val mutableIsOnline = MutableStateFlow(status.states.value.isOnline)
    val isOnline: StateFlow<Boolean> = mutableIsOnline.asStateFlow()

    private val mutableRecovered = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val recovered: SharedFlow<Unit> = mutableRecovered.asSharedFlow()

    init {
        synchronized(lock) {
            runCatching {
                currentNetwork = connectivityManager.activeNetwork
                val capabilities = currentNetwork?.let(connectivityManager::getNetworkCapabilities)
                if (capabilities != null) readCapabilities(capabilities)
                publish(networkChanged = currentNetwork != null, notifyRecovery = false)
            }.onFailure {
                FileLogger.w(TAG, "读取初始默认网络状态失败", it)
            }
        }
        runCatching {
            connectivityManager.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    synchronized(lock) {
                        if (network == currentNetwork) return
                        currentNetwork = network
                        clearDetails()
                        publish(networkChanged = true)
                    }
                }

                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                    synchronized(lock) {
                        if (network != currentNetwork) return
                        readCapabilities(capabilities)
                        publish()
                    }
                }

                override fun onBlockedStatusChanged(network: Network, isBlocked: Boolean) {
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
                    synchronized(lock) {
                        if (network != currentNetwork) return
                        blocked = isBlocked
                        publish()
                    }
                }

                override fun onLost(network: Network) {
                    synchronized(lock) {
                        if (network != currentNetwork) return
                        currentNetwork = null
                        clearDetails()
                        publish()
                    }
                }
            })
        }.onFailure {
            FileLogger.e(TAG, "注册默认网络回调失败，${status.states.value.diagnosticSummary()}", it)
        }
    }

    private fun clearDetails() {
        hasInternet = null
        validated = null
        blocked = null
        transports = emptySet()
    }

    private fun readCapabilities(capabilities: NetworkCapabilities) {
        hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        transports = buildSet {
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add(NetworkStatus.Transport.WIFI)
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add(NetworkStatus.Transport.CELLULAR)
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add(NetworkStatus.Transport.ETHERNET)
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) add(NetworkStatus.Transport.VPN)
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) add(NetworkStatus.Transport.BLUETOOTH)
            if (isEmpty()) add(NetworkStatus.Transport.OTHER)
        }
    }

    private fun publish(networkChanged: Boolean = false, notifyRecovery: Boolean = true) {
        val (previous, snapshot) = status.updateNetwork(
            online = currentNetwork != null && hasInternet == true && blocked != true,
            networkChanged = networkChanged,
            hasInternet = hasInternet,
            validated = validated,
            blocked = blocked,
            transports = transports
        )
        mutableIsOnline.value = snapshot.isOnline
        if (snapshot == previous) return
        val summary = snapshot.diagnosticSummary()
        if (notifyRecovery && snapshot.recoveryVersion > previous.recoveryVersion) {
            FileLogger.i(TAG, "默认网络恢复或切换，$summary")
            mutableRecovered.tryEmit(Unit)
        } else {
            FileLogger.d(TAG, "默认网络状态变化，$summary")
        }
    }

    private companion object {
        const val TAG = "NetworkConnectivity"
    }
}
