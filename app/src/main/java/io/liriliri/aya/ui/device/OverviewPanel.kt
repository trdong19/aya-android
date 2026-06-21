package io.liriliri.aya.ui.device

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.liriliri.aya.data.DeviceOverview

@Composable
fun OverviewPanel(
    deviceId: String,
    overview: DeviceOverview?,
    isLoading: Boolean,
    viewModel: MainAppViewModel
) {
    @OptIn(ExperimentalMaterial3Api::class)
    PullToRefreshBox(
        isRefreshing = isLoading,
        onRefresh = { viewModel.loadOverview(deviceId) },
        modifier = Modifier.fillMaxSize()
    ) {
    if (isLoading && overview == null) {
        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
    } else if (overview == null) {
        Text("无法获取设备信息", modifier = Modifier.align(Alignment.Center))
    } else {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Device info
        item {
            SectionHeader("设备信息")
        }
        item {
            InfoCard {
                InfoItem(Icons.Default.PhoneAndroid, "型号", overview.model)
                InfoItem(Icons.Default.Business, "品牌", overview.brand)
                InfoItem(Icons.Default.Memory, "处理器", overview.processor)
                InfoItem(Icons.Default.DeveloperBoard, "核心数", "${overview.cores} 核")
                InfoItem(Icons.Default.Adb, "ABI", overview.abi)
                InfoItem(Icons.Default.Numbers, "序列号", overview.serial)
            }
        }

        // System info
        item {
            SectionHeader("系统信息")
        }
        item {
            InfoCard {
                InfoItem(Icons.Default.Android, "Android 版本", overview.androidVersion)
                InfoItem(Icons.Default.Tag, "API 等级", "${overview.apiLevel}")
                InfoItem(Icons.Default.Code, "内核版本", overview.kernelVersion)
                InfoItem(Icons.Default.Security, "Root 状态", if (overview.isRooted) "已 Root" else "未 Root")
            }
        }

        // Display info
        item {
            SectionHeader("显示")
        }
        item {
            InfoCard {
                InfoItem(Icons.Default.ScreenRotation, "分辨率", overview.resolution)
                InfoItem(Icons.Default.DensitySmall, "DPI", "${overview.density}")
            }
        }

        // Storage & Memory
        item {
            SectionHeader("存储与内存")
        }
        item {
            InfoCard {
                InfoItem(Icons.Default.Storage, "内存", formatSize(overview.memoryTotal))
                InfoItem(Icons.Default.SdStorage, "存储", "${formatSize(overview.storageUsed)} / ${formatSize(overview.storageTotal)}")
            }
        }

        // Network
        item {
            SectionHeader("网络")
        }
        item {
            InfoCard {
                InfoItem(Icons.Default.Wifi, "WiFi", overview.wifiSsid.ifBlank { "未连接" })
                InfoItem(Icons.Default.Lan, "IP 地址", overview.ip)
                InfoItem(Icons.Default.SettingsEthernet, "MAC 地址", overview.mac)
            }
        }

        // Battery
        item {
            SectionHeader("电池")
        }
        item {
            InfoCard {
                InfoItem(Icons.Default.BatteryFull, "电量", "${overview.batteryLevel}%")
                InfoItem(Icons.Default.Thermostat, "温度", "${overview.batteryTemperature}°C")
            }
        }

        item { Spacer(modifier = Modifier.height(16.dp)) }
    }
    } // else
    } // PullToRefreshBox
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(vertical = 4.dp)
    )
}

@Composable
private fun InfoCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            content()
        }
    }
}

@Composable
private fun InfoItem(icon: ImageVector, label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(80.dp)
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
    }
}

private fun formatSize(bytes: Long): String {
    if (bytes <= 0) return "N/A"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var size = bytes.toDouble()
    var unitIdx = 0
    while (size >= 1024 && unitIdx < units.size - 1) {
        size /= 1024
        unitIdx++
    }
    return "%.1f %s".format(size, units[unitIdx])
}
