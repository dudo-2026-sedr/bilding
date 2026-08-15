package com.agenttask

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.agenttask.ui.ChatScreen
import com.agenttask.ui.ChatViewModel
import com.agenttask.ui.SettingsScreen
import com.agenttask.ui.theme.AgentTaskTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val app = application as App
            val vm: ChatViewModel = viewModel(factory = object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = ChatViewModel(app) as T
            })
            val theme by vm.theme.collectAsState()
            var screen by remember { mutableStateOf("chat") }

            AgentTaskTheme(theme) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    when (screen) {
                        "settings" -> SettingsScreen(vm) { screen = "chat" }
                        else -> ChatScreen(vm) { screen = "settings" }
                    }
                }
            }
        }
    }
}
