package io.liriliri.aya.ui.device

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.liriliri.aya.adb.DeviceManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ScreenshotViewModel @Inject constructor(
    private val deviceManager: DeviceManager
) : ViewModel() {
    private val _screenshot = MutableStateFlow<ByteArray?>(null)
    val screenshot: StateFlow<ByteArray?> = _screenshot.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    fun capture(deviceId: String) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                _screenshot.value = deviceManager.screencap(deviceId)
            } catch (_: Exception) {} finally {
                _isLoading.value = false
            }
        }
    }
}

@Composable
fun ScreenshotPanel(
    deviceId: String,
    viewModel: ScreenshotViewModel = hiltViewModel()
) {
    val screenshot by viewModel.screenshot.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(deviceId) {
        viewModel.capture(deviceId)
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Toolbar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = { viewModel.capture(deviceId) },
                enabled = !isLoading,
                shape = RoundedCornerShape(8.dp)
            ) {
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Icon(Icons.Default.CameraAlt, null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("截图")
            }
            Spacer(modifier = Modifier.width(8.dp))
            IconButton(onClick = { scale = 1f; offsetX = 0f; offsetY = 0f }) {
                Icon(Icons.Default.FitScreen, "适配")
            }
            IconButton(onClick = { scale = (scale * 1.2f).coerceAtMost(5f) }) {
                Icon(Icons.Default.ZoomIn, "放大")
            }
            IconButton(onClick = { scale = (scale / 1.2f).coerceAtLeast(0.5f) }) {
                Icon(Icons.Default.ZoomOut, "缩小")
            }
        }

        // Screenshot image
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(0.5f, 5f)
                        offsetX += pan.x
                        offsetY += pan.y
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            screenshot?.let { data ->
                val bitmap = remember(data) {
                    BitmapFactory.decodeByteArray(data, 0, data.size)?.asImageBitmap()
                }
                bitmap?.let {
                    Image(
                        bitmap = it,
                        contentDescription = "截图",
                        modifier = Modifier
                            .fillMaxWidth()
                            .graphicsLayer(
                                scaleX = scale,
                                scaleY = scale,
                                translationX = offsetX,
                                translationY = offsetY
                            ),
                        contentScale = ContentScale.Fit
                    )
                }
            } ?: run {
                if (!isLoading) {
                    Text("点击截图按钮捕获屏幕", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
