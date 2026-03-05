package com.utbionic.verysmartassistant

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.ParcelUuid
import android.widget.Toast
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
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
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.util.UUID

class DeviceManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val information: Information
) {
    private var heartbeatJob: Job? = null
    private var scanTimeoutJob: Job? = null
    private var provisioningTimeoutJob: Job? = null
    private val maxRetries = 3
    private val provisioningTimeoutMs = 30_000L
    private val SERVICE_UUID = UUID.fromString("0000abcd-0000-1000-8000-00805f9b34fb")
    private val CHAR_UUID = UUID.fromString("0000dcba-0000-1000-8000-00805f9b34fb")
    private val CCC_DESCRIPTOR_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(
            context, permission
        ) == PackageManager.PERMISSION_GRANTED
    }

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
                out.write("ARE_YOU_ALIVE_BRO\n".toByteArray())
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

        sendCommand(CommandProtocol.createDoorCommand(normalizedTarget, durationMs), callback)
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
                val ip = information.controllerAddress.trim()
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

                // Read and parse newline-delimited response JSON
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val responseString = reader.readLine()?.trim().orEmpty()

                if (responseString.isBlank()) {
                    withContext(Dispatchers.Main) {
                        callback(false, "No response from controller")
                    }
                    return@launch
                }
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
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = manager.adapter
        if (adapter == null || !adapter.isEnabled) {
            Toast.makeText(context, "Please enable Bluetooth first", Toast.LENGTH_SHORT).show()
            return
        }

        if (!hasPermission(Manifest.permission.BLUETOOTH_SCAN) || !hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
            showToast("Bluetooth permissions are required")
            return
        }

        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            showToast("Bluetooth LE not supported on this device")
            return
        }

        Toast.makeText(context, "Scanning for VSA Controller wirelessly...", Toast.LENGTH_SHORT)
            .show()

        val scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                val device = result?.device ?: return

                scanTimeoutJob?.cancel()
                if (hasPermission(Manifest.permission.BLUETOOTH_SCAN)) {
                    try {
                        scanner.stopScan(this)
                    } catch (_: SecurityException) {
                    }
                }

                showToast("Found controller! Connecting...")
                connectToController(device)
            }

            override fun onScanFailed(errorCode: Int) {
                showToast("Bluetooth scan failed: $errorCode")
            }
        }

        // Only explicitly look for devices specifically broadcasting our Service UUID!
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build()

        val settings =
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()

        try {
            scanner.startScan(listOf(filter), settings, scanCallback)
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
                    // Ignore
                }
            }
            showToast("Controller not found. Make sure it's powered on and nearby.")
        }
    }

    private fun connectToController(device: BluetoothDevice) {
        if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
            return
        }

        try {
            var pendingProvisionPayload: ByteArray? = null
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
                        gatt.disconnect()
                        return
                    }

                    val service = gatt.getService(SERVICE_UUID)
                    if (service == null) {
                        // Not the target controller, disconnect quietly
                        gatt.disconnect()
                        return
                    }

                    val characteristic = service.getCharacteristic(CHAR_UUID)
                    if (characteristic == null) {
                        gatt.disconnect()
                        return
                    }

                    scanTimeoutJob?.cancel()
                    showToast("Controller matched!")

                    val ssid = information.wifiSsid
                    val password = information.wifiPassword

                    if (ssid.isBlank()) {
                        showToast("Please set a Wi-Fi SSID")
                        gatt.disconnect()
                        return
                    }
                    if (password.isBlank()) {
                        showToast("Please set a Wi-Fi password")
                        gatt.disconnect()
                        return
                    }

                    val json = "{\"ssid\":\"$ssid\",\"password\":\"$password\"}"
                    pendingProvisionPayload = json.toByteArray(StandardCharsets.UTF_8)

                    val notificationsEnabled =
                        gatt.setCharacteristicNotification(characteristic, true)
                    if (!notificationsEnabled) {
                        showToast("Failed to enable controller notifications")
                        gatt.disconnect()
                        return
                    }

                    val cccDescriptor = characteristic.getDescriptor(CCC_DESCRIPTOR_UUID)
                    if (cccDescriptor == null) {
                        showToast("Controller notification descriptor missing")
                        gatt.disconnect()
                        return
                    }

                    val descriptorWriteStatus = gatt.writeDescriptor(
                        cccDescriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    )
                    if (descriptorWriteStatus != BluetoothStatusCodes.SUCCESS) {
                        showToast("Failed to configure controller notifications")
                        gatt.disconnect()
                        return
                    }

                    provisioningTimeoutJob?.cancel()
                    provisioningTimeoutJob = scope.launch {
                        delay(provisioningTimeoutMs)
                        showToast("Setup timed out waiting for controller Wi-Fi connection")
                        gatt.disconnect()
                    }
                }

                override fun onCharacteristicWrite(
                    gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int
                ) {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        showToast("Credentials sent. Waiting for controller Wi-Fi connection...")
                    } else {
                        provisioningTimeoutJob?.cancel()
                        showToast("Failed to send credentials.")
                        gatt.disconnect()
                    }
                }

                override fun onDescriptorWrite(
                    gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int
                ) {
                    if (descriptor.uuid != CCC_DESCRIPTOR_UUID) {
                        return
                    }

                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        provisioningTimeoutJob?.cancel()
                        showToast("Failed to subscribe to controller status")
                        gatt.disconnect()
                        return
                    }

                    val service = gatt.getService(SERVICE_UUID)
                    val characteristic = service?.getCharacteristic(CHAR_UUID)
                    val payload = pendingProvisionPayload
                    if (characteristic == null || payload == null) {
                        provisioningTimeoutJob?.cancel()
                        showToast("Missing provisioning data")
                        gatt.disconnect()
                        return
                    }

                    characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    val writeStatus = gatt.writeCharacteristic(
                        characteristic, payload, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    )
                    if (writeStatus != BluetoothStatusCodes.SUCCESS) {
                        provisioningTimeoutJob?.cancel()
                        showToast("Failed to send Wi-Fi credentials")
                        gatt.disconnect()
                    }
                }

                override fun onCharacteristicChanged(
                    gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic
                ) {
                    handleProvisioningResponse(gatt, characteristic.value)
                }

                override fun onCharacteristicChanged(
                    gatt: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    value: ByteArray
                ) {
                    handleProvisioningResponse(gatt, value)
                }
            })
        } catch (_: SecurityException) {
            showToast("Bluetooth connect permission is required")
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun handleProvisioningResponse(gatt: BluetoothGatt, rawValue: ByteArray) {
        val payload = rawValue.toString(StandardCharsets.UTF_8).trim()
        if (payload.isBlank()) {
            return
        }

        val connectedIp = parseConnectedIp(payload)
        if (connectedIp != null) {
            provisioningTimeoutJob?.cancel()
            information.updateControllerAddress(connectedIp)
            showToast("Controller connected at $connectedIp")
            gatt.disconnect()
            startTcpHeartbeat()
            return
        }

        if (isFailureStatus(payload)) {
            provisioningTimeoutJob?.cancel()
            showToast("Controller failed to connect to Wi-Fi")
            gatt.disconnect()
            return
        }

        if (isConnectingStatus(payload)) {
            showToast("Controller is connecting to Wi-Fi...")
        }
    }

    private fun parseConnectedIp(payload: String): String? {
        val jsonIp = Regex("\"ip\"\\s*:\\s*\"([^\"]+)\"").find(payload)?.groupValues?.get(1)
        if (!jsonIp.isNullOrBlank() && isIpv4(jsonIp)) {
            return jsonIp
        }

        val labelIp =
            Regex("(?:IP|ip)\\s*[:=]\\s*([0-9]{1,3}(?:\\.[0-9]{1,3}){3})").find(payload)?.groupValues?.get(
                1
            )
        if (!labelIp.isNullOrBlank() && isIpv4(labelIp)) {
            return labelIp
        }

        return null
    }

    private fun isFailureStatus(payload: String): Boolean {
        val normalized = payload.lowercase()
        return normalized.contains("\"status\":\"failed\"") || normalized.contains("wifi_failed") || normalized.contains(
            "wifi_connect_failed"
        )
    }

    private fun isConnectingStatus(payload: String): Boolean {
        val normalized = payload.lowercase()
        return normalized.contains("\"status\":\"connecting\"") || normalized.contains("wifi_connecting")
    }

    private fun isIpv4(value: String): Boolean {
        val parts = value.split('.')
        if (parts.size != 4) {
            return false
        }
        return parts.all { part ->
            val num = part.toIntOrNull() ?: return false
            num in 0..255
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
        provisioningTimeoutJob?.cancel()
    }
}