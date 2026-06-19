package io.liriliri.aya.ui.device

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.liriliri.aya.adb.DeviceManager
import io.liriliri.aya.data.DeviceOverview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

// Tabs for the main screen
enum class MainTab(val label: String, val icon: ImageVector) {
    Overview("概览", Icons.Default.PhoneAndroid),
    Application("应用", Icons.Default.Apps),
    File("文件", Icons.Default.Folder),
    Process("进程", Icons.Default.List),
    Performance("性能", Icons.Default.Speed),
    Shell("终端", Icons.Default.Code),
    Logcat("日志", Icons.Default.BugReport),
    Layout("布局", Icons.Default.ViewQuilt),
    Screenshot("截图", Icons.Default.CameraAlt),
    Webview("Webview", Icons.Default.Web),
    Settings("设置", Icons.Default.Settings)
}

@HiltViewModel
class MainAppViewModel @Inject constructor(
    val deviceManager: DeviceManager
) : ViewModel() {
    var currentTab = mutableStateOf(MainTab.Overview)

    private val _overview = MutableStateFlow<DeviceOverview?>(null)
    val overview: StateFlow<DeviceOverview?> = _overview.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    fun loadOverview(deviceId: String) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                _overview.value = deviceManager.getOverview(deviceId)
            } catch (e: Exception) {
                // Handle error
            } finally {
                _isLoading.value = false
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainAppScreen(
    deviceId: String,
    onDisconnect: () -> Unit,
    viewModel: MainAppViewModel = hiltViewModel()
) {
    val currentTab by viewModel.currentTab
    val overview by viewModel.overview.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()

    LaunchedEffect(deviceId) {
        viewModel.loadOverview(deviceId)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            overview?.model ?: deviceId,
                            style = MaterialTheme.typography.titleMedium
                        )
                        if (overview != null) {
                            Text(
                                "Android ${overview!!.androidVersion} · ${overview!!.ip}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = {
                        viewModel.deviceManager.disconnect(deviceId)
                        onDisconnect()
                    }) {
                        Icon(Icons.Default.LinkOff, contentDescription = "断开")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            NavigationBar {
                MainTab.entries.take(6).forEach { tab ->
                    NavigationBarItem(
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) },
                        selected = currentTab == tab,
                        onClick = { viewModel.currentTab.value = tab }
                    )
                }
                // More menu
                var showMore by remember { mutableStateOf(false) }
                NavigationBarItem(
                    icon = { Icon(Icons.Default.MoreHoriz, contentDescription = "更多") },
                    label = { Text("更多") },
                    selected = currentTab !in MainTab.entries.take(6),
                    onClick = { showMore = true }
                )
                DropdownMenu(
                    expanded = showMore,
                    onDismissRequest = { showMore = false }
                ) {
                    MainTab.entries.drop(6).forEach { tab ->
                        DropdownMenuItem(
                            text = { Text(tab.label) },
                            leadingIcon = { Icon(tab.icon, contentDescription = null) },
                            onClick = {
                                viewModel.currentTab.value = tab
                                showMore = false
                            }
                        )
                    }
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when (currentTab) {
                MainTab.Overview -> OverviewPanel(deviceId, overview, isLoading, viewModel)
                MainTab.Application -> ApplicationPanel(deviceId)
                MainTab.File -> FilePanel(deviceId)
                MainTab.Process -> ProcessPanel(deviceId)
                MainTab.Performance -> PerformancePanel(deviceId)
                MainTab.Shell -> ShellPanel(deviceId)
                MainTab.Logcat -> LogcatPanel(deviceId)
                MainTab.Layout -> LayoutPanel(deviceId)
                MainTab.Screenshot -> ScreenshotPanel(deviceId)
                MainTab.Webview -> WebviewPanel(deviceId)
                MainTab.Settings -> SettingsPanel()
            }
        }
    }
}
