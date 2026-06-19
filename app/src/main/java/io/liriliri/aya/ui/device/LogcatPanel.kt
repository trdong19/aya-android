package io.liriliri.aya.ui.device

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.liriliri.aya.adb.DeviceManager
import io.liriliri.aya.data.LogcatEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LogcatViewModel @Inject constructor(
    private val deviceManager: DeviceManager
) : ViewModel() {
    private val _entries = MutableStateFlow<List<LogcatEntry>>(emptyList())
    val entries: StateFlow<List<LogcatEntry>> = _entries.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _filter = MutableStateFlow("")
    val filter: StateFlow<String> = _filter.asStateFlow()

    private val _minPriority = MutableStateFlow('V')
    val minPriority: StateFlow<Char> = _minPriority.asStateFlow()

    private var logcatStreamId: String? = null

    fun start(deviceId: String) {
        viewModelScope.launch {
            _isRunning.value = true
            logcatStreamId = deviceManager.openLogcat(deviceId) { entry ->
                val current = _entries.value.toMutableList()
                if (current.size > 5000) current.removeFirst()
                current.add(entry)
                _entries.value = current
            }
        }
    }

    fun stop() {
        viewModelScope.launch {
            logcatStreamId?.let { deviceManager.closeLogcat(it) }
            _isRunning.value = false
        }
    }

    fun clear() { _entries.value = emptyList() }
    fun updateFilter(value: String) { _filter.value = value }
    fun updateMinPriority(priority: Char) { _minPriority.value = priority }

    private val priorityOrder = mapOf('V' to 0, 'D' to 1, 'I' to 2, 'W' to 3, 'E' to 4, 'F' to 5)

    fun getFiltered(): List<LogcatEntry> {
        val minLevel = priorityOrder[_minPriority.value] ?: 0
        return _entries.value.filter { entry ->
            (priorityOrder[entry.priority] ?: 0) >= minLevel &&
                    (_filter.value.isBlank() ||
                            entry.tag.contains(_filter.value, ignoreCase = true) ||
                            entry.message.contains(_filter.value, ignoreCase = true) ||
                            entry.packageName.contains(_filter.value, ignoreCase = true))
        }
    }
}

@Composable
fun LogcatPanel(
    deviceId: String,
    viewModel: LogcatViewModel = hiltViewModel()
) {
    val isRunning by viewModel.isRunning.collectAsState()
    val filter by viewModel.filter.collectAsState()
    val minPriority by viewModel.minPriority.collectAsState()
    val entries = remember(isRunning, filter, minPriority) { viewModel.getFiltered() }
    val listState = rememberLazyListState()

    LaunchedEffect(deviceId) {
        viewModel.start(deviceId)
    }

    DisposableEffect(Unit) {
        onDispose { viewModel.stop() }
    }

    LaunchedEffect(entries.size) {
        if (entries.isNotEmpty()) {
            listState.animateScrollToItem(entries.size - 1)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Controls
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Priority filter
            val priorities = listOf('V', 'D', 'I', 'W', 'E')
            priorities.forEach { p ->
                FilterChip(
                    selected = minPriority == p,
                    onClick = { viewModel.updateMinPriority(p) },
                    label = { Text("$p", style = MaterialTheme.typography.bodySmall) },
                    modifier = Modifier.padding(end = 4.dp)
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = filter,
                onValueChange = { viewModel.updateFilter(it) },
                placeholder = { Text("过滤...") },
                leadingIcon = { Icon(Icons.Default.FilterList, null) },
                singleLine = true,
                modifier = Modifier.weight(1f).height(44.dp),
                shape = RoundedCornerShape(8.dp),
                textStyle = MaterialTheme.typography.bodySmall
            )
            Spacer(modifier = Modifier.width(4.dp))
            IconButton(onClick = { if (isRunning) viewModel.stop() else viewModel.start(deviceId) }, modifier = Modifier.size(36.dp)) {
                Icon(
                    if (isRunning) Icons.Default.Pause else Icons.Default.PlayArrow,
                    null,
                    modifier = Modifier.size(18.dp)
                )
            }
            IconButton(onClick = { viewModel.clear() }, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.CleaningServices, null, modifier = Modifier.size(18.dp))
            }
        }

        Text(
            "${entries.size} 条",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
        )

        // Log entries
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF1E1E1E))
                .padding(horizontal = 4.dp)
        ) {
            items(entries) { entry ->
                LogcatEntryRow(entry)
            }
        }
    }
}

@Composable
private fun LogcatEntryRow(entry: LogcatEntry) {
    val color = when (entry.priority) {
        'V' -> Color(0xFF888888)
        'D' -> Color(0xFF569CD6)
        'I' -> Color(0xFF4EC9B0)
        'W' -> Color(0xFFDCDCAA)
        'E' -> Color(0xFFC586C0)
        'F' -> Color(0xFFF44747)
        else -> Color(0xFFD4D4D4)
    }

    Row(modifier = Modifier.padding(vertical = 0.5.dp)) {
        Text(
            "${entry.priority}/",
            style = TextStyle(fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = color),
        )
        Text(
            "${entry.tag}: ",
            style = TextStyle(fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = color),
        )
        Text(
            entry.message,
            style = TextStyle(fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Color(0xFFD4D4D4)),
        )
    }
}
