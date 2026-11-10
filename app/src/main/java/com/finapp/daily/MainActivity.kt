package com.finapp.daily

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.finapp.daily.ui.AppState
import com.finapp.daily.ui.FinanceApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val state = remember { AppState(applicationContext) }

                    LaunchedEffect(Unit) {
                        state.loadFromDb()
                        state.refreshIfStale()
                    }

                    DisposableEffect(Unit) {
                        onDispose { state.shutdown() }
                    }

                    FinanceApp(state)
                }
            }
        }
    }
}
