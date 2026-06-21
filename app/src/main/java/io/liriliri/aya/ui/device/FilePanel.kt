package io.liriliri.aya.ui.device

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.liriliri.aya.adb.DeviceManager
import io.liriliri.aya.data.DeviceFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

@HiltViewModel
class FileViewModel @Inject constructor(
    private val deviceManager: DeviceManager
) : ViewModel() {

    private val _files = MutableStateFlow<List<DeviceFile>>(emptyList())
    val files: StateFlow<List<DeviceFile>> = _files.asStateFlow()

    private val _currentPath = MutableStateFlow("/sdcard")
    val currentPath: StateFlow<String> = _currentPath.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _installResult = MutableStateFlow<String?>(null)
    val installResult: StateFlow<String?> = _installResult.asStateFlow()

    private val pathHistory = mutableListOf<String>()
    private var initialized = false

    /**
     * Initialize file browsing - try /sdcard first, fall back to /storage/emulated/0 or /
     */
    fun initialize(deviceId: String) {
        if (initialized) return
        initialized = true
        viewModelScope.launch {
            _isLoading.value = true
            try {
                // Resolve /sdcard real path (might be symlink)
                val realPath = try {
                    deviceManager.executeCommand(deviceId, "readlink -f /sdcard 2>/dev/null || realpath /sdcard 2>/dev/null").trim()
                } catch (_: Exception) { "" }

                val pathsToTry = listOfNotNull(
                    realPath.ifBlank { null },
                    "/sdcard",
                    "/storage/emulated/0",
                    "/storage/self/primary",
                    "/"
                )

                var files = emptyList<DeviceFile>()
                var startPath = "/"

                for (path in pathsToTry) {
                    val result = deviceManager.readDir(deviceId, path)
                    if (result.isNotEmpty()) {
                        files = result
                        startPath = path
                        break
                    }
                }

                _currentPath.value = startPath
                pathHistory.clear()
                pathHistory.add(startPath)
                _files.value = files
            } catch (_: Exception) {
                _currentPath.value = "/"
                pathHistory.clear()
                pathHistory.add("/")
                _files.value = emptyList()
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun navigateTo(deviceId: String, path: String, addToHistory: Boolean = false) {
        val safePath = path.ifBlank { "/" }.let { if (it == "..") "/" else it }
        viewModelScope.launch {
            _isLoading.value = true
            try {
                _currentPath.value = safePath
                _files.value = deviceManager.readDir(deviceId, safePath)
                if (addToHistory) {
                    if (pathHistory.lastOrNull() != safePath) {
                        pathHistory.add(safePath)
                    }
                }
            } catch (e: Exception) {
                _files.value = emptyList()
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun navigateUp(deviceId: String) {
        val current = _currentPath.value
        if (current == "/" || current.isBlank()) return
        val parent = current.substringBeforeLast("/", "/").ifBlank { "/" }
        if (parent == current) return
        navigateTo(deviceId, parent, addToHistory = true)
    }

    fun goBack(deviceId: String) {
        if (pathHistory.size > 1) {
            pathHistory.removeLast()
            val target = pathHistory.last()
            _currentPath.value = target
            navigateTo(deviceId, target, addToHistory = false)
        }
    }

    fun openDirectory(deviceId: String, path: String) {
        navigateTo(deviceId, path, addToHistory = true)
    }

    fun deleteFile(deviceId: String, path: String) {
        viewModelScope.launch {
            try {
                deviceManager.deleteFile(deviceId, path)
                navigateTo(deviceId, _currentPath.value)
            } catch (_: Exception) {}
        }
    }

    fun createDir(deviceId: String, name: String) {
        viewModelScope.launch {
            try {
                val path = "${_currentPath.value}/$name"
                deviceManager.createDir(deviceId, path)
                navigateTo(deviceId, _currentPath.value)
            } catch (_: Exception) {}
        }
    }

    fun renameFile(deviceId: String, oldPath: String, newName: String) {
        viewModelScope.launch {
            try {
                val dir = oldPath.substringBeforeLast("/")
                deviceManager.moveFile(deviceId, oldPath, "$dir/$newName")
                navigateTo(deviceId, _currentPath.value)
            } catch (_: Exception) {}
        }
    }

    fun installApk(deviceId: String, apkPath: String) {
        viewModelScope.launch {
            _installResult.value = "正在安装 ${apkPath.substringAfterLast("/")}..."
            try {
                val result = deviceManager.installPackage(deviceId, apkPath)
                _installResult.value = if (result.contains("Success", ignoreCase = true)) {
                    "✅ 安装成功: ${apkPath.substringAfterLast("/")}"
                } else {
                    "❌ 安装失败: $result"
                }
            } catch (e: Exception) {
                _installResult.value = "❌ 安装出错: ${e.message}"
            }
        }
    }

    fun clearInstallResult() { _installResult.value = null }
}

@Composable
fun FilePanel(
    deviceId: String,
    viewModel: FileViewModel = hiltViewModel()
) {
    val files by viewModel.files.collectAsState()
    val currentPath by viewModel.currentPath.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val installResult by viewModel.installResult.collectAsState()

    LaunchedEffect(deviceId) {
        viewModel.initialize(deviceId)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Path bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { viewModel.goBack(deviceId) }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            IconButton(onClick = { viewModel.navigateUp(deviceId) }) {
                Icon(Icons.Default.ArrowUpward, contentDescription = "上级")
            }
            Text(
                currentPath,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            IconButton(onClick = { viewModel.navigateTo(deviceId, currentPath) }) {
                Icon(Icons.Default.Refresh, contentDescription = "刷新")
            }
        }

        if (isLoading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        // Install result feedback
        installResult?.let { result ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (result.contains("✅")) MaterialTheme.colorScheme.primaryContainer
                    else if (result.contains("❌")) MaterialTheme.colorScheme.errorContainer
                    else MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Row(
                    modifier = Modifier.padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(result, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    IconButton(onClick = { viewModel.clearInstallResult() }, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "关闭", modifier = Modifier.size(16.dp))
                    }
                }
            }
        }

        // File list with pull-to-refresh
        @OptIn(ExperimentalMaterial3Api::class)
        PullToRefreshBox(
            isRefreshing = isLoading,
            onRefresh = { viewModel.navigateTo(deviceId, currentPath) },
            modifier = Modifier.fillMaxSize()
        ) {
            val sortedFiles = files.distinctBy { it.path }.sortedWith(compareByDescending<DeviceFile> { it.isDirectory }.thenBy { it.name })
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                items(sortedFiles, key = { "${it.path}_${it.name}" }) { file ->
                    FileItem(
                        file = file,
                        onClick = {
                            if (file.isDirectory) {
                                viewModel.openDirectory(deviceId, file.path)
                            }
                        },
                        onDelete = { viewModel.deleteFile(deviceId, file.path) },
                        onInstall = if (file.name.endsWith(".apk")) {
                            { viewModel.installApk(deviceId, file.path) }
                        } else null
                    )
                }
            }
        }
    }
}

@Composable
private fun FileItem(
    file: DeviceFile,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onInstall: (() -> Unit)? = null
) {
    var showMenu by remember { mutableStateOf(false) }

    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (file.isDirectory) Icons.Default.Folder else getFileIcon(file.name),
                contentDescription = null,
                tint = if (file.isDirectory) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    file.name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row {
                    if (!file.isDirectory) {
                        Text(
                            formatFileSize(file.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(
                        file.permissions,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = null)
                }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    if (onInstall != null) {
                        DropdownMenuItem(
                            text = { Text("安装此 APK") },
                            leadingIcon = { Icon(Icons.Default.InstallMobile, null, tint = MaterialTheme.colorScheme.primary) },
                            onClick = { showMenu = false; onInstall() }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                        leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
                        onClick = { showMenu = false; onDelete() }
                    )
                }
            }
        }
    }
}

private fun getFileIcon(name: String) = when {
    name.endsWith(".apk") -> Icons.Default.Android
    name.endsWith(".jpg") || name.endsWith(".png") || name.endsWith(".webp") -> Icons.Default.Image
    name.endsWith(".mp4") || name.endsWith(".mkv") -> Icons.Default.VideoFile
    name.endsWith(".mp3") || name.endsWith(".ogg") -> Icons.Default.AudioFile
    name.endsWith(".txt") || name.endsWith(".log") -> Icons.Default.TextSnippet
    name.endsWith(".zip") || name.endsWith(".tar") -> Icons.Default.FolderZip
    else -> Icons.Default.InsertDriveFile
}

private fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    var size = bytes.toDouble()
    var idx = 0
    while (size >= 1024 && idx < units.size - 1) {
        size /= 1024
        idx++
    }
    return "%.1f %s".format(size, units[idx])
}
