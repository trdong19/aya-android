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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    // Device history
    data class DeviceHistoryEntry(val host: String, val port: Int, val name: String, val lastUsed: Long)
    private val _history = MutableStateFlow<List<DeviceHistoryEntry>>(emptyList())
    val history: StateFlow<List<DeviceHistoryEntry>> = _history.asStateFlow()

    // LAN scan
    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()
    private val _lanDevices = MutableStateFlow<List<String>>(emptyList())
    val lanDevices: StateFlow<List<String>> = _lanDevices.asStateFlow()


    val hasLocalAccess: Boolean get() = localDeviceManager.isAvailable

    init {
        loadHistory()
    }

    private fun getPrefs(): android.content.SharedPreferences? {
        return try {
            val ctx = io.liriliri.aya.AyaApplication.instance
            ctx.getSharedPreferences("aya_devices", android.content.Context.MODE_PRIVATE)
        } catch (_: Exception) { null }
    }

    private fun loadHistory() {
        val p = getPrefs() ?: return
        val set = p.getStringSet("history", emptySet()) ?: emptySet()
        _history.value = set.mapNotNull { entry ->
            val parts = entry.split("|")
            if (parts.size >= 3) {
                DeviceHistoryEntry(parts[0], parts[1].toIntOrNull() ?: 5555, parts[2], parts.getOrNull(3)?.toLongOrNull() ?: 0)
            } else null
        }.sortedByDescending { it.lastUsed }
    }

    private fun saveToHistory(host: String, port: Int, name: String = "") {
        val p = getPrefs() ?: return
        val current = _history.value.toMutableList()
        current.removeAll { it.host == host && it.port == port }
        current.add(0, DeviceHistoryEntry(host, port, name, System.currentTimeMillis()))
        val set = current.take(20).map { "${it.host}|${it.port}|${it.name}|${it.lastUsed}" }.toSet()
        p.edit().putStringSet("history", set).apply()
        _history.value = current.take(20)
    }

    fun removeHistory(host: String, port: Int) {
        val p = getPrefs() ?: return
        val current = _history.value.toMutableList()
        current.removeAll { it.host == host && it.port == port }
        val set = current.map { "${it.host}|${it.port}|${it.name}|${it.lastUsed}" }.toSet()
        p.edit().putStringSet("history", set).apply()
        _history.value = current
    }

    fun scanLan() {
        viewModelScope.launch {
            _scanning.value = true
            _lanDevices.value = emptyList()
            try {
                val found = withContext(Dispatchers.IO) {
                    val localIp = java.net.NetworkInterface.getNetworkInterfaces()?.toList()
                        ?.flatMap { it.inetAddresses.toList() }
                        ?.firstOrNull { !it.isLoopbackAddress && it is java.net.Inet4Address }
                        ?.hostAddress ?: ""

                    if (localIp.isBlank()) return@withContext emptyList<String>()

                    val prefix = localIp.substringBeforeLast(".")
                    val results = mutableListOf<String>()

                    for (batch in (1..254).chunked(50)) {
                        val jobs = batch.map { i ->
                            async(Dispatchers.IO) {
                                val ip = "$prefix.$i"
                                try {
                                    val socket = java.net.Socket()
                                    socket.connect(java.net.InetSocketAddress(ip, 5555), 300)
                                    socket.close()
                                    ip
                                } catch (_: Exception) { null }
                            }
                        }
                        jobs.forEach { job ->
                            val ip = job.await()
                            if (ip != null) results.add(ip)
                        }
                    }
                    results
                }
                _lanDevices.value = found
            } catch (_: Exception) {}
            _scanning.value = false
        }
    }

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
                saveToHistory(h, p, device.model)
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
    val history by viewModel.history.collectAsState()
    val scanning by viewModel.scanning.collectAsState()
    val lanDevices by viewModel.lanDevices.collectAsState()

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
                    Text("连接中...", fontSize = 16.sp)
                } else {
                    Icon(Icons.Default.Link, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("连接远程设备", fontSize = 16.sp)
                }
            }

            // Hint when connecting
            if (isConnecting) {
                Text(
                    "请在远程设备上点击「始终允许」授权 ADB 调试",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp)
                )
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

            Spacer(modifier = Modifier.height(16.dp))

            // LAN scan button - always visible
            OutlinedButton(
                onClick = { viewModel.scanLan() },
                enabled = !scanning,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                if (scanning) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("扫描中...")
                } else {
                    Icon(Icons.Default.WifiFind, null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("局域网扫描")
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Content area with scrolling
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // History section
                if (history.isNotEmpty()) {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.History, null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("历史记录", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        }
                    }
                    items(history) { entry ->
                        Card(
                            onClick = {
                                viewModel.updateHost(entry.host)
                                viewModel.updatePort(entry.port.toString())
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.PhoneAndroid, null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(entry.name.ifBlank { entry.host }, fontWeight = FontWeight.Medium)
                                    Text("${entry.host}:${entry.port}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                IconButton(onClick = { viewModel.removeHistory(entry.host, entry.port) }, modifier = Modifier.size(32.dp)) {
                                    Icon(Icons.Default.Close, null, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }

                // LAN scan results
                if (lanDevices.isNotEmpty()) {
                    item {
                        Text("局域网设备", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    }
                    items(lanDevices) { ip ->
                        Card(
                            onClick = {
                                viewModel.updateHost(ip)
                                viewModel.updatePort("5555")
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Wifi, null, tint = MaterialTheme.colorScheme.secondary)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(ip)
                            }
                        }
                    }
                }

                // Tips when no history
                if (history.isEmpty() && lanDevices.isEmpty()) {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text("USB 转 WiFi ADB", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                Spacer(modifier = Modifier.height(8.dp))
                                TipItem("1. USB 连接目标设备到电脑")
                                TipItem("2. 电脑执行: adb tcpip 5555")
                                TipItem("3. 拔掉 USB，输入设备 IP")
                            }
                        }
                    }
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text("无线调试 (Android 11+)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                Spacer(modifier = Modifier.height(8.dp))
                                TipItem("1. 开发者选项 → 无线调试")
                                TipItem("2. 电脑配对: adb pair <IP>:<端口> <码>")
                                TipItem("3. 输入显示的 IP 和端口连接")
                            }
                        }
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
