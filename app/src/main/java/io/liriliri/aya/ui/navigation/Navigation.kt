package io.liriliri.aya.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import io.liriliri.aya.ui.device.DeviceConnectScreen
import io.liriliri.aya.ui.device.MainAppScreen

@Composable
fun AyaNavGraph(navController: NavHostController) {
    NavHost(
        navController = navController,
        startDestination = "connect"
    ) {
        composable("connect") {
            DeviceConnectScreen(
                onDeviceConnected = { deviceId ->
                    navController.navigate("main/$deviceId") {
                        popUpTo("connect") { inclusive = true }
                    }
                }
            )
        }
        composable("main/{deviceId}") { backStackEntry ->
            val deviceId = backStackEntry.arguments?.getString("deviceId") ?: ""
            MainAppScreen(
                deviceId = deviceId,
                onDisconnect = {
                    navController.navigate("connect") {
                        popUpTo("main/$deviceId") { inclusive = true }
                    }
                }
            )
        }
    }
}
