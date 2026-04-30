package com.utbionic.verysmartassistant

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket

class DeviceManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val information: Information,
) {
    companion object {
        private const val PORT = 4211
        private const val TIMEOUT = 3_000
        private const val DISCOVERY_OVERALL_TIMEOUT_MS = 15_000L
        private const val SERVICE_TYPE = "_vsa._tcp."
    }

    private var heartbeatJob: Job? = null

    private suspend fun sendRequest(command: String): Boolean {
        return withContext(Dispatchers.IO) {
            runCatching {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(information.controllerAddress, PORT), TIMEOUT)
                    socket.soTimeout = TIMEOUT

                    val out = socket.getOutputStream()
                    out.write(command.toByteArray())
                    out.write("\n".toByteArray())
                    out.flush()

                    val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                    val response = reader.readLine()
                    val json = JSONObject(response)

                    json["success"] == true
                }
            }.getOrDefault(false)
        }
    }

    fun sendHeartbeat(callback: (Boolean) -> Unit) {
        scope.launch {
            val success = sendRequest("HEARTBEAT")
            withContext(Dispatchers.Main) {
                callback(success)
            }
        }
    }

    fun startHeartbeat(interval: Long, callback: (Boolean) -> Unit = {}) {
        stopHeartbeat()
        heartbeatJob = scope.launch {
            while (isActive) {
                val success = sendRequest("HEARTBEAT")
                withContext(Dispatchers.Main) {
                    callback(success)
                }
                delay(interval)
            }
        }
    }

    fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    fun sendCommand(command: String, callback: (Boolean) -> Unit) {
        scope.launch {
            val success = sendRequest(command)
            withContext(Dispatchers.Main) {
                callback(success)
            }
        }
    }

    fun discoverControllerAddress(
        callback: (String?) -> Unit,
    ) {
        scope.launch {
            val discoveredIp = discoverControllerIpAddress()
            if (discoveredIp != null) {
                information.updateControllerAddress(discoveredIp)
            }

            withContext(Dispatchers.Main) {
                callback(discoveredIp)
            }
        }
    }

    private suspend fun discoverControllerIpAddress(): String? {
        val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
        val deferredIp = CompletableDeferred<String?>()

        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {}

            override fun onServiceFound(service: NsdServiceInfo) {
                if (service.serviceType.contains("_vsa._tcp") && !deferredIp.isCompleted) {
                    nsdManager.resolveService(service, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}

                        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                            val hostIp = serviceInfo.host?.hostAddress
                            if (hostIp != null && !deferredIp.isCompleted) {
                                deferredIp.complete(hostIp)
                            }
                        }
                    })
                }
            }

            override fun onServiceLost(service: NsdServiceInfo) {}

            override fun onDiscoveryStopped(serviceType: String) {}

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                runCatching { nsdManager.stopServiceDiscovery(this) }
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                runCatching { nsdManager.stopServiceDiscovery(this) }
            }
        }

        return withContext(Dispatchers.IO) {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
            val discoveredIp = withTimeoutOrNull(DISCOVERY_OVERALL_TIMEOUT_MS) {
                deferredIp.await()
            }
            runCatching { nsdManager.stopServiceDiscovery(discoveryListener) }
            discoveredIp
        }
    }
}
