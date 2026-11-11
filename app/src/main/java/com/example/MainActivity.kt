package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.sumi.terminal.SumiApp
import dev.sumi.terminal.SumiRootApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val sumiApp = (applicationContext as? SumiApp) ?: SumiApp().apply { onCreate() }
        setContent {
            SumiRootApp(app = sumiApp)
        }
    }
}
