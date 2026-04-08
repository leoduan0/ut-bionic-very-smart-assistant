package com.utbionic.verysmartassistant

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

class DeviceManager(
    private val context: Context, // NEW: We need context for NsdManager
    private val scope: CoroutineScope,
    private val information: Information,
) {
    val PORT = 4211
    val TIMEOUT = 30000
    private var heartbeatJob: Job? = null

    // --- NEW mDNS DISCOVERY LOGIC ---
    fun discoverControllerAddress(callback: (String?) -> Unit) {
        val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
        val serviceType = "_utbionic._tcp."

        scope.launch(Dispatchers.IO) {
            val deferredIp = CompletableDeferred<String?>()

            val discoveryListener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(regType: String) {}
                override fun onServiceFound(service: NsdServiceInfo) {
                    // We found a service, now we must resolve it to get the IP
                    if (service.serviceType.contains("_utbionic._tcp")) {
                        nsdManager.resolveService(service, object : NsdManager.ResolveListener {
                            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
                            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                                val hostIp = serviceInfo.host.hostAddress
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
                    nsdManager.stopServiceDiscovery(this)
                }
                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                    nsdManager.stopServiceDiscovery(this)
                }
            }

            // Start listening
            nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, discoveryListener)

            // Wait up to 15 seconds for a result
            val discoveredIp = try {
                withTimeout(15000) { deferredIp.await() }
            } catch (e: TimeoutCancellationException) {
                null
            }

            // Clean up the listener so it doesn't run forever
            try {
                nsdManager.stopServiceDiscovery(discoveryListener)
            } catch (e: Exception) { /* Ignore if already stopped */ }

            // Save and return
            if (discoveredIp != null) {
                information.updateControllerAddress(discoveredIp)
            }

            withContext(Dispatchers.Main) {
                callback(discoveredIp)
            }
        }
    }
    // --------------------------------

    private suspend fun sendRequest(command: String): Boolean {
        return withContext(Dispatchers.IO) {
            runCatching {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(information.controllerAddress, PORT), TIMEOUT)
                    socket.soTimeout = TIMEOUT

                    val out: OutputStream = socket.getOutputStream()
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
}