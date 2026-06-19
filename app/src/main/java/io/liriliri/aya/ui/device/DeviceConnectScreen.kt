package io.liriliri.aya.ui.device

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.liriliri.aya.adb.DeviceManager
import io.liriliri.aya.adb.LocalDeviceManager
import io.liriliri.aya.data.ConnectionState
import io.liriliri.aya.data.Device
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DeviceConnectViewModel @Inject constructor(
    private val deviceManager: DeviceManager,
    private val localDeviceManager: LocalDeviceManager
) : ViewModel() {

    val connectionState = deviceManager.connectionState
    val connectedDevices = deviceManager.connectedDevices

    private val _host = MutableStateFlow("")
    val host: StateFlow<String> = _host.asStateFlow()

    private val _port = MutableStateFlow("5555")
    val port: StateFlow<String> = _port.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    val hasLocalAccess: Boolean get() = localDeviceManager.isAvailable

    fun updateHost(value: String) { _host.value = value }
    fun updatePort(value: String) { _port.value = value }
    fun clearError() { _error.value = null }

    fun connect(onSuccess: (String) -> Unit) {
        val h = _host.value.trim()
        val p = _port.value.trim().toIntOrNull() ?: 5555
        if (h.isBlank()) {
            _error.value = "请输入设备 IP 地址"
            return
        }

        viewModelScope.launch {
            try {
                _error.value = null
                val device = deviceManager.connect(h, p)
                onSuccess(device.id)
            } catch (e: Exception) {
                _error.value = e.message ?: "连接失败"
            }
        }
    }

    fun connectLocal(onSuccess: () -> Unit) {
        if (!localDeviceManager.isAvailable) {
            _error.value = "需要 Shizuku 或 Root 权限"
            return
        }
        if (localDeviceManager.hasShizuku && !localDeviceManager.checkShizukuPermission()) {
            localDeviceManager.requestShizukuPermission()
            _error.value = "请授予 Shizuku 权限后重试"
            return
        }
        onSuccess()
    }

    fun disconnect(deviceId: String) {
        deviceManager.disconnect(deviceId)
    }

    fun connectToDevice(device: Device, onSuccess: (String) -> Unit) {
        viewModelScope.launch {
            try {
                _error.value = null
                val d = deviceManager.connect(device.host, device.port)
                onSuccess(d.id)
            } catch (e: Exception) {
                _error.value = e.message ?: "连接失败"
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceConnectScreen(
    onDeviceConnected: (String) -> Unit,
    viewModel: DeviceConnectViewModel = hiltViewModel()
) {
    val host by viewModel.host.collectAsState()
    val port by viewModel.port.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val connectedDevices by viewModel.connectedDevices.collectAsState()
    val error by viewModel.error.collectAsState()

    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current

    val isConnecting = connectionState is ConnectionState.Connecting

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "AYA",
                        fontWeight = FontWeight.Bold,
                        fontSize = 22.sp
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(32.dp))

            // Logo / Icon
            Icon(
                imageVector = Icons.Default.PhoneAndroid,
                contentDescription = null,
                modifier = Modifier.size(72.dp),
                tint = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                "连接设备",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )

            Text(
                "通过 WiFi ADB 连接远程 Android 设备",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(32.dp))

            // IP Address input
            OutlinedTextField(
                value = host,
                onValueChange = { viewModel.updateHost(it) },
                label = { Text("设备 IP 地址") },
                placeholder = { Text("例如: 192.168.1.100") },
                leadingIcon = { Icon(Icons.Default.Wifi, contentDescription = null) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Next
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
                enabled = !isConnecting
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Port input
            OutlinedTextField(
                value = port,
                onValueChange = { viewModel.updatePort(it) },
                label = { Text("端口") },
                placeholder = { Text("5555") },
                leadingIcon = { Icon(Icons.Default.Tag, contentDescription = null) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(
                    onDone = {
                        focusManager.clearFocus()
                        viewModel.connect(onDeviceConnected)
                    }
                ),
                modifier = Modifier.fillMaxWidth(),
                enabled = !isConnecting
            )

            // Error message
            AnimatedVisibility(visible = error != null) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Error,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            error ?: "",
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = { viewModel.clearError() },
                            modifier = Modifier.size(20.dp)
                        ) {
                            Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Connect button
            Button(
                onClick = {
                    focusManager.clearFocus()
                    viewModel.connect(onDeviceConnected)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                enabled = !isConnecting && host.isNotBlank(),
                shape = RoundedCornerShape(12.dp)
            ) {
                if (isConnecting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("连接中...")
                } else {
                    Icon(Icons.Default.Link, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("连接远程设备", fontSize = 16.sp)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Local device button
            OutlinedButton(
                onClick = {
                    focusManager.clearFocus()
                    viewModel.connectLocal { onDeviceConnected("local") }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.PhoneAndroid, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("控制本机", fontSize = 16.sp)
                if (viewModel.hasLocalAccess) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Previously connected devices
            if (connectedDevices.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "已连接的设备",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    items(connectedDevices) { device ->
                        DeviceCard(
                            device = device,
                            onClick = { viewModel.connectToDevice(device, onDeviceConnected) },
                            onDisconnect = { viewModel.disconnect(device.id) }
                        )
                    }
                }
            } else {
                // Tips - Method 1: USB ADB
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "方法一：USB 转 WiFi ADB（推荐）",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        TipItem("1. 用 USB 线连接目标设备到电脑")
                        TipItem("2. 电脑执行: adb tcpip 5555")
                        TipItem("3. 拔掉 USB 线")
                        TipItem("4. 输入设备 IP 地址，端口填 5555")
                        TipItem("5. 点击「连接远程设备」")
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Tips - Method 2: Wireless debugging
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "方法二：无线调试（Android 11+）",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        TipItem("1. 开启「开发者选项」→「无线调试」")
                        TipItem("2. 先用电脑配对: adb pair <IP>:<配对端口> <配对码>")
                        TipItem("3. 配对成功后，输入无线调试显示的 IP 和端口")
                        TipItem("4. 点击「连接远程设备」")
                        Text(
                            "⚠️ 无线调试每次重启需重新配对",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TipItem(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 2.dp)
    )
}

@Composable
private fun DeviceCard(
    device: Device,
    onClick: () -> Unit,
    onDisconnect: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.PhoneAndroid,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(40.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    device.name.ifBlank { device.model.ifBlank { device.id } },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    device.id,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (device.androidVersion.isNotBlank()) {
                    Text(
                        "Android ${device.androidVersion} (API ${device.apiLevel})",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            IconButton(onClick = onDisconnect) {
                Icon(
                    Icons.Default.LinkOff,
                    contentDescription = "断开",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
