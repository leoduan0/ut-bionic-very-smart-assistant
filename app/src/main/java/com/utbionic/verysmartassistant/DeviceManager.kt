package com.utbionic.verysmartassistant

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.net.ConnectException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.UUID

class DeviceManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val information: Information
) {
    private var heartbeatJob: Job? = null
    private var scanTimeoutJob: Job? = null
    private val maxRetries = 3
    private val SERVICE_UUID = UUID.fromString("0000abcd-0000-1000-8000-00805f9b34fb")
    private val CHAR_UUID = UUID.fromString("0000dcba-0000-1000-8000-00805f9b34fb")

    private fun hasPermission(permission: String): Boolean {
        return ActivityCompat.checkSelfPermission(
            context, permission
        ) == PackageManager.PERMISSION_GRANTED
    }

    // --- TCP / Network Functions ---

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
                val out: OutputStream = socket.getOutputStream()
                out.write("ARE_YOU_ALIVE_BRO\n".toByteArray())
                out.flush()
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private suspend fun onConnectionLost() {
        broadcastPairingMode()
        withContext(Dispatchers.Main) {
            Toast.makeText(
                context, "Lost connection. Please repair with controller.", Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun broadcastPairingMode() {
        scope.launch(Dispatchers.IO) {
            try {
                val socket = DatagramSocket()
                val buffer = "PAIRING_MODE_REQUEST".toByteArray()
                val packet = DatagramPacket(
                    buffer, buffer.size, InetAddress.getByName("255.255.255.255"), 4210
                )
                socket.broadcast = true
                socket.send(packet)
                socket.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * Send a door open command to the ESP32
     * @param target "apartment" or "suite"
     * @param durationMs how long to hold the button (default 5000ms)
     * @param callback returns (success, message)
     */
    fun sendDoorCommand(
        target: String, durationMs: Int = 5000, callback: (Boolean, String) -> Unit
    ) {
        sendCommand(CommandProtocol.createDoorCommand(target, durationMs), callback)
    }

    /**
     * Send a structured command to the ESP32
     * @param command the Command object to send
     * @param callback returns (success, message)
     */
    private fun sendCommand(command: Command, callback: (Boolean, String) -> Unit) {
        scope.launch(Dispatchers.IO) {
            var socket: Socket? = null
            try {
                val ip = information.controllerAddress
                val port = 4211
                val timeoutMs = 10000

                socket = Socket()
                socket.connect(InetSocketAddress(ip, port), timeoutMs)
                socket.soTimeout = timeoutMs

                // Send command as JSON
                val commandJson = CommandProtocol.toJson(command)
                val out: OutputStream = socket.getOutputStream()
                out.write(commandJson.toByteArray())
                out.write("\n".toByteArray())
                out.flush()

                // Read and parse response
                val input = socket.getInputStream()
                val buffer = ByteArray(1024)
                val bytesRead = input.read(buffer)

                if (bytesRead <= 0) {
                    withContext(Dispatchers.Main) {
                        callback(false, "No response from controller")
                    }
                    return@launch
                }

                val responseString = String(buffer, 0, bytesRead)
                val response = CommandProtocol.parseResponse(responseString)

                if (response != null) {
                    val message = response.message.ifBlank {
                        if (response.success) "${command.target} door opened successfully"
                        else "Failed to open ${command.target} door: ${response.error ?: "Unknown error"}"
                    }
                    withContext(Dispatchers.Main) { callback(response.success, message) }
                } else {
                    // Fallback if response parsing fails
                    withContext(Dispatchers.Main) {
                        callback(false, "Invalid response format: $responseString")
                    }
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

    // --- Bluetooth Functions ---
    fun setupBluetooth() {
        val adapter =
            (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter == null || !adapter.isEnabled) {
            Toast.makeText(context, "Please enable Bluetooth first", Toast.LENGTH_SHORT).show()
            return
        }

        if (!hasPermission(Manifest.permission.BLUETOOTH_SCAN)) {
            showToast("Bluetooth scan permission is required")
            return
        }

        val scanner = adapter.bluetoothLeScanner
        Toast.makeText(context, "Searching for the controller...", Toast.LENGTH_SHORT).show()

        val scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
                    showToast("Bluetooth connect permission is required")
                    return
                }

                if (result?.device?.name?.contains("ESP32", ignoreCase = true) == true) {
                    scanTimeoutJob?.cancel()
                    if (hasPermission(Manifest.permission.BLUETOOTH_SCAN)) {
                        scanner.stopScan(this)
                    }
                    connectToController(result.device)
                }
            }
        }
        try {
            scanner.startScan(scanCallback)
        } catch (_: SecurityException) {
            showToast("Bluetooth scan permission is required")
            return
        }
        scanTimeoutJob?.cancel()
        scanTimeoutJob = scope.launch {
            delay(15_000)
            if (hasPermission(Manifest.permission.BLUETOOTH_SCAN)) {
                try {
                    scanner.stopScan(scanCallback)
                } catch (_: SecurityException) {
                    showToast("Unable to stop scan due to missing permission")
                }
            }
            showToast("Controller not found. Please try setup again.")
        }
    }

    private fun connectToController(device: BluetoothDevice) {
        if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
            showToast("Bluetooth connect permission is required")
            return
        }

        try {
            device.connectGatt(context, false, object : BluetoothGattCallback() {
                override fun onConnectionStateChange(
                    gatt: BluetoothGatt, status: Int, newState: Int
                ) {
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        gatt.discoverServices()
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        gatt.close()
                    }
                }

                override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        showToast("Failed to discover controller services")
                        gatt.disconnect()
                        return
                    }

                    val service = gatt.getService(SERVICE_UUID)
                    val characteristic = service?.getCharacteristic(CHAR_UUID)
                    if (characteristic == null) {
                        showToast("Controller service not found.")
                        gatt.disconnect()
                        return
                    }

                    val configuredSsid = information.wifiSsid.trim()
                    val ssid = if (configuredSsid.isNotBlank()) {
                        configuredSsid
                    } else {
                        NetworkUtils.getSSID(context)
                    }
                    val password = information.wifiPassword

                    // Basic Validation
                    if (ssid.isBlank() || ssid.equals("<unknown ssid>", ignoreCase = true)) {
                        showToast("Wi-Fi SSID unavailable. Check Location/Permissions.")
                        gatt.disconnect()
                        return
                    }
                    if (password.isBlank()) {
                        showToast("Please set Wi-Fi password in settings.")
                        gatt.disconnect()
                        return
                    }

                    val payload =
                        "IP:${NetworkUtils.getLocalIpAddress(context)};SSID:$ssid;PASSWORD:$password"
                    val bytes = payload.toByteArray()
                    gatt.writeCharacteristic(
                        characteristic, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    )
                }

                override fun onCharacteristicWrite(
                    gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int
                ) {
                    showToast(if (status == BluetoothGatt.GATT_SUCCESS) "Credentials sent!" else "Failed to send credentials.")
                    gatt.disconnect()
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        startTcpHeartbeat()
                    }
                }
            })
        } catch (_: SecurityException) {
            showToast("Bluetooth connect permission is required")
        }
    }

    private fun showToast(msg: String) {
        scope.launch(Dispatchers.Main) {
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }

    fun cleanup() {
        heartbeatJob?.cancel()
        scanTimeoutJob?.cancel()
    }
}