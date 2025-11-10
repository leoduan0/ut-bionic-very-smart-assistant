package com.example.verysmartassistant

import android.Manifest
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import com.example.verysmartassistant.ui.theme.VerySmartAssistantTheme
import android.speech.RecognizerIntent
import android.app.Activity
import android.content.pm.PackageManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.ui.text.font.FontWeight
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.Locale

private const val MOM_PHONE_NUMBER = "1234567890"
private const val PSW_PHONE_NUMBER = "1234567890"

class MainActivity : ComponentActivity() {
    private val speechRecognizerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val matches = result.data!!.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val command = matches?.firstOrNull()?.lowercase(Locale.ROOT)
            if (command != null) {
                when {
                    "call mom" in command -> call(MOM_PHONE_NUMBER)
                }
            }
        }
    }

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
                    onSetup = { setup() },
                    onCallMom = { call(MOM_PHONE_NUMBER) },
                    onCallPSW = { call(PSW_PHONE_NUMBER) },
                    onOpenApartmentDoor = { openDoor("apartment") },
                    onOpenSuiteDoor = { openDoor("suite") },
                    onVoiceCommand = { startVoiceRecognition() },
                )
            }
        }
    }

    private fun setup() {
        // connects to ESP32
    }

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
    }

    private fun openSuiteDoor() {
        // opens apartment door
    }


    private fun startVoiceRecognition() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Say a command...")
        }
        speechRecognizerLauncher.launch(intent)
    }
}

@Composable
fun Home(
    modifier: Modifier = Modifier,
    onSetup: () -> Unit = {},
    onCallMom: () -> Unit = {},
    onCallPSW: () -> Unit = {},
    onOpenApartmentDoor: () -> Unit = {},
    onOpenSuiteDoor: () -> Unit = {},
    onVoiceCommand: () -> Unit = {},
) {
    Column(modifier = modifier) {
        Text("Very Smart Assistant", fontWeight = FontWeight.Bold)
        Text(
            text = "Hello! I'm your Very Smart Assistant from the University of Toronto Bioengineering Innovation and Outreach in Consulting Club (UT BIONIC)!",
        )
        Text("Setup", fontWeight = FontWeight.Bold)
        TextButton(onClick = { onSetup() }) { Text("Setup") }
        Text(
            "This button may be pressed to setup the app for the first time or troubleshoot the connection between the app and the controller.",
            fontSize = 12.sp,
            color = Color.Gray
        )
        Text(
            "Calls", fontWeight = FontWeight.Bold
        )
        TextButton(onClick = { onCallMom() }) { Text("Call Mom") }
        TextButton(onClick = { onCallPSW() }) { Text("Call PSW") }
        Text("Doors", fontWeight = FontWeight.Bold)
        TextButton(onClick = { onOpenApartmentDoor() }) { Text("Open Apartment Door") }
        TextButton(onClick = { onOpenSuiteDoor() }) { Text("Open Suite Door") }

        // TextButton(onClick = { onVoiceCommand() }) { Text("Activate Voice Command") }
    }
}
