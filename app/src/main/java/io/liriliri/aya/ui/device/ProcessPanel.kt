package io.liriliri.aya.ui.device

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullRefreshIndicator
import androidx.compose.material3.pulltorefresh.pullRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.liriliri.aya.adb.DeviceManager
import io.liriliri.aya.data.ProcessInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.roundToInt

@HiltViewModel
class ProcessViewModel @Inject constructor(
    private val deviceManager: DeviceManager
) : ViewModel() {
    private val _processes = MutableStateFlow<List<ProcessInfo>>(emptyList())
    val processes: StateFlow<List<ProcessInfo>> = _processes.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _filter = MutableStateFlow("")
    val filter: StateFlow<String> = _filter.asStateFlow()

    private val _autoRefresh = MutableStateFlow(true)
    val autoRefresh: StateFlow<Boolean> = _autoRefresh.asStateFlow()

    fun loadProcesses(deviceId: String) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                _processes.value = deviceManager.getProcesses(deviceId)
            } catch (_: Exception) {} finally {
                _isLoading.value = false
            }
        }
    }

    fun startAutoRefresh(deviceId: String) {
        viewModelScope.launch {
            while (_autoRefresh.value) {
                try {
                    _processes.value = deviceManager.getProcesses(deviceId)
                } catch (_: Exception) {}
                delay(5000)
            }
        }
    }

    fun toggleAutoRefresh() { _autoRefresh.value = !_autoRefresh.value }
    fun updateFilter(value: String) { _filter.value = value }

    fun stopProcess(deviceId: String, packageName: String) {
        viewModelScope.launch {
            try {
                deviceManager.stopPackage(deviceId, packageName)
                loadProcesses(deviceId)
            } catch (_: Exception) {}
        }
    }
}

@Composable
fun ProcessPanel(
    deviceId: String,
    viewModel: ProcessViewModel = hiltViewModel()
) {
    val processes by viewModel.processes.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val filter by viewModel.filter.collectAsState()
    val autoRefresh by viewModel.autoRefresh.collectAsState()

    LaunchedEffect(deviceId) {
        viewModel.loadProcesses(deviceId)
        viewModel.startAutoRefresh(deviceId)
    }

    val filtered = processes.filter {
        filter.isBlank() || it.name.contains(filter, ignoreCase = true)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = filter,
                onValueChange = { viewModel.updateFilter(it) },
                placeholder = { Text("搜索进程...") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
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
            Text(
                "${filtered.size} 个进程",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.weight(1f))
            IconToggleButton(checked = autoRefresh, onCheckedChange = { viewModel.toggleAutoRefresh() }) {
                Icon(
                    if (autoRefresh) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
            }
            IconButton(onClick = { viewModel.loadProcesses(deviceId) }) {
                Icon(Icons.Default.Refresh, "刷新")
            }
        }

        if (isLoading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

        // Process list with pull-to-refresh
        @OptIn(ExperimentalMaterial3Api::class)
        val pullRefreshState = rememberPullToRefreshState(isRefreshing = { isLoading })
        LaunchedEffect(pullRefreshState.isRefreshing) {
            if (pullRefreshState.isRefreshing) {
                viewModel.loadProcesses(deviceId)
            }
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pullRefresh(pullRefreshState)
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                items(filtered, key = { it.pid }) { process ->
                    ProcessItem(process = process)
                }
            }
            @OptIn(ExperimentalMaterial3Api::class)
            PullRefreshIndicator(
                refreshing = isLoading,
                state = pullRefreshState,
                modifier = Modifier.align(Alignment.TopCenter)
            )
        }
    }
}

@Composable
private fun ProcessItem(process: ProcessInfo) {
    val cpuColor = getCpuColor(process.cpuPercent)
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp)) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // CPU usage indicator dot
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(cpuColor)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    process.displayName.ifBlank { process.name },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    if (process.displayName.isNotBlank()) "${process.name} · PID: ${process.pid}"
                    else "PID: ${process.pid} · ${process.user}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    "${process.cpuPercent.roundToInt()}%",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    color = cpuColor
                )
                Text(
                    formatMem(process.memoryKb),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun getCpuColor(cpuPercent: Float): Color {
    return when {
        cpuPercent >= 80 -> Color(0xFFD32F2F)   // 红色
        cpuPercent >= 60 -> Color(0xFFF57C00)   // 橙色
        cpuPercent >= 40 -> Color(0xFFFFA726)   // 浅橙
        cpuPercent >= 20 -> Color(0xFF66BB6A)   // 绿色
        else -> Color(0xFF4CAF50)               // 深绿
    }
}

private fun formatMem(kb: Long): String {
    return if (kb > 1024 * 1024) "%.1f GB".format(kb / 1024.0 / 1024.0)
    else if (kb > 1024) "%.1f MB".format(kb / 1024.0)
    else "$kb KB"
}
