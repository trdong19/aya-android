package io.liriliri.aya.ui

import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.AndroidEntryPoint
import io.liriliri.aya.ui.navigation.AyaNavGraph
import io.liriliri.aya.ui.theme.AyaTheme
import rikka.shizuku.Shizuku

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val shizukuPermissionListener = object : Shizuku.OnRequestPermissionResultListener {
        override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
            val granted = grantResult == PackageManager.PERMISSION_GRANTED
            Log.d("MainActivity", "Shizuku permission result: granted=$granted")
        }
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        Log.d("MainActivity", "Shizuku binder received")
        // Request permission when Shizuku binder becomes available
        try {
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                Shizuku.requestPermission(1001)
            }
        } catch (e: Exception) {
            Log.d("MainActivity", "Shizuku permission check failed: ${e.message}")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Register Shizuku listeners
        try {
            Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        } catch (e: Exception) {
            Log.d("MainActivity", "Shizuku not available: ${e.message}")
        }

        setContent {
            AyaTheme {
                val navController = rememberNavController()
                AyaNavGraph(navController = navController)
            }
        }
    }

    override fun onDestroy() {
        try {
            Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
            Shizuku.removeBinderReceivedListener(binderReceivedListener)
        } catch (_: Exception) {}
        super.onDestroy()
    }
}
