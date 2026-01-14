package com.utbionic.verysmartassistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import android.os.Build
import com.utbionic.verysmartassistant.ui.theme.VerySmartAssistantTheme
import android.widget.Toast
import kotlinx.coroutines.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothProfile
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import java.util.UUID
import androidx.activity.result.contract.ActivityResultContracts
import java.net.InetAddress
import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import java.io.OutputStream
import java.net.Socket
import java.net.InetSocketAddress


class MainActivity : ComponentActivity() {
    private val information: Information by viewModels()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var heartbeatJob: Job? = null

    private val maxRetries = 3

    private val SERVICE_UUID = UUID.fromString("0000abcd-0000-1000-8000-00805f9b34fb")
    private val CHAR_UUID = UUID.fromString("0000dcba-0000-1000-8000-00805f9b34fb")

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.all { it }) {
            setup()
        } else {
            showToast("Error: Bluetooth permissions denied.")
        }
    }

    private fun startTcpHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive) {
                var success = false

                // Retry up to maxRetries
                repeat(maxRetries) { attempt ->
                    if (sendTcpHeartbeat(information.controllerAddress)) {
                        success = true
                        return@repeat
                    } else {
                        delay(5000) // wait 5s before next attempt
                    }
                }

                if (!success) {
                    // Trigger pairing mode and alert the user
                    onConnectionLost()
                    return@launch
                }

                // Wait 1 hour until the next heartbeat
                delay(60 * 60 * 1000L)
            }
        }
    }

    private fun sendTcpHeartbeat(ip: String, port: Int = 4211, timeout: Int = 3000): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(ip, port), timeout)
                val out: OutputStream = socket.getOutputStream()
                val message = "ARE_YOU_ALIVE_BRO\n"
                out.write(message.toByteArray())
                out.flush()
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun onConnectionLost() {
        broadcastPairingMode()
        runOnUiThread {
            Toast.makeText(
                this@MainActivity,
                "I lost connection with the controller. Please repair me with the controller.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun broadcastPairingMode() {
        scope.launch {
            try {
                val socket = DatagramSocket()
                val message = "PAIRING_MODE_REQUEST"
                val buffer = message.toByteArray()
                val address = InetAddress.getByName("255.255.255.255")
                val packet = DatagramPacket(buffer, buffer.size, address, 4210)
                socket.broadcast = true
                socket.send(packet)
                socket.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        heartbeatJob?.cancel()
        scope.cancel()
    }

    private fun getSSID(): String {
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val info = wifiManager.connectionInfo
        // On Android 14, this returns "<unknown ssid>" if Location is OFF
        return info.ssid.replace("\"", "")
    }

    private fun getLocalIpAddress(): String {
        val connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val linkProperties =
            connectivityManager.getLinkProperties(connectivityManager.activeNetwork)
        val ipAddress =
            linkProperties?.linkAddresses?.firstOrNull { it.address.isSiteLocalAddress }?.address
        return ipAddress?.hostAddress ?: "0.0.0.0"
    }

    private fun getWifiPassword(): String = "" // TODO


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            VerySmartAssistantTheme {
                Home(
                    modifier = Modifier
                        .fillMaxSize()
                        .systemBarsPadding()
                        .padding(horizontal = 16.dp),
                    information = information,
                    onSetup = { checkPermissionsAndSetup() },
                    onCallMom = { call(information.momNumber) },
                    onCallPSW = { call(information.pswNumber) },
                    onOpenApartmentDoor = { openDoor("apartment") },
                    onOpenSuiteDoor = { openDoor("suite") },
                )
            }
        }
    }

    private fun sendRequestToController(
        endpoint: String, callback: (success: Boolean, response: String?) -> Unit
    ) {
        scope.launch {
            try {
                // Use the controller IP from your Information object
                val ip = information.controllerAddress
                val port = 4211 // Same TCP port your ESP32 listens on

                Socket().use { socket ->
                    socket.connect(InetSocketAddress(ip, port), 3000)
                    val out: OutputStream = socket.getOutputStream()

                    // Send the command string (endpoint)
                    val message = "COMMAND:$endpoint\n"
                    out.write(message.toByteArray())
                    out.flush()

                    // Optionally read response
                    val input = socket.getInputStream()
                    val buffer = ByteArray(1024)
                    val bytesRead = input.read(buffer)
                    val response = if (bytesRead > 0) String(buffer, 0, bytesRead) else null

                    withContext(Dispatchers.Main) {
                        callback(true, response)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    callback(false, e.message)
                }
            }
        }
    }

    private fun checkPermissionsAndSetup() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)

        val ungranted = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (ungranted.isEmpty()) {
            setup()
        } else {
            requestPermissionLauncher.launch(ungranted.toTypedArray())
        }
    }

    private fun setup() {
        val adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter == null || !adapter.isEnabled) {
            showToast("Please enable Bluetooth first")
            return
        }

        val scanner = adapter.bluetoothLeScanner
        showToast("Searching for the controller...")

        val scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                if (ActivityCompat.checkSelfPermission(
                        this@MainActivity, Manifest.permission.BLUETOOTH_CONNECT
                    ) != PackageManager.PERMISSION_GRANTED
                ) return

                if (result?.device?.name?.contains("ESP32", ignoreCase = true) == true) {
                    scanner.stopScan(this)
                    connectToController(result.device)
                }
            }
        }
        scanner.startScan(scanCallback)
    }

    private fun connectToController(device: BluetoothDevice) {
        if (ActivityCompat.checkSelfPermission(
                this, Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) return

        device.connectGatt(this, false, object : BluetoothGattCallback() {
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
                if (characteristic != null) {
                    val payload =
                        "IP:${getLocalIpAddress()};SSID:${getSSID()};PASSWORD:${getWifiPassword()}"
                    sendData(gatt, characteristic, payload)
                }
            }

            override fun onCharacteristicWrite(
                gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int
            ) {
                runOnUiThread {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        Toast.makeText(
                            this@MainActivity, "Credentials sent successfully!", Toast.LENGTH_SHORT
                        ).show()
                        gatt.disconnect()

                        // Start TCP heartbeat
                        startTcpHeartbeat()
                    } else {
                        Toast.makeText(
                            this@MainActivity, "Failed to send credentials.", Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        })
    }

    private fun sendData(
        gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, data: String
    ) {
        val bytes = data.toByteArray()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(
                characteristic, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            )
        } else {
            @Suppress("DEPRECATION") characteristic.value = bytes
            @Suppress("DEPRECATION") gatt.writeCharacteristic(characteristic)
        }
    }

    private fun showToast(msg: String) =
        runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }

    private fun call(phoneNumber: String) {
        val intent = Intent(Intent.ACTION_CALL).apply {
            data = ("tel:$phoneNumber").toUri()
        }

        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.CALL_PHONE
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            startActivity(intent)
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CALL_PHONE), 1)
        }
    }

    private fun openDoor(target: String) {
        when (target) {
            "apartment" -> openApartmentDoor()
            "suite" -> openSuiteDoor()
            else -> print("Invalid target")
        }
    }

    private fun openApartmentDoor() {
        // opens apartment door
        sendRequestToController("remote/apartment") { success, response ->
            if (success) {
                println("Success: apartment door opened. $response")
            } else {
                println("Failure: apartment door not opened. $response")
            }
        }
    }

    private fun openSuiteDoor() {
        // opens apartment door
        sendRequestToController("remote/suite") { success, response ->
            if (success) {
                println("Success: suite door opened. $response")
            } else {
                println("Failure: suite door not opened. $response")
            }
        }
    }
}


@Composable
fun Home(
    modifier: Modifier = Modifier,
    information: Information,
    onSetup: () -> Unit = {},
    onCallMom: () -> Unit = {},
    onCallPSW: () -> Unit = {},
    onOpenApartmentDoor: () -> Unit = {},
    onOpenSuiteDoor: () -> Unit = {},
) {
    var showInfoDialog by remember { mutableStateOf(false) }

    Column(modifier = modifier) {
        Text("Very Smart Assistant", fontWeight = FontWeight.Bold)
        Text(
            text = "Hello! I'm your Very Smart Assistant from the University of Toronto Bioengineering Innovation and Outreach in Consulting Club (UT BIONIC)!",
        )

        Text("Setup", fontWeight = FontWeight.Bold)
        Text("Mom Phone Number: ${information.momNumber}")
        Text("PSW Phone Number: ${information.pswNumber}")
        TextButton(onClick = { onSetup() }) { Text("Setup") }
        Text(
            "Setup the app for the first time or repair the connection between the app and the controller.",
            fontSize = 12.sp,
            color = Color.Gray
        )
        TextButton(onClick = { showInfoDialog = true }) { Text("Update Information") }
        Text(
            "Update information like phone numbers", fontSize = 12.sp, color = Color.Gray
        )

        Text(
            "Calls", fontWeight = FontWeight.Bold
        )
        TextButton(onClick = { onCallMom() }) { Text("Call Mom") }
        TextButton(onClick = { onCallPSW() }) { Text("Call PSW") }

        Text("Doors", fontWeight = FontWeight.Bold)
        TextButton(onClick = { onOpenApartmentDoor() }) { Text("Open Apartment Door") }
        TextButton(onClick = { onOpenSuiteDoor() }) { Text("Open Suite Door") }
    }

    if (showInfoDialog) {
        InfoDialog(
            currentMomNumber = information.momNumber,
            currentPswNumber = information.pswNumber,
            onDismissRequest = { showInfoDialog = false },
            onConfirmation = { newMomNumber, newPswNumber ->
                information.updateMomNumber(newMomNumber)
                information.updatePswNumber(newPswNumber)
                showInfoDialog = false
            },
        )
    }
}