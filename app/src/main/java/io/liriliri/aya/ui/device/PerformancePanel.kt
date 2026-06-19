package io.liriliri.aya.ui.device

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.liriliri.aya.adb.DeviceManager
import io.liriliri.aya.data.PerformanceSnapshot
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PerformanceViewModel @Inject constructor(
    private val deviceManager: DeviceManager
) : ViewModel() {
    private val _snapshot = MutableStateFlow<PerformanceSnapshot?>(null)
    val snapshot: StateFlow<PerformanceSnapshot?> = _snapshot.asStateFlow()

    private val cpuHistory = mutableListOf<List<Float>>()
    private val memHistory = mutableListOf<Long>()

    private val _cpuHistoryState = MutableStateFlow<List<List<Float>>>(emptyList())
    val cpuHistoryState: StateFlow<List<List<Float>>> = _cpuHistoryState.asStateFlow()

    private val _memHistoryState = MutableStateFlow<List<Long>>(emptyList())
    val memHistoryState: StateFlow<List<Long>> = _memHistoryState.asStateFlow()

    fun startMonitoring(deviceId: String) {
        viewModelScope.launch {
            while (true) {
                try {
                    val perf = deviceManager.getPerformance(deviceId)
                    val temp = deviceManager.getCpuTemperature(deviceId)
                    val top = deviceManager.getTopPackage(deviceId)

                    _snapshot.value = perf.copy(
                        cpuTemperature = temp,
                        topPackage = top.first
                    )

                    cpuHistory.add(perf.cpuLoads)
                    if (cpuHistory.size > 60) cpuHistory.removeFirst()
                    _cpuHistoryState.value = cpuHistory.toList()

                    memHistory.add(perf.memoryUsed)
                    if (memHistory.size > 60) memHistory.removeFirst()
                    _memHistoryState.value = memHistory.toList()
                } catch (_: Exception) {}
                delay(2000)
            }
        }
    }
}

@Composable
fun PerformancePanel(
    deviceId: String,
    viewModel: PerformanceViewModel = hiltViewModel()
) {
    val snapshot by viewModel.snapshot.collectAsState()
    val cpuHistory by viewModel.cpuHistoryState.collectAsState()
    val memHistory by viewModel.memHistoryState.collectAsState()

    LaunchedEffect(deviceId) {
        viewModel.startMonitoring(deviceId)
    }

    val snap = snapshot

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (snap == null) {
            Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Column
        }

        // CPU
        SectionCard("CPU", Icons.Default.Speed) {
            val avgLoad = snap.cpuLoads.average().toFloat()
            GaugeBar("总负载", avgLoad, MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(8.dp))
            snap.cpuLoads.forEachIndexed { idx, load ->
                GaugeBar("核心 $idx", load, cpuCoreColor(idx))
            }
            if (snap.cpuTemperature > 0) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "温度: ${"%.1f".format(snap.cpuTemperature)}°C",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // CPU history chart
            if (cpuHistory.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                MiniLineChart(
                    data = cpuHistory.map { it.average().toFloat() },
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth().height(60.dp)
                )
            }
        }

        // Memory
        SectionCard("内存", Icons.Default.Memory) {
            val usedMB = snap.memoryUsed / 1024 / 1024
            val totalMB = snap.memoryTotal / 1024 / 1024
            val percent = if (snap.memoryTotal > 0) snap.memoryUsed * 100f / snap.memoryTotal else 0f
            GaugeBar("使用量", percent, MaterialTheme.colorScheme.tertiary)
            Text(
                "${usedMB} MB / ${totalMB} MB",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (memHistory.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                MiniLineChart(
                    data = memHistory.map { (it / 1024 / 1024).toFloat() },
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.fillMaxWidth().height(60.dp)
                )
            }
        }

        // Battery
        SectionCard("电池", Icons.Default.BatteryFull) {
            GaugeBar("电量", snap.batteryLevel.toFloat(), batteryColor(snap.batteryLevel))
            Text(
                "${snap.batteryLevel}%",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (snap.batteryTemperature > 0) {
                Text(
                    "温度: ${"%.1f".format(snap.batteryTemperature)}°C",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (snap.batteryVoltage > 0) {
                Text(
                    "电压: ${"%.2f".format(snap.batteryVoltage)}V",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Top package
        if (snap.topPackage.isNotBlank()) {
            SectionCard("前台应用", Icons.Default.Apps) {
                Text(snap.topPackage, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun GaugeBar(label: String, percent: Float, color: Color) {
    Column(modifier = Modifier.padding(vertical = 2.dp)) {
        LinearProgressIndicator(
            progress = { (percent / 100f).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(8.dp),
            color = color,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
    }
}

@Composable
private fun MiniLineChart(
    data: List<Float>,
    color: Color,
    modifier: Modifier = Modifier
) {
    if (data.isEmpty()) return
    val max = data.max().coerceAtLeast(1f)

    Canvas(modifier = modifier) {
        val stepX = size.width / (data.size - 1).coerceAtLeast(1)
        val path = Path()
        data.forEachIndexed { idx, value ->
            val x = idx * stepX
            val y = size.height - (value / max * size.height)
            if (idx == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color, style = Stroke(width = 2.dp.toPx()))
    }
}

private fun cpuCoreColor(index: Int): Color {
    val colors = listOf(
        Color(0xFF1976D2), Color(0xFF388E3C), Color(0xFFF57C00),
        Color(0xFF7B1FA2), Color(0xFFC62828), Color(0xFF00838F),
        Color(0xFF5D4037), Color(0xFF455A64)
    )
    return colors[index % colors.size]
}

private fun batteryColor(level: Int): Color = when {
    level > 60 -> Color(0xFF388E3C)
    level > 20 -> Color(0xFFF57C00)
    else -> Color(0xFFC62828)
}
