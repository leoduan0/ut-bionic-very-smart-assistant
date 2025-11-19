package com.utbionic.verysmartassistant

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
import com.utbionic.verysmartassistant.ui.theme.VerySmartAssistantTheme
import android.speech.RecognizerIntent
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.ui.text.font.FontWeight
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.Locale
import okhttp3.*
import java.io.IOException

private const val MOM_PHONE_NUMBER = "1234567890"
private const val PSW_PHONE_NUMBER = "1234567890"
private const val CONTROLLER_IP_ADDRESS = "127.0.0.1"

class MainActivity : ComponentActivity() {
    private val client = OkHttpClient()

    private fun sendRequestToController(
        endpoint: String, callback: (success: Boolean, response: String?) -> Unit
    ) {
        val url = "http://$CONTROLLER_IP_ADDRESS/$endpoint"
        val request = Request.Builder().url(url).build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread { callback(false, e.message) }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val body = response.body?.string()
                    runOnUiThread { callback(true, body) }
                }
            }
        })
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
    onSetup: () -> Unit = {},
    onCallMom: () -> Unit = {},
    onCallPSW: () -> Unit = {},
    onOpenApartmentDoor: () -> Unit = {},
    onOpenSuiteDoor: () -> Unit = {},
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
    }
}
