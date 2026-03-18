package com.utbionic.verysmartassistant

import android.content.Context
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

class DeviceManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val information: Information
) {
    private var heartbeatJob: Job? = null
    private val maxRetries = 3

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

    private fun sendTcpHeartbeat(ip: String, port: Int = 4211, timeout: Int = 3000): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(ip, port), timeout)
                socket.soTimeout = timeout
                val out: OutputStream = socket.getOutputStream()
                out.write("HEARTBEAT\n".toByteArray())
                out.flush()

                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val response = reader.readLine()?.trim().orEmpty()
                response.equals("ACK", ignoreCase = true)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

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
                val ip = information.controllerAddress.trim()
                val port = 4211
                val timeoutMs = 10000

                socket = Socket()
                socket.connect(InetSocketAddress(ip, port), timeoutMs)
                socket.soTimeout = timeoutMs

                // ESP32 controller expects line-based ASCII commands.
                val out: OutputStream = socket.getOutputStream()
                out.write(command.toByteArray())
                out.write("\n".toByteArray())
                out.flush()

                // Firmware returns one line per command, usually JSON.
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val responseString = reader.readLine()?.trim().orEmpty()

                if (responseString.isBlank()) {
                    withContext(Dispatchers.Main) {
                        callback(false, "No response from controller")
                    }
                    return@launch
                }

                val success = responseString.contains("\"success\":true", ignoreCase = true)
                val message = extractJsonField(responseString, "message").ifBlank {
                    if (success) "Command sent successfully" else "Command failed"
                }

                withContext(Dispatchers.Main) {
                    callback(success, message)
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