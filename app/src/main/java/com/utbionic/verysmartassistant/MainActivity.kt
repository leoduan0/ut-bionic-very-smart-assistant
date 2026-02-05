package com.utbionic.verysmartassistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.utbionic.verysmartassistant.ui.theme.VerySmartAssistantTheme
import kotlinx.coroutines.*

class MainActivity : ComponentActivity() {
    private val information: Information by viewModels()
    // Use MainScope to interact with UI, pass this scope to DeviceManager
    private val scope = MainScope() 
    private lateinit var deviceManager: DeviceManager

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.all { it }) {
            deviceManager.setupBluetooth()
        } else {
            Toast.makeText(this, "Error: Bluetooth/Location permissions denied.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        // Initialize the manager
        deviceManager = DeviceManager(this, scope, information)

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

    override fun onDestroy() {
        super.onDestroy()
        deviceManager.cleanup()
        scope.cancel()
    }

    private fun checkPermissionsAndSetup() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        // Fine location is needed for SSID retrieval in many android versions
        permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)

        val ungranted = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (ungranted.isEmpty()) {
            deviceManager.setupBluetooth()
        } else {
            requestPermissionLauncher.launch(ungranted.toTypedArray())
        }
    }

    private fun call(phoneNumber: String) {
        val intent = Intent(Intent.ACTION_DIAL).apply {
            data = ("tel:$phoneNumber").toUri()
        }
        startActivity(intent)
    }

    private fun openDoor(target: String) {
        val endpoint = when (target) {
            "apartment" -> "remote/apartment"
            "suite" -> "remote/suite"
            else -> return
        }

        deviceManager.sendRequestToController(endpoint) { success, response ->
            val msg = if (success) "$target door opened." else "$target failed: $response"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
    }
}

// Keep your Composable UI functions below or move them to a separate HomeScreen.kt file
@Composable
fun Home(
    modifier: Modifier = Modifier,
    information: Information,
    onSetup: () -> Unit,
    onCallMom: () -> Unit,
    onCallPSW: () -> Unit,
    onOpenApartmentDoor: () -> Unit,
    onOpenSuiteDoor: () -> Unit,
) {
    var showInfoDialog by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()

    Column(modifier = modifier.verticalScroll(scrollState)) {
        Text("Very Smart Assistant", fontWeight = FontWeight.Bold, fontSize = 22.sp)
        Text("Hello! I'm your Very Smart Assistant from the University of Toronto Bioengineering Innovation and Outreach in Consulting Club (UT BIONIC)!")

        Spacer(modifier = Modifier.height(12.dp))

        Text("Setup", fontWeight = FontWeight.Bold)
        Text("Mom Phone Number: ${information.momNumber}")
        Text("PSW Phone Number: ${information.pswNumber}")
        Text("Controller Address: ${information.controllerAddress}")
        Text("Wi-Fi Password: ${if (information.wifiPassword.isBlank()) "Not set" else "Set"}")
        
        Button(onClick = onSetup, modifier = Modifier.fillMaxWidth()) { Text("Setup") }
        Text("Setup the app for the first time or repair the connection.", fontSize = 12.sp, color = Color.Gray)
        
        Button(onClick = { showInfoDialog = true }, modifier = Modifier.fillMaxWidth()) { Text("Update Information") }
        Text("Update information like phone numbers", fontSize = 12.sp, color = Color.Gray)

        Spacer(modifier = Modifier.height(12.dp))

        Text("Calls", fontWeight = FontWeight.Bold)
        Button(onClick = onCallMom, modifier = Modifier.fillMaxWidth()) { Text("Call Mom") }
        Button(onClick = onCallPSW, modifier = Modifier.fillMaxWidth()) { Text("Call PSW") }

        Spacer(modifier = Modifier.height(12.dp))

        Text("Doors", fontWeight = FontWeight.Bold)
        Button(onClick = onOpenApartmentDoor, modifier = Modifier.fillMaxWidth()) { Text("Open Apartment Door") }
        Button(onClick = onOpenSuiteDoor, modifier = Modifier.fillMaxWidth()) { Text("Open Suite Door") }
    }

    if (showInfoDialog) {
        // Assuming InfoDialog is defined elsewhere in your project
        InfoDialog(
            currentMomNumber = information.momNumber,
            currentPswNumber = information.pswNumber,
            currentControllerAddress = information.controllerAddress,
            currentWifiPassword = information.wifiPassword,
            onDismissRequest = { showInfoDialog = false },
            onConfirmation = { newMom, newPsw, newAddr, newPass ->
                information.updateMomNumber(newMom)
                information.updatePswNumber(newPsw)
                information.updateControllerAddress(newAddr)
                information.updateWifiPassword(newPass)
                showInfoDialog = false
            },
        )
    }
}