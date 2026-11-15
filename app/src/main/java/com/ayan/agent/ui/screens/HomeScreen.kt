package com.ayan.agent.ui.screens

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavController

/** Step 3b — Home with bottom navigation: Chat / Terminal / Settings (PRD v0.3). */
@Composable
fun HomeScreen(navController: NavController) {
    var tab by rememberSaveable { mutableStateOf(HomeTab.CHAT) }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == HomeTab.CHAT,
                    onClick = { tab = HomeTab.CHAT },
                    icon = { Icon(Icons.Default.Chat, contentDescription = null) },
                    label = { Text("Chat") }
                )
                NavigationBarItem(
                    selected = tab == HomeTab.TERMINAL,
                    onClick = { tab = HomeTab.TERMINAL },
                    icon = { Icon(Icons.Default.Terminal, contentDescription = null) },
                    label = { Text("Terminal") }
                )
                NavigationBarItem(
                    selected = tab == HomeTab.SETTINGS,
                    onClick = { tab = HomeTab.SETTINGS },
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    label = { Text("Settings") }
                )
            }
        }
    ) { padding ->
        when (tab) {
            HomeTab.CHAT -> ChatScreen(
                modifier = Modifier.padding(padding),
                onOpenEditor = { navController.navigate("editor") }
            )
            HomeTab.TERMINAL -> TerminalScreen(modifier = Modifier.padding(padding))
            HomeTab.SETTINGS -> SettingsScreen(modifier = Modifier.padding(padding))
        }
    }
}

enum class HomeTab { CHAT, TERMINAL, SETTINGS }
