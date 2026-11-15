package com.ayan.agent

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.ayan.agent.ui.screens.CodeEditorScreen
import com.ayan.agent.ui.screens.HomeScreen
import com.ayan.agent.ui.screens.LoadingScreen
import com.ayan.agent.ui.screens.SetupScreen
import com.ayan.agent.ui.screens.SuccessScreen
import com.ayan.agent.ui.theme.AyanAgentTheme

/**
 * Ayan Agent — entry point.
 * PRD v0.3 flow: Loading → Setup → Success (+ Continue) → Home
 * (Home = bottom nav: Chat / Terminal / Settings) + Code Editor.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AyanAgentTheme {
                AyanAgentNav()
            }
        }
    }
}

object Routes {
    const val LOADING = "loading"
    const val SETUP = "setup"
    const val SUCCESS = "success"
    const val HOME = "home"
    const val EDITOR = "editor"
}

@Composable
fun AyanAgentNav() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = Routes.LOADING) {
        composable(Routes.LOADING) {
            LoadingScreen(
                onFinished = { setupDone ->
                    if (setupDone) {
                        navController.navigate(Routes.HOME) {
                            popUpTo(Routes.LOADING) { inclusive = true }
                        }
                    } else {
                        navController.navigate(Routes.SETUP) {
                            popUpTo(Routes.LOADING) { inclusive = true }
                        }
                    }
                }
            )
        }
        composable(Routes.SETUP) {
            SetupScreen(
                onSetupComplete = {
                    navController.navigate(Routes.SUCCESS) {
                        popUpTo(Routes.SETUP) { inclusive = true }
                    }
                }
            )
        }
        composable(Routes.SUCCESS) {
            SuccessScreen(
                onContinue = {
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.SUCCESS) { inclusive = true }
                    }
                }
            )
        }
        composable(Routes.HOME) {
            HomeScreen(navController = navController)
        }
        composable(Routes.EDITOR) {
            CodeEditorScreen()
        }
    }
}
