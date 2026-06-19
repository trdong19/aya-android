package io.liriliri.aya.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.AndroidEntryPoint
import io.liriliri.aya.ui.navigation.AyaNavGraph
import io.liriliri.aya.ui.theme.AyaTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AyaTheme {
                val navController = rememberNavController()
                AyaNavGraph(navController = navController)
            }
        }
    }
}
