package com.utbionic.verysmartassistant

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.Collections
import kotlinx.coroutines.cancelChildren

class DeviceManager(
    private val scope: CoroutineScope,
    private val information: Information,
) {
    companion object {
        private const val PORT = 4211
        private const val TIMEOUT = 30_000
        private const val DISCOVERY_SWEEP_TIMEOUT_MS = 1_500
        private const val DISCOVERY_OVERALL_TIMEOUT_MS = 15_000L
        private const val DISCOVERY_MAX_CONCURRENCY = 100
        private const val HOTSPOT_DISCOVERY_PREFIX = "172.20.10"
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

    fun sendHeartbeat(
        callback: (Boolean) -> Unit
    ) {
        scope.launch {
            val success = sendRequest("HEARTBEAT")
            withContext(Dispatchers.Main) {
                callback(success)
            }
        }
    }

    fun startHeartbeat(
        interval: Long,
        callback: (Boolean) -> Unit = {},
    ) {
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

    fun sendCommand(
        command: String,
        callback: (Boolean) -> Unit,
    ) {
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
        val prefixes = buildDiscoveryPrefixes()
        if (prefixes.isEmpty()) {
            return null
        }

        return withTimeoutOrNull(DISCOVERY_OVERALL_TIMEOUT_MS) {
            supervisorScope {
                val found = CompletableDeferred<String>()
                val semaphore = Semaphore(DISCOVERY_MAX_CONCURRENCY)

                prefixes.forEach { prefix ->
                    for (host in 1..255) {
                        val ip = "$prefix.$host"
                        launch(Dispatchers.IO) {
                            semaphore.withPermit {
                                if (found.isCompleted) {
                                    return@withPermit
                                }

                                if (probeHeartbeat(ip) && found.complete(ip)) {
                                    this@supervisorScope.coroutineContext.cancelChildren()
                                }
                            }
                        }
                    }
                }

                found.await()
            }
        }
    }

    private fun buildDiscoveryPrefixes(): List<String> {
        val prefixes = mutableSetOf<String>()

        // 1. Get the actual network IP prefixes
        runCatching {
            Collections.list(NetworkInterface.getNetworkInterfaces())
                .asSequence()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { Collections.list(it.inetAddresses).asSequence() }
                .filterIsInstance<Inet4Address>()
                .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress }
                .forEach { address ->
                    val hostAddress = address.hostAddress ?: return@forEach
                    val prefix = hostAddress.substringBeforeLast('.')
                    if (prefix.isNotBlank()) {
                        prefixes.add(prefix)
                    }
                }
        }

        // 2. Detect if Android Studio is trapping us in the emulator
        val isEmulator = prefixes.any { it == "10.0.2" }

        // 3. Deploy the universal safety net if trapped or empty
        if (isEmulator || prefixes.isEmpty()) {
            prefixes.add("10.0.0")    // Rogers / Comcast Xfinity
            prefixes.add("192.168.0") // TP-Link / D-Link
            prefixes.add("192.168.1") // Netgear / Linksys / Asus
            prefixes.add("192.168.2") // Bell Canada
        }

        // 4. Always ensure the iPhone hotspot is in the list
        prefixes.add(HOTSPOT_DISCOVERY_PREFIX)

        return prefixes.toList()
    }

    private fun probeHeartbeat(ip: String): Boolean {
        return runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(ip, PORT), DISCOVERY_SWEEP_TIMEOUT_MS)
                socket.soTimeout = DISCOVERY_SWEEP_TIMEOUT_MS

                val out = socket.getOutputStream()
                out.write("HEARTBEAT\n".toByteArray())
                out.flush()

                val response = BufferedReader(InputStreamReader(socket.getInputStream())).use { reader ->
                    reader.readLine()
                }

                response?.contains("HEARTBEAT_SUCCESS") == true
            }
        }.getOrDefault(false)
    }
}
