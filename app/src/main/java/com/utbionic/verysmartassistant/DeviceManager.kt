package com.utbionic.verysmartassistant

import android.Manifest
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.*
import java.io.OutputStream
import java.net.*
import java.util.UUID

class DeviceManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val information: Information
) {
    private var heartbeatJob: Job? = null
    private val maxRetries = 3
    private val SERVICE_UUID = UUID.fromString("0000abcd-0000-1000-8000-00805f9b34fb")
    private val CHAR_UUID = UUID.fromString("0000dcba-0000-1000-8000-00805f9b34fb")

    // --- TCP / Network Functions ---

    fun startTcpHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                var success = false
                repeat(maxRetries) {
                    if (sendTcpHeartbeat(information.controllerAddress)) {
                        success = true
                        return@repeat
                    } else {
                        delay(5000)
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
            Toast.makeText(context, "Lost connection. Please repair with controller.", Toast.LENGTH_LONG).show()
        }
    }

    private fun broadcastPairingMode() {
        scope.launch(Dispatchers.IO) {
            try {
                val socket = DatagramSocket()
                val buffer = "PAIRING_MODE_REQUEST".toByteArray()
                val packet = DatagramPacket(buffer, buffer.size, InetAddress.getByName("255.255.255.255"), 4210)
                socket.broadcast = true
                socket.send(packet)
                socket.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun sendRequestToController(endpoint: String, callback: (Boolean, String?) -> Unit) {
        scope.launch(Dispatchers.IO) {
            try {
                val ip = information.controllerAddress
                val port = 4211
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(ip, port), 3000)
                    val out: OutputStream = socket.getOutputStream()
                    out.write("COMMAND:$endpoint\n".toByteArray())
                    out.flush()

                    val input = socket.getInputStream()
                    val buffer = ByteArray(1024)
                    val bytesRead = input.read(buffer)
                    val response = if (bytesRead > 0) String(buffer, 0, bytesRead) else null

                    withContext(Dispatchers.Main) { callback(true, response) }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) { callback(false, e.message) }
            }
        }
    }

    // --- Bluetooth Functions ---

    fun setupBluetooth() {
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter == null || !adapter.isEnabled) {
            Toast.makeText(context, "Please enable Bluetooth first", Toast.LENGTH_SHORT).show()
            return
        }

        val scanner = adapter.bluetoothLeScanner
        Toast.makeText(context, "Searching for the controller...", Toast.LENGTH_SHORT).show()

        val scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return

                if (result?.device?.name?.contains("ESP32", ignoreCase = true) == true) {
                    scanner.stopScan(this)
                    connectToController(result.device)
                }
            }
        }
        scanner.startScan(scanCallback)
    }

    private fun connectToController(device: BluetoothDevice) {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return

        device.connectGatt(context, false, object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    gatt.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    gatt.close()
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                val service = gatt.getService(SERVICE_UUID)
                val characteristic = service?.getCharacteristic(CHAR_UUID)
                if (characteristic == null) {
                    showToast("Controller service not found.")
                    return
                }

                val ssid = NetworkUtils.getSSID(context)
                val password = information.wifiPassword
                
                // Basic Validation
                if (ssid.isBlank() || ssid.equals("<unknown ssid>", ignoreCase = true)) {
                    showToast("Wi-Fi SSID unavailable. Check Location/Permissions.")
                    return
                }
                if (password.isBlank()) {
                    showToast("Please set Wi-Fi password in settings.")
                    return
                }

                val payload = "IP:${NetworkUtils.getLocalIpAddress(context)};SSID:$ssid;PASSWORD:$password"
                val bytes = payload.toByteArray()
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeCharacteristic(characteristic, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                } else {
                    // Deprecated way for older Android
                    characteristic.value = bytes
                    gatt.writeCharacteristic(characteristic)
                }
            }

            override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                showToast(if (status == BluetoothGatt.GATT_SUCCESS) "Credentials sent!" else "Failed to send credentials.")
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    gatt.disconnect()
                    startTcpHeartbeat()
                }
            }
        })
    }

    private fun showToast(msg: String) {
        scope.launch(Dispatchers.Main) {
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }
    
    fun cleanup() {
        heartbeatJob?.cancel()
    }
}