package com.example.verysmartassistant

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.example.verysmartassistant.ui.theme.VerySmartAssistantTheme
import android.speech.RecognizerIntent
import android.app.Activity
import android.content.pm.PackageManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.Locale

class MainActivity : ComponentActivity() {

    private val speechRecognizerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val matches = result.data!!.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val command = matches?.firstOrNull()?.lowercase(Locale.ROOT)
            if (command != null) {
                when {
                    "call mom" in command -> callMom()
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
                    onVoiceCommand = { startVoiceRecognition() },
                    onCallMom = { callMom() }
                )
            }
        }
    }

    private fun startVoiceRecognition() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Say a command...")
        }
        speechRecognizerLauncher.launch(intent)
    }

    private fun callMom() {
        val intent = Intent(Intent.ACTION_CALL).apply {
            data = Uri.parse("tel:1234567890") // Replace with mom’s actual number
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CALL_PHONE), 1)
        } else {
            startActivity(intent)
        }

    }
}

@Composable
fun Home(onVoiceCommand: () -> Unit = {}, onCallMom: () -> Unit = {}) {
    val context = LocalContext.current
    Column {
        Greeting()
        ConnectivityButton(::connectToController)
        Text("Call Mom Section")
        TextButton(onClick = { onCallMom() }) { Text("Call Mom") }
        TextButton(onClick = { onVoiceCommand() }) { Text("Activate Voice Command") }
    }
}

@Composable
fun Greeting(modifier: Modifier = Modifier) {
    Text(
        text = "Hello! I'm your Very Smart Assistant from the University of Toronto Bioengineering Innovation and Outreach in Consulting Club (UT BIONIC)!",
        modifier = modifier
    )
}

@Composable
fun ConnectivityButton(onClick: () -> Unit) {
    TextButton(
        onClick = { onClick() }
    ) {
        Text("Connect to Controller")
    }
}

fun connectToController() {
    println("This function connects the app to the controller.")
}
