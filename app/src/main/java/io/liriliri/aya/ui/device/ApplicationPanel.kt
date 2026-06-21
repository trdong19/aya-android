package io.liriliri.aya.ui.device

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.liriliri.aya.adb.DeviceManager
import io.liriliri.aya.data.PackageInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class ApplicationViewModel @Inject constructor(
    private val deviceManager: DeviceManager
) : ViewModel() {

    private val _packages = MutableStateFlow<List<PackageInfo>>(emptyList())
    val packages: StateFlow<List<PackageInfo>> = _packages.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _filter = MutableStateFlow("")
    val filter: StateFlow<String> = _filter.asStateFlow()

    private val _includeSystem = MutableStateFlow(false)
    val includeSystem: StateFlow<Boolean> = _includeSystem.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun loadPackages(deviceId: String) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val names = deviceManager.getPackages(deviceId, _includeSystem.value)
                val infos = deviceManager.getPackageInfos(deviceId, names)
                _packages.value = infos
            } catch (e: Exception) {
                _error.value = e.message
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun updateFilter(value: String) { _filter.value = value }
    fun toggleIncludeSystem(deviceId: String) {
        _includeSystem.value = !_includeSystem.value
        loadPackages(deviceId)
    }

    fun uninstallPackage(deviceId: String, packageName: String) {
        viewModelScope.launch {
            try {
                val result = deviceManager.uninstallPackage(deviceId, packageName)
                val ctx = io.liriliri.aya.AyaApplication.instance
                io.liriliri.aya.util.NotificationHelper.showSuccess(ctx, "卸载成功", packageName)
                loadPackages(deviceId)
            } catch (e: Exception) {
                _error.value = e.message
                io.liriliri.aya.util.NotificationHelper.showError(io.liriliri.aya.AyaApplication.instance, "卸载失败", e.message ?: "")
            }
        }
    }

    fun startPackage(deviceId: String, packageName: String) {
        viewModelScope.launch {
            try {
                deviceManager.startPackage(deviceId, packageName)
            } catch (e: Exception) {
                _error.value = e.message
            }
        }
    }

    fun stopPackage(deviceId: String, packageName: String) {
        viewModelScope.launch {
            try {
                deviceManager.stopPackage(deviceId, packageName)
            } catch (e: Exception) {
                _error.value = e.message
            }
        }
    }

    fun clearPackage(deviceId: String, packageName: String) {
        viewModelScope.launch {
            try {
                deviceManager.clearPackage(deviceId, packageName)
            } catch (e: Exception) {
                _error.value = e.message
            }
        }
    }

    fun disablePackage(deviceId: String, packageName: String) {
        viewModelScope.launch {
            try {
                deviceManager.disablePackage(deviceId, packageName)
                loadPackages(deviceId)
            } catch (e: Exception) {
                _error.value = e.message
            }
        }
    }

    fun enablePackage(deviceId: String, packageName: String) {
        viewModelScope.launch {
            try {
                deviceManager.enablePackage(deviceId, packageName)
                loadPackages(deviceId)
            } catch (e: Exception) {
                _error.value = e.message
            }
        }
    }

    fun installApk(deviceId: String, apkPath: String, onResult: (String) -> Unit) {
        viewModelScope.launch {
            try {
                val result = deviceManager.installPackage(deviceId, apkPath)
                val success = result.contains("Success", ignoreCase = true)
                onResult(if (success) "✅ $result" else "❌ $result")
                val ctx = io.liriliri.aya.AyaApplication.instance
                if (success) io.liriliri.aya.util.NotificationHelper.showSuccess(ctx, "安装成功", apkPath.substringAfterLast("/"))
                else io.liriliri.aya.util.NotificationHelper.showError(ctx, "安装失败", result)
                loadPackages(deviceId)
            } catch (e: Exception) {
                onResult("❌ ${e.message}")
                io.liriliri.aya.util.NotificationHelper.showError(io.liriliri.aya.AyaApplication.instance, "安装出错", e.message ?: "")
            }
        }
    }

    suspend fun findApkFiles(deviceId: String): List<String> {
        return deviceManager.findApkFiles(deviceId)
    }

    fun installFromUri(deviceId: String, uri: Uri, onResult: (String) -> Unit) {
        viewModelScope.launch {
            try {
                val context = io.liriliri.aya.AyaApplication.instance
                val tmpFile = java.io.File(context.cacheDir, "_aya_install.apk")

                // Copy URI content to temp file
                withContext(kotlinx.coroutines.Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        tmpFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    } ?: throw Exception("无法读取文件")
                }

                // Check connection - try to reconnect if needed
                try {
                    deviceManager.getConnection(deviceId)
                } catch (_: Exception) {
                    // Try to reconnect
                    val parts = deviceId.split(":")
                    if (parts.size == 2) {
                        deviceManager.connect(parts[0], parts[1].toIntOrNull() ?: 5555)
                    } else {
                        throw Exception("设备未连接，请重新连接")
                    }
                }

                // Push and install on remote device
                val result = deviceManager.pushAndInstall(deviceId, tmpFile.absolutePath)
                tmpFile.delete()

                onResult(if (result.contains("Success", ignoreCase = true)) "✅ $result" else "❌ $result")
            } catch (e: Exception) {
                onResult("❌ ${e.message}")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApplicationPanel(
    deviceId: String,
    viewModel: ApplicationViewModel = hiltViewModel()
) {
    val packages by viewModel.packages.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val filter by viewModel.filter.collectAsState()
    val includeSystem by viewModel.includeSystem.collectAsState()
    val error by viewModel.error.collectAsState()

    var isInstalling by remember { mutableStateOf(false) }
    var installStatus by remember { mutableStateOf<String?>(null) }

    // File picker for local APK files
    val apkPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            isInstalling = true
            installStatus = "正在安装..."
            viewModel.installFromUri(deviceId, uri) { result ->
                installStatus = result
                isInstalling = false
                viewModel.loadPackages(deviceId)
            }
        }
    }

    LaunchedEffect(deviceId) {
        viewModel.loadPackages(deviceId)
    }

    val filteredPackages = packages.filter {
        filter.isBlank() || it.packageName.contains(filter, ignoreCase = true) ||
                it.label.contains(filter, ignoreCase = true)
    }

    // Install status feedback
    installStatus?.let { status ->
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (status.startsWith("✅")) MaterialTheme.colorScheme.primaryContainer
                else if (status.startsWith("❌")) MaterialTheme.colorScheme.errorContainer
                else MaterialTheme.colorScheme.secondaryContainer
            )
        ) {
            Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (isInstalling) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(status, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                IconButton(onClick = { installStatus = null }, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                }
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Search and filter bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = filter,
                onValueChange = { viewModel.updateFilter(it) },
                placeholder = { Text("搜索应用...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (filter.isNotBlank()) {
                        IconButton(onClick = { viewModel.updateFilter("") }) {
                            Icon(Icons.Default.Clear, contentDescription = null)
                        }
                    }
                },
                singleLine = true,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp)
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilterChip(
                selected = includeSystem,
                onClick = { viewModel.toggleIncludeSystem(deviceId) },
                label = { Text("显示系统应用") }
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                "${filteredPackages.size} 个应用",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            IconButton(onClick = { apkPicker.launch("application/vnd.android.package-archive") }) {
                Icon(Icons.Default.InstallMobile, contentDescription = "安装 APK")
            }
            IconButton(onClick = { viewModel.loadPackages(deviceId) }) {
                Icon(Icons.Default.Refresh, contentDescription = "刷新")
            }
        }

        if (isLoading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        // Error
        error?.let {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
            ) {
                Text(it, modifier = Modifier.padding(8.dp), color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }

        // Package list with pull-to-refresh
        @OptIn(ExperimentalMaterial3Api::class)
        PullToRefreshBox(
            isRefreshing = isLoading,
            onRefresh = { viewModel.loadPackages(deviceId) },
            modifier = Modifier.fillMaxSize()
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(filteredPackages, key = { it.packageName }) { pkg ->
                    PackageItem(
                        pkg = pkg,
                        deviceId = deviceId,
                        viewModel = viewModel
                    )
                }
            }
        }
    }
}

@Composable
private fun PackageItem(
    pkg: PackageInfo,
    deviceId: String,
    viewModel: ApplicationViewModel
) {
    var showMenu by remember { mutableStateOf(false) }
    var showDetail by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Icon placeholder
            Surface(
                modifier = Modifier.size(40.dp),
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.Android,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    pkg.label.ifBlank { pkg.packageName },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    pkg.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row {
                    if (pkg.versionName.isNotBlank()) {
                        Text(
                            "v${pkg.versionName}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (pkg.isSystem) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "系统",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }
                    if (!pkg.isEnabled) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "已禁用",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "菜单")
                }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("应用信息") },
                        leadingIcon = { Icon(Icons.Default.Info, contentDescription = null) },
                        onClick = { showMenu = false; showDetail = true }
                    )
                    DropdownMenuItem(
                        text = { Text("启动") },
                        leadingIcon = { Icon(Icons.Default.PlayArrow, contentDescription = null) },
                        onClick = { showMenu = false; viewModel.startPackage(deviceId, pkg.packageName) }
                    )
                    DropdownMenuItem(
                        text = { Text("停止") },
                        leadingIcon = { Icon(Icons.Default.Stop, contentDescription = null) },
                        onClick = { showMenu = false; viewModel.stopPackage(deviceId, pkg.packageName) }
                    )
                    DropdownMenuItem(
                        text = { Text("清除数据") },
                        leadingIcon = { Icon(Icons.Default.CleaningServices, contentDescription = null) },
                        onClick = { showMenu = false; viewModel.clearPackage(deviceId, pkg.packageName) }
                    )
                    if (pkg.isEnabled) {
                        DropdownMenuItem(
                            text = { Text("禁用") },
                            leadingIcon = { Icon(Icons.Default.Block, contentDescription = null) },
                            onClick = { showMenu = false; viewModel.disablePackage(deviceId, pkg.packageName) }
                        )
                    } else {
                        DropdownMenuItem(
                            text = { Text("启用") },
                            leadingIcon = { Icon(Icons.Default.CheckCircle, contentDescription = null) },
                            onClick = { showMenu = false; viewModel.enablePackage(deviceId, pkg.packageName) }
                        )
                    }
                    if (!pkg.isSystem) {
                        DropdownMenuItem(
                            text = { Text("卸载", color = MaterialTheme.colorScheme.error) },
                            leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                            onClick = { showMenu = false; viewModel.uninstallPackage(deviceId, pkg.packageName) }
                        )
                    }
                }
            }
        }
    }

    // Package detail dialog
    if (showDetail) {
        AlertDialog(
            onDismissRequest = { showDetail = false },
            title = { Text(pkg.label.ifBlank { pkg.packageName }) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    DetailRow("包名", pkg.packageName)
                    DetailRow("版本", "${pkg.versionName} (${pkg.versionCode})")
                    DetailRow("Min SDK", "${pkg.minSdkVersion}")
                    DetailRow("Target SDK", "${pkg.targetSdkVersion}")
                    DetailRow("APK 路径", pkg.apkPath)
                    DetailRow("系统应用", if (pkg.isSystem) "是" else "否")
                    DetailRow("已启用", if (pkg.isEnabled) "是" else "否")
                }
            },
            confirmButton = {
                TextButton(onClick = { showDetail = false }) {
                    Text("关闭")
                }
            }
        )
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row {
        Text(
            "$label: ",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall
        )
    }
}
