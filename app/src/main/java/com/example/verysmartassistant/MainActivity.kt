//This whole application should have only one page.
//
//Implement:
//
//* Call PSW
//* Call mom
//* Open apartment door
//* Open suite door
//
//For *each* of the tasks above:
//
//* Create a function that does the specified task. Minimize the number of helper functions as the tasks should be simple enough to not need any
//* Donate an App Action to Google Assistant that performs the task
//* Create a section that has the text of the task (e.g. "open suite door"). Have a button that performs the action (in case manual triggering is needed)

package com.example.verysmartassistant

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
//import androidx.compose.ui.tooling.preview.Preview
import com.example.verysmartassistant.ui.theme.VerySmartAssistantTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            VerySmartAssistantTheme {
                Home()
            }
        }
    }
}

@Composable
fun Home() {
    Column {
        Greeting()
        ConnectivityButton(::connectToController)
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
    // logic
    println("This function connects the app to the controller.")
}
