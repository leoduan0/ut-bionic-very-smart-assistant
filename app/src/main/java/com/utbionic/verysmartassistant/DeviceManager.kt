package com.utbionic.verysmartassistant

import android.content.Context
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI

class DeviceManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val information: Information
) {
    private var heartbeatJob: Job? = null
    private val maxRetries = 3
    private val defaultControllerPort = 4211
    private val logTag = "DeviceManager"

    // --- TCP / Network Functions ---

    fun testConnection(callback: (Boolean) -> Unit) {
        scope.launch(Dispatchers.IO) {
            val address = information.controllerAddress.trim()
            if (address.isBlank()) {
                withContext(Dispatchers.Main) { callback(false) }
                return@launch
            }

            // Just a single attempt for quick setup testing
            val success = sendTcpHeartbeat(address, timeout = 3000)
            withContext(Dispatchers.Main) { callback(success) }
        }
    }

    fun startTcpHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                var success = false
                for (attempt in 0 until maxRetries) {
                    if (sendTcpHeartbeat(information.controllerAddress)) {
                        success = true
                        break
                    } else {
                        if (attempt < maxRetries - 1) {
                            delay(5000)
                        }
                    }
                }

                if (!success) {
                    onConnectionLost()
                    return@launch
                }
                delay(60 * 60 * 1000L) // 1 hour
            }
        }
    }

    private fun sendTcpHeartbeat(rawAddress: String, timeout: Int = 3000): Boolean {
        val endpoint = parseControllerEndpoint(rawAddress, defaultControllerPort)
        if (endpoint == null) {
            Log.w(logTag, "Invalid controller address for heartbeat: '$rawAddress'")
            return false
        }

        if (sendHeartbeat(endpoint, "HEARTBEAT", timeout, expectAck = true)) {
            return true
        }

        // Backward compatibility: older firmware expects ARE_YOU_ALIVE_BRO and may not ACK.
        return sendHeartbeat(endpoint, "ARE_YOU_ALIVE_BRO", timeout, expectAck = false)
    }

    private fun sendHeartbeat(
        endpoint: ControllerEndpoint,
        command: String,
        timeout: Int,
        expectAck: Boolean,
    ): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(endpoint.host, endpoint.port), timeout)
                socket.soTimeout = timeout
                val out: OutputStream = socket.getOutputStream()
                out.write("$command\n".toByteArray())
                out.flush()

                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val response = try {
                    reader.readLine()?.trim().orEmpty()
                } catch (_: SocketTimeoutException) {
                    ""
                }
                Log.d(
                    logTag,
                    "Heartbeat '$command' response from ${endpoint.host}:${endpoint.port}: '$response'",
                )

                if (!expectAck) {
                    // Legacy firmware may not return any payload for heartbeat.
                    return response.isBlank() || isHeartbeatAck(response)
                }

                isHeartbeatAck(response)
            }
        } catch (e: Exception) {
            Log.w(logTag, "Heartbeat command '$command' failed", e)
            false
        }
    }

    private fun isHeartbeatAck(response: String): Boolean {
        if (response.isBlank()) return false
        val normalized = response.trim()
        return normalized.equals("ACK", ignoreCase = true) ||
            normalized.equals("ALIVE", ignoreCase = true) ||
            normalized.equals("YEAH_ALIVE_BRO", ignoreCase = true) ||
            normalized.contains("\"success\":true", ignoreCase = true)
    }

    private fun parseControllerEndpoint(
        rawAddress: String,
        defaultPort: Int,
    ): ControllerEndpoint? {
        val candidate = normalizeControllerAddress(rawAddress)
        if (candidate.isBlank()) return null

        return try {
            val withScheme = if (candidate.contains("://")) candidate else "tcp://$candidate"
            val uri = URI(withScheme)
            val host = uri.host?.trim().orEmpty()
            if (host.isBlank()) return null

            val port = if (uri.port in 1..65535) uri.port else defaultPort
            ControllerEndpoint(host, port)
        } catch (e: Exception) {
            Log.w(logTag, "Unable to parse controller address: '$candidate'", e)
            null
        }
    }

    private fun normalizeControllerAddress(rawAddress: String): String {
        val trimmed = rawAddress.trim()
        if (trimmed.isBlank()) return ""

        // Accept pasted values like "IP: 192.168.1.10" from serial output.
        val withoutLabel = trimmed.replace(
            Regex("^(ip|host|controller\\s*address)\\s*[:=]\\s*", RegexOption.IGNORE_CASE),
            "",
        )

        return withoutLabel
            .trim()
            .trimEnd('/', ',', ';')
    }

    private data class ControllerEndpoint(
        val host: String,
        val port: Int,
    )

    private suspend fun onConnectionLost() {
        withContext(Dispatchers.Main) {
            Toast.makeText(
                context,
                "Lost connection to controller. Check controller address and Wi-Fi network.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    /**
     * Send a door open command to the ESP32
     * @param target "apartment" or "suite"
     * @param callback returns (success, message)
     */
    fun sendDoorCommand(target: String, callback: (Boolean, String) -> Unit) {
        val normalizedTarget = target.trim().lowercase()
        if (normalizedTarget != "apartment" && normalizedTarget != "suite") {
            callback(false, "Invalid door target: $target")
            return
        }

        val controllerAddress = information.controllerAddress.trim()
        if (controllerAddress.isBlank()) {
            callback(false, "Controller address is not set")
            return
        }

        val command = when (normalizedTarget) {
            "apartment" -> "OPEN_APARTMENT"
            "suite" -> "OPEN_SUITE"
            else -> {
                callback(false, "Invalid door target: $target")
                return
            }
        }

        sendCommand(command, callback)
    }

    /**
     * Send a newline-delimited command to the ESP32
     * @param command command literal expected by firmware
     * @param callback returns (success, message)
     */
    private fun sendCommand(command: String, callback: (Boolean, String) -> Unit) {
        scope.launch(Dispatchers.IO) {
            var socket: Socket? = null
            try {
                val endpoint = parseControllerEndpoint(
                    information.controllerAddress,
                    defaultControllerPort,
                )
                if (endpoint == null) {
                    withContext(Dispatchers.Main) {
                        callback(false, "Invalid controller address. Use IP/host or IP:port.")
                    }
                    return@launch
                }

                val timeoutMs = 10000

                socket = Socket()
                socket.connect(InetSocketAddress(endpoint.host, endpoint.port), timeoutMs)
                socket.soTimeout = timeoutMs
                Log.d(logTag, "Sending command '$command' to ${endpoint.host}:${endpoint.port}")

                // ESP32 controller expects line-based ASCII commands.
                val out: OutputStream = socket.getOutputStream()
                out.write(command.toByteArray())
                out.write("\n".toByteArray())
                out.flush()

                // Firmware returns one line per command, usually JSON.
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val responseString = reader.readLine()?.trim().orEmpty()
                Log.d(logTag, "Command '$command' response: '$responseString'")

                if (responseString.isBlank()) {
                    withContext(Dispatchers.Main) {
                        callback(false, "No response from controller")
                    }
                    return@launch
                }

                val successFromJson = responseString.contains("\"success\":true", ignoreCase = true)
                val message = extractJsonField(responseString, "message").ifBlank {
                    responseString
                }
                val normalizedMessage = message.lowercase()
                val semanticFailure = normalizedMessage.contains("busy") ||
                    normalizedMessage.contains("unknown command") ||
                    normalizedMessage.contains("failed")
                val success = successFromJson && !semanticFailure

                val userMessage = if (message.isNotBlank()) {
                    message
                } else {
                    if (success) "Command sent successfully" else "Command failed"
                }

                withContext(Dispatchers.Main) {
                    callback(success, userMessage)
                }

            } catch (e: SocketTimeoutException) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    callback(false, "Command timeout - controller not responding")
                }
            } catch (e: ConnectException) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    callback(false, "Cannot connect to controller - check IP/network")
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    callback(false, "Error: ${e.message ?: "Unknown error"}")
                }
            } finally {
                try {
                    socket?.close()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    private fun extractJsonField(jsonLine: String, field: String): String {
        val regex = Regex("\"$field\"\\s*:\\s*\"([^\"]*)\"")
        return regex.find(jsonLine)?.groupValues?.get(1).orEmpty()
    }

    fun cleanup() {
        heartbeatJob?.cancel()
    }
}