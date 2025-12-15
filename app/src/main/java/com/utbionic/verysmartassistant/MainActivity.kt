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
import com.utbionic.verysmartassistant.ui.theme.VerySmartAssistantTheme
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

class MainActivity : ComponentActivity() {
    private val information: Information by viewModels()

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
                    onSetup = { setup() },
                    onCallMom = { call(information.momNumber) },
                    onCallPSW = { call(information.pswNumber) },
                    onOpenApartmentDoor = { openDoor("apartment") },
                    onOpenSuiteDoor = { openDoor("suite") },
                )
            }
        }
    }

    private val client = OkHttpClient()

    private fun sendRequestToController(
        endpoint: String, callback: (success: Boolean, response: String?) -> Unit
    ) {
        val url = "http://${information.controllerAddress}/$endpoint"
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

    // TODO implement
    private fun setup() {
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
            "Setup the app for the first time or troubleshoot the connection between the app and the controller.",
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