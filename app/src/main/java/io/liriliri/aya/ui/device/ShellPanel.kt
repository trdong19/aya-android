package io.liriliri.aya.ui.device

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.liriliri.aya.adb.AdbStream
import io.liriliri.aya.adb.DeviceManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ShellViewModel @Inject constructor(
    private val deviceManager: DeviceManager
) : ViewModel() {
    private val _outputLines = MutableStateFlow<List<String>>(emptyList())
    val outputLines: StateFlow<List<String>> = _outputLines.asStateFlow()

    private val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText.asStateFlow()

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private var shellStream: AdbStream? = null

    fun connect(deviceId: String) {
        viewModelScope.launch {
            try {
                val conn = deviceManager.getConnection(deviceId)
                val stream = conn.open("shell:")
                shellStream = stream
                _isConnected.value = true
                _outputLines.value = listOf("已连接到 shell")

                // Read output
                stream.output.collect { data ->
                    if (data.isEmpty()) {
                        _outputLines.value = _outputLines.value + "--- 连接已断开 ---"
                        _isConnected.value = false
                        return@collect
                    }
                    val text = String(data)
                    val newLines = _outputLines.value.toMutableList()
                    text.lines().forEach { line ->
                        if (newLines.size > 5000) newLines.removeFirst()
                        newLines.add(line)
                    }
                    _outputLines.value = newLines
                }
            } catch (e: Exception) {
                _outputLines.value = _outputLines.value + "错误: ${e.message}"
                _isConnected.value = false
            }
        }
    }

    fun updateInput(value: String) { _inputText.value = value }

    fun sendCommand() {
        val cmd = _inputText.value.trim()
        if (cmd.isBlank()) return
        _inputText.value = ""

        viewModelScope.launch {
            try {
                shellStream?.writeString("$cmd\n")
                _outputLines.value = _outputLines.value + "$ $cmd"
            } catch (e: Exception) {
                _outputLines.value = _outputLines.value + "发送失败: ${e.message}"
            }
        }
    }

    fun clear() {
        _outputLines.value = emptyList()
    }

    fun disconnect() {
        viewModelScope.launch {
            shellStream?.close()
            shellStream = null
            _isConnected.value = false
            _outputLines.value = _outputLines.value + "--- 已断开 ---"
        }
    }

    override fun onCleared() {
        shellStream?.close()
    }
}

@Composable
fun ShellPanel(
    deviceId: String,
    viewModel: ShellViewModel = hiltViewModel()
) {
    val outputLines by viewModel.outputLines.collectAsState()
    val inputText by viewModel.inputText.collectAsState()
    val isConnected by viewModel.isConnected.collectAsState()
    val listState = rememberLazyListState()

    LaunchedEffect(deviceId) {
        viewModel.connect(deviceId)
    }

    LaunchedEffect(outputLines.size) {
        if (outputLines.isNotEmpty()) {
            listState.animateScrollToItem(outputLines.size - 1)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Toolbar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Code,
                null,
                tint = if (isConnected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.error,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                if (isConnected) "已连接" else "未连接",
                style = MaterialTheme.typography.bodySmall,
                color = if (isConnected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.error
            )
            Spacer(modifier = Modifier.weight(1f))
            IconButton(onClick = { viewModel.clear() }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.CleaningServices, "清空", modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = { viewModel.disconnect() }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Refresh, "重连", modifier = Modifier.size(18.dp))
            }
        }

        // Output area
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color(0xFF1E1E1E))
                .padding(8.dp)
        ) {
            items(outputLines) { line ->
                Text(
                    line,
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = Color(0xFFD4D4D4)
                    )
                )
            }
        }

        // Input area
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "$",
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
            )
            Spacer(modifier = Modifier.width(8.dp))
            BasicTextField(
                value = inputText,
                onValueChange = { viewModel.updateInput(it) },
                textStyle = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                singleLine = true,
                modifier = Modifier.weight(1f),
                enabled = isConnected
            )
            IconButton(
                onClick = { viewModel.sendCommand() },
                enabled = isConnected && inputText.isNotBlank()
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, "发送", tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}
