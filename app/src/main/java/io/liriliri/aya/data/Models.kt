package io.liriliri.aya.data

import kotlinx.serialization.Serializable

/**
 * Represents a connected device.
 */
@Serializable
data class Device(
    val id: String,           // IP:port or serial
    val host: String,
    val port: Int = 5555,
    val name: String = "",
    val model: String = "",
    val androidVersion: String = "",
    val apiLevel: Int = 0,
    val isConnected: Boolean = false,
    val lastConnected: Long = 0
)

/**
 * Device overview information.
 */
data class DeviceOverview(
    val name: String = "",
    val brand: String = "",
    val model: String = "",
    val serial: String = "",
    val androidVersion: String = "",
    val apiLevel: Int = 0,
    val kernelVersion: String = "",
    val processor: String = "",
    val cores: Int = 0,
    val abi: String = "",
    val storageTotal: Long = 0,
    val storageUsed: Long = 0,
    val memoryTotal: Long = 0,
    val resolution: String = "",
    val density: Int = 0,
    val dpi: Int = 0,
    val ip: String = "",
    val mac: String = "",
    val wifiSsid: String = "",
    val batteryLevel: Int = 0,
    val batteryTemperature: Float = 0f,
    val isRooted: Boolean = false
)

/**
 * Installed package information.
 */
data class PackageInfo(
    val packageName: String,
    val label: String = "",
    val versionName: String = "",
    val versionCode: Long = 0,
    val minSdkVersion: Int = 0,
    val targetSdkVersion: Int = 0,
    val firstInstallTime: Long = 0,
    val lastUpdateTime: Long = 0,
    val apkPath: String = "",
    val apkSize: Long = 0,
    val appSize: Long = 0,
    val dataSize: Long = 0,
    val cacheSize: Long = 0,
    val iconBase64: String = "",
    val signature: String = "",
    val isSystem: Boolean = false,
    val isEnabled: Boolean = true
)

/**
 * File/directory entry on the device.
 */
data class DeviceFile(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long = 0,
    val lastModified: Long = 0,
    val permissions: String = ""
)

/**
 * Running process on the device.
 */
data class ProcessInfo(
    val pid: Int,
    val name: String,
    val user: String = "",
    val cpuPercent: Float = 0f,
    val memoryKb: Long = 0,
    val cpuTime: String = "",
    val displayName: String = ""
)

/**
 * Performance metrics snapshot.
 */
data class PerformanceSnapshot(
    val cpuLoads: List<Float> = emptyList(),
    val cpuSpeeds: List<Long> = emptyList(),
    val cpuTemperature: Float = 0f,
    val memoryUsed: Long = 0,
    val memoryTotal: Long = 0,
    val batteryLevel: Int = 0,
    val batteryVoltage: Float = 0f,
    val batteryTemperature: Float = 0f,
    val fps: Float = 0f,
    val topPackage: String = ""
)

/**
 * Logcat entry.
 */
data class LogcatEntry(
    val pid: Int,
    val tid: Int,
    val priority: Char,  // V, D, I, W, E, F
    val tag: String,
    val message: String,
    val timestamp: Long = 0,
    val packageName: String = ""
)

/**
 * Webview instance.
 */
data class WebviewInfo(
    val title: String,
    val url: String,
    val webSocketDebuggerUrl: String = "",
    val faviconUrl: String = ""
)

/**
 * Port forwarding entry.
 */
data class PortForward(
    val local: String,
    val remote: String,
    val isReverse: Boolean = false
)

/**
 * Connection state.
 */
sealed class ConnectionState {
    data object Disconnected : ConnectionState()
    data object Connecting : ConnectionState()
    data class Connected(val device: Device) : ConnectionState()
    data class Error(val message: String) : ConnectionState()
}

/**
 * Transfer state for file operations.
 */
data class FileTransfer(
    val id: String,
    val type: TransferType,
    val src: String,
    val dest: String,
    val totalSize: Long,
    val transferredSize: Long = 0,
    val startTime: Long = System.currentTimeMillis()
)

enum class TransferType {
    UPLOAD, DOWNLOAD
}
