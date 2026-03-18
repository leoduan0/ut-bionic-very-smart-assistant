package com.utbionic.verysmartassistant

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import com.utbionic.verysmartassistant.ui.theme.VerySmartAssistantTheme
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel

class MainActivity : ComponentActivity() {
    private val information: Information by viewModels()

    // Use MainScope to interact with UI, pass this scope to DeviceManager
    private val scope = MainScope()
    private lateinit var deviceManager: DeviceManager
    private var pendingAction: (() -> Unit)? = null
    private var statusMessage by mutableStateOf("Ready. Tap Setup to test controller.")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        deviceManager = DeviceManager(this, scope, information)
        handleIntent(intent)

        setContent {
            VerySmartAssistantTheme {
                LaunchedEffect(information.isLoaded) {
                    if (information.isLoaded) {
                        pendingAction?.invoke()
                        pendingAction = null
                    }
                }
                Home(
                    modifier = Modifier
                        .fillMaxSize()
                        .systemBarsPadding()
                        .padding(horizontal = 16.dp),
                    information = information,
                    statusMessage = statusMessage,
                    onSetup = {
                        setup()
                    },
                    onCallMom = {
                        showMessage("Calling mom")
                        call(information.momNumber)
                    },
                    onCallPSW = {
                        showMessage("Calling PSW")
                        call(information.pswNumber)
                    },
                    onOpenApartmentDoor = { openApartmentDoor() },
                    onOpenSuiteDoor = { openSuiteDoor() },
                    onInformationUpdated = { showMessage("Information updated") },
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        deviceManager.cleanup()
        scope.cancel()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun setup() {
        showMessage("Starting setup...")
        deviceManager.testConnection { isConnected ->
            if (isConnected) {
                showMessage("Connected to the controller")
                deviceManager.startTcpHeartbeat()
            } else {
                showMessage("Cannot reach controller. Check address and Wi-Fi network.")
            }
        }
    }

    private fun call(phoneNumber: String) {
        val sanitizedNumber = phoneNumber.trim()
        if (sanitizedNumber.isBlank()) {
            showMessage("Phone number is not set")
            return
        }

        val intent = Intent(Intent.ACTION_DIAL).apply {
            data = ("tel:$sanitizedNumber").toUri()
        }
        startActivity(intent)
    }

    private fun openApartmentDoor() {
        openDoor("apartment")
    }

    private fun openSuiteDoor() {
        openDoor("suite")
    }

    private fun openDoor(target: String) {
        val normalizedTarget = target.trim().lowercase()
        val label = if (normalizedTarget == "apartment") "Apartment" else "Suite"

        showMessage("$label button tapped. Sending command...")
        deviceManager.sendDoorCommand(normalizedTarget) { success, message ->
            val resultPrefix = if (success) "ACK" else "FAILED"
            val statusText = if (success) {
                "$label: $resultPrefix - controller accepted command ($message)"
            } else {
                "$label: $resultPrefix - $message"
            }
            showMessage(statusText)
        }
    }

    private fun showMessage(message: String) {
        statusMessage = message
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun runWhenInfoLoaded(action: () -> Unit) {
        if (information.isLoaded) {
            action()
        } else {
            pendingAction = action
        }
    }

    private fun handleIntent(incoming: Intent) {
        if (incoming.action != Intent.ACTION_VIEW) return

        val uri = incoming.data
        val doorFromUri = uri?.getQueryParameter("door_type")
        val doorExtra = incoming.getStringExtra("door_type")
        val contactFromUri = uri?.getQueryParameter("contact")
        val contactExtra = incoming.getStringExtra("contact")
        val feature = incoming.getStringExtra("feature")

        val doorType = when {
            !doorFromUri.isNullOrBlank() -> doorFromUri
            !doorExtra.isNullOrBlank() -> doorExtra
            !feature.isNullOrBlank() && feature.contains(
                "apartment", ignoreCase = true
            ) -> "apartment"

            !feature.isNullOrBlank() && feature.contains("suite", ignoreCase = true) -> "suite"
            else -> null
        }

        val contactType = when {
            !contactFromUri.isNullOrBlank() -> contactFromUri
            !contactExtra.isNullOrBlank() -> contactExtra
            !feature.isNullOrBlank() && feature.contains("mom", ignoreCase = true) -> "mom"
            !feature.isNullOrBlank() && feature.contains("psw", ignoreCase = true) -> "psw"
            else -> null
        }

        if (doorType != null) {
            runWhenInfoLoaded { openDoor(doorType) }
        } else if (contactType != null) {
            runWhenInfoLoaded {
                when (contactType.lowercase()) {
                    "mom" -> call(information.momNumber)
                    "psw" -> call(information.pswNumber)
                }
            }
        }
    }
}

// Keep your Composable UI functions below or move them to a separate HomeScreen.kt file
@Composable
fun Home(
    modifier: Modifier = Modifier,
    information: Information,
    statusMessage: String,
    onSetup: () -> Unit,
    onCallMom: () -> Unit,
    onCallPSW: () -> Unit,
    onOpenApartmentDoor: () -> Unit,
    onOpenSuiteDoor: () -> Unit,
    onInformationUpdated: () -> Unit,
) {
    var showInfoDialog by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()

    Column(modifier = modifier.verticalScroll(scrollState)) {
        Text("Very Smart Assistant", fontWeight = FontWeight.Bold, fontSize = 22.sp)

        Spacer(modifier = Modifier.height(12.dp))

        Text("Setup", fontWeight = FontWeight.Bold)
        Text("Mom Phone Number: ${information.momNumber}")
        Text("PSW Phone Number: ${information.pswNumber}")
        Text("Controller Address: ${information.controllerAddress}")

        Button(onClick = onSetup, modifier = Modifier.fillMaxWidth()) { Text("Setup") }
        Text(
            "Checks controller connection over Wi-Fi.",
            fontSize = 12.sp,
            color = Color.Gray
        )

        Button(
            onClick = { showInfoDialog = true }, modifier = Modifier.fillMaxWidth()
        ) { Text("Update Information") }
        Text("Update information like phone numbers", fontSize = 12.sp, color = Color.Gray)

        Spacer(modifier = Modifier.height(12.dp))

        Text("Calls", fontWeight = FontWeight.Bold)
        Button(onClick = onCallMom, modifier = Modifier.fillMaxWidth()) { Text("Call Mom") }
        Button(onClick = onCallPSW, modifier = Modifier.fillMaxWidth()) { Text("Call PSW") }

        Spacer(modifier = Modifier.height(12.dp))

        Text("Doors", fontWeight = FontWeight.Bold)
        Button(
            onClick = onOpenApartmentDoor, modifier = Modifier.fillMaxWidth()
        ) { Text("Open Apartment Door") }
        Button(
            onClick = onOpenSuiteDoor, modifier = Modifier.fillMaxWidth()
        ) { Text("Open Suite Door") }

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "Last action: $statusMessage",
            fontSize = 12.sp,
            color = Color.Gray,
        )

        Text(
            "Made with ❤️ by the University of Toronto Bioengineering Innovation and Outreach in Consulting Club (UT BIONIC).",
            fontSize = 12.sp,
            color = Color.Gray
        )
    }

    if (showInfoDialog) {
        // Assuming InfoDialog is defined elsewhere in your project
        InfoDialog(
            currentMomNumber = information.momNumber,
            currentPswNumber = information.pswNumber,
            currentControllerAddress = information.controllerAddress,
            onDismissRequest = { showInfoDialog = false },
            onConfirmation = { newMom, newPsw, newAddr ->
                information.updateMomNumber(newMom)
                information.updatePswNumber(newPsw)
                information.updateControllerAddress(newAddr)
                onInformationUpdated()
            },
        )
    }
}