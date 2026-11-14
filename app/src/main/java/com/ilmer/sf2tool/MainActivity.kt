package com.ilmer.sf2tool

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import com.ilmer.sf2tool.ui.MainScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val dark = isSystemInDarkTheme()
            val colors = if (dark) darkColorScheme(primary = Color(0xFF81C784), secondary = Color(0xFFA5D6A7))
            else lightColorScheme(primary = Color(0xFF2E7D32), secondary = Color(0xFF558B2F))
            MaterialTheme(colorScheme = colors) { MainScreen() }
        }
    }
}
