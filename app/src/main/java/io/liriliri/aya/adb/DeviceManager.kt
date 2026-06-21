package io.liriliri.aya.adb

import android.content.Context
import android.util.Log
import io.liriliri.aya.data.ConnectionState
import io.liriliri.aya.data.Device
import io.liriliri.aya.data.DeviceOverview
import io.liriliri.aya.data.PackageInfo
import io.liriliri.aya.data.DeviceFile
import io.liriliri.aya.data.ProcessInfo
import io.liriliri.aya.data.PerformanceSnapshot
import io.liriliri.aya.data.LogcatEntry
import io.liriliri.aya.data.WebviewInfo
import io.liriliri.aya.data.PortForward
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Main manager for ADB device connections and operations.
 */
class DeviceManager(
    private val context: Context,
    private val localCommandExecutor: LocalCommandExecutor? = null
) {
    companion object {
        private const val TAG = "DeviceManager"
    }

    private val crypto by lazy { AdbCrypto.loadOrCreate(context) }
    private val connections = ConcurrentHashMap<String, AdbConnection>()
    private val activeStreams = ConcurrentHashMap<String, AdbStream>()
    private val localDeviceManager by lazy { LocalDeviceManager(context) }

    private fun isLocal(deviceId: String) = deviceId == "local"

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _connectedDevices = MutableStateFlow<List<Device>>(emptyList())
    val connectedDevices: StateFlow<List<Device>> = _connectedDevices.asConnectedDevices()

    private fun MutableStateFlow<List<Device>>.asConnectedDevices() = asStateFlow()

    /**
     * Connect to a device via WiFi ADB.
     */
    suspend fun connect(host: String, port: Int = 5555): Device {
        val deviceId = "$host:$port"
        _connectionState.value = ConnectionState.Connecting

        try {
            // Try to inject our ADB key via Shizuku/Root before connecting.
            // On Android 11+, AUTH RSA_PUBLIC registration is not supported,
            // so the key must be pre-registered in /data/misc/adb/adb_keys.
            try {
                val localMgr = LocalDeviceManager(context)
                if (localMgr.isAvailable) {
                    val keyB64 = crypto.getPublicKeyBase64()
                    Log.d(TAG, "Injecting ADB key before connect...")
                    localMgr.injectAdbKey(keyB64)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Key injection failed (non-fatal): ${e.message}")
            }

            val connection = AdbConnection(host, port, crypto)
            connection.connect()
            Log.d(TAG, "ADB connection established, storing connection...")

            connections[deviceId] = connection

            // Persist our ADB key on the remote device so future connections don't need re-auth
            try {
                val keyB64 = crypto.getPublicKeyBase64()
                Log.d(TAG, "Persisting ADB key on remote device...")
                connection.shell("mkdir -p /data/misc/adb 2>/dev/null")
                connection.shell("grep -qF '$keyB64' /data/misc/adb/adb_keys 2>/dev/null || echo '$keyB64' >> /data/misc/adb/adb_keys")
                connection.shell("chmod 640 /data/misc/adb/adb_keys 2>/dev/null")
                Log.d(TAG, "ADB key persisted")
            } catch (e: Exception) {
                Log.w(TAG, "Key persistence failed (non-fatal): ${e.message}")
            }

            // Get device properties
            Log.d(TAG, "Getting device properties...")
            val props = getDeviceProperties(connection)
            Log.d(TAG, "Device properties obtained: ${props["ro.product.model"]}")
            val device = Device(
                id = deviceId,
                host = host,
                port = port,
                name = props["ro.product.model"] ?: "",
                model = props["ro.product.model"] ?: "",
                androidVersion = props["ro.build.version.release"] ?: "",
                apiLevel = (props["ro.build.version.sdk"] ?: "0").toIntOrNull() ?: 0,
                isConnected = true,
                lastConnected = System.currentTimeMillis()
            )

            _connectionState.value = ConnectionState.Connected(device)
            _connectedDevices.value = _connectedDevices.value + device

            return device
        } catch (e: Exception) {
            _connectionState.value = ConnectionState.Error(e.message ?: "Unknown error")
            throw e
        }
    }

    /**
     * Disconnect from a device.
     */
    fun disconnect(deviceId: String) {
        connections[deviceId]?.disconnect()
        connections.remove(deviceId)
        _connectedDevices.value = _connectedDevices.value.filter { it.id != deviceId }
        if (_connectedDevices.value.isEmpty()) {
            _connectionState.value = ConnectionState.Disconnected
        }
    }

    /**
     * Disconnect all devices.
     */
    fun disconnectAll() {
        connections.values.forEach { it.disconnect() }
        connections.clear()
        _connectedDevices.value = emptyList()
        _connectionState.value = ConnectionState.Disconnected
    }

    /**
     * Get the ADB connection for a device.
     */
    fun getConnection(deviceId: String): AdbConnection {
        return connections[deviceId] ?: throw IllegalStateException("Device not connected: $deviceId")
    }

    /**
     * Execute a simple shell command and return output.
     */
    suspend fun executeCommand(deviceId: String, command: String): String {
        if (isLocal(deviceId)) return localDeviceManager.execute(command)
        val conn = getConnection(deviceId)
        return conn.shell(command)
    }

    // ==================== Device Info ====================

    suspend fun getOverview(deviceId: String): DeviceOverview {
        if (isLocal(deviceId)) return localDeviceManager.getOverview()
        val conn = getConnection(deviceId)
        val commands = listOf(
            "getprop ro.product.model",                    // 0
            "getprop ro.product.brand",                    // 1
            "getprop ro.product.device",                   // 2
            "getprop ro.serialno",                         // 3
            "getprop ro.build.version.release",            // 4
            "getprop ro.build.version.sdk",                // 5
            "uname -r",                                    // 6
            "getprop ro.hardware",                         // 7
            "cat /proc/cpuinfo | grep processor | wc -l",  // 8
            "getprop ro.product.cpu.abi",                  // 9
            "cat /proc/meminfo | grep MemTotal",           // 10
            "wm size",                                     // 11
            "wm density",                                  // 12
            "dumpsys wifi | grep 'mWifiInfo' | grep -o 'SSID: [^,]*' | head -1",  // 13
            "ip addr show wlan0 2>/dev/null | grep 'inet ' | awk '{print \$2}' | cut -d/ -f1",  // 14
            "cat /sys/class/net/wlan0/address 2>/dev/null",  // 15
            "cat /proc/partitions 2>/dev/null | grep -v 'name' | grep 'mmcblk' | tail -1",  // 16
            "df /data 2>/dev/null | tail -1",              // 17
            "dumpsys diskstats",                           // 18
            "dumpsys battery"                              // 19
        )
        val results = conn.shell(commands)

        // Parse storage - try multiple sources
        val storageInfo = parseStorageInfo(
            results.getOrElse(17) { "" },
            results.getOrElse(18) { "" }
        )

        // Parse WiFi SSID
        val wifiRaw = results.getOrElse(13) { "" }
        val wifiSsid = Regex("""SSID:\s*"?([^",]+)"?""").find(wifiRaw)?.groupValues?.get(1)?.trim() ?: ""

        // Parse IP - try multiple sources
        var ip = results.getOrElse(14) { "" }.trim()
        if (ip.isBlank()) {
            ip = try {
                val ipResult = conn.shell("ip route get 1.1.1.1 2>/dev/null | grep -o 'src [0-9.]*' | awk '{print \$2}'")
                ipResult.trim()
            } catch (_: Exception) { "" }
        }

        return DeviceOverview(
            model = results.getOrElse(0) { "" },
            brand = results.getOrElse(1) { "" },
            name = results.getOrElse(0) { "" },
            serial = results.getOrElse(3) { "" },
            androidVersion = results.getOrElse(4) { "" },
            apiLevel = results.getOrElse(5) { "0" }.toIntOrNull() ?: 0,
            kernelVersion = results.getOrElse(6) { "" },
            processor = results.getOrElse(7) { "" },
            cores = results.getOrElse(8) { "0" }.trim().toIntOrNull() ?: 0,
            abi = results.getOrElse(9) { "" },
            memoryTotal = parseMemoryTotal(results.getOrElse(10) { "" }),
            storageTotal = storageInfo.first,
            storageUsed = storageInfo.second,
            resolution = results.getOrElse(11) { "" }.trim(),
            density = parseDensity(results.getOrElse(12) { "" }),
            ip = ip,
            mac = results.getOrElse(15) { "" }.trim(),
            wifiSsid = wifiSsid,
            batteryLevel = parseBatteryLevel(results.getOrElse(19) { "" }),
            batteryTemperature = parseBatteryTemperature(results.getOrElse(19) { "" }),
            isRooted = checkRoot(conn)
        )
    }

    // ==================== Package Management ====================

    suspend fun getPackages(deviceId: String, includeSystem: Boolean = false): List<String> {
        if (isLocal(deviceId)) return localDeviceManager.getPackages(includeSystem)
        val conn = getConnection(deviceId)
        val cmd = if (includeSystem) "pm list packages" else "pm list packages -3"
        val result = conn.shell(cmd)
        return result.lines()
            .filter { it.startsWith("package:") }
            .map { it.removePrefix("package:").trim() }
            .sorted()
    }

    suspend fun getPackageInfos(deviceId: String, packageNames: List<String>): List<PackageInfo> {
        if (isLocal(deviceId)) return localDeviceManager.getPackageInfos(packageNames)
        val conn = getConnection(deviceId)
        if (packageNames.isEmpty()) return emptyList()

        // Batch: resolve labels first (1 shell call)
        val labelMap = try {
            val labelResult = conn.shell("cmd package dump 2>/dev/null | grep -E 'pkg=|label='")
            parseAllLabels(labelResult)
        } catch (_: Exception) { emptyMap<String, String>() }

        // Batch: get dumpsys info for all packages in chunks (avoids 2*N shell calls)
        val infos = mutableListOf<PackageInfo>()
        for (chunk in packageNames.chunked(50)) {
            val chunkInfos = getPackageInfosBatch(conn, chunk, labelMap)
            infos.addAll(chunkInfos)
        }
        return infos
    }

    private suspend fun getPackageInfosBatch(
        conn: AdbConnection,
        packages: List<String>,
        labelMap: Map<String, String>
    ): List<PackageInfo> {
        // Build a single shell command that dumps all packages with separators
        val sep = "|@@AYA@@|"
        val cmd = packages.joinToString(" && echo '$sep' && ") { pkg ->
            "dumpsys package $pkg 2>/dev/null | head -80"
        }

        val result = try {
            conn.shell(cmd)
        } catch (e: Exception) {
            Log.w(TAG, "Batch getPackageInfos failed: ${e.message}")
            return packages.map { PackageInfo(packageName = it, label = labelMap[it] ?: it) }
        }

        val sections = result.split(sep)
        return packages.mapIndexed { i, pkg ->
            val dumpsys = sections.getOrElse(i) { "" }
            val lines = dumpsys.lines()
            val label = labelMap[pkg] ?: pkg
            parsePackageInfoFromDumpsys(pkg, label, dumpsys, lines)
        }
    }

    private fun parsePackageInfoFromDumpsys(
        pkg: String,
        label: String,
        dumpsys: String,
        lines: List<String>
    ): PackageInfo {
        // Extract fields from the first ~80 lines of dumpsys package output
        var versionName = ""
        var versionCode = 0L
        var minSdk = 0
        var targetSdk = 0
        var codePath = ""

        for (line in lines) {
            val l = line.trim()
            if (versionName.isBlank() && l.startsWith("versionName=")) {
                versionName = l.removePrefix("versionName=").trim()
            }
            if (versionCode == 0L && l.startsWith("versionCode=")) {
                val raw = l.removePrefix("versionCode=").trim()
                versionCode = raw.split("/").firstOrNull()?.toLongOrNull() ?: 0
            }
            if (minSdk == 0 && l.startsWith("minSdk=")) {
                minSdk = l.removePrefix("minSdk=").trim().toIntOrNull() ?: 0
            }
            if (targetSdk == 0 && l.startsWith("targetSdk=")) {
                targetSdk = l.removePrefix("targetSdk=").trim().toIntOrNull() ?: 0
            }
            if (codePath.isBlank() && l.startsWith("codePath=")) {
                codePath = l.removePrefix("codePath=").trim()
            }
            if (versionName.isNotBlank() && versionCode > 0 && codePath.isNotBlank()) break
        }

        return PackageInfo(
            packageName = pkg,
            label = label,
            versionName = versionName,
            versionCode = versionCode,
            minSdkVersion = minSdk,
            targetSdkVersion = targetSdk,
            apkPath = codePath,
            isSystem = dumpsys.contains("SYSTEM"),
            isEnabled = !dumpsys.contains("enabled=false")
        )
    }

    suspend fun installPackage(deviceId: String, apkPath: String): String {
        if (isLocal(deviceId)) return localDeviceManager.installPackage(apkPath)
        val conn = getConnection(deviceId)
        Log.d(TAG, "Installing APK: $apkPath")

        // Method 1: Direct install
        var result = conn.shell("pm install -r -t '$apkPath' 2>&1")
        Log.d(TAG, "Direct install result: $result")
        if (result.contains("Success", ignoreCase = true)) return result

        // Method 2: Copy to /data/local/tmp then install (for scoped storage restrictions)
        Log.d(TAG, "Trying /data/local/tmp approach...")
        val tmpPath = "/data/local/tmp/_aya_install.apk"
        conn.shell("cp '$apkPath' '$tmpPath' 2>&1")
        result = conn.shell("pm install -r -t '$tmpPath' 2>&1")
        Log.d(TAG, "Tmp install result: $result")
        conn.shell("rm -f '$tmpPath'")
        if (result.contains("Success", ignoreCase = true)) return result

        // Method 3: Streaming install via cat pipe
        Log.d(TAG, "Trying streaming install...")
        result = conn.shell("cat '$apkPath' | pm install -S \$(stat -c '%s' '$apkPath' 2>/dev/null || echo 0) -r -t 2>&1")
        Log.d(TAG, "Streaming install result: $result")

        return result
    }

    /**
     * Push a local APK file to the remote device and install it.
     * Uses streaming install (pm install -S) to avoid broken pipe on large files.
     */
    suspend fun pushAndInstall(deviceId: String, localApkPath: String): String {
        if (isLocal(deviceId)) return localDeviceManager.installPackage(localApkPath)
        val conn = getConnection(deviceId)
        val apkFile = java.io.File(localApkPath)
        val fileSize = apkFile.length()

        Log.d(TAG, "Streaming install: $localApkPath ($fileSize bytes)")

        // Use pm install -S to stream APK data directly (no broken pipe)
        val stream = conn.open("shell:pm install -S $fileSize -r -t")

        // Write APK data in chunks with flow control
        val buffer = ByteArray(32768)
        apkFile.inputStream().use { fis ->
            var totalWritten = 0L
            while (totalWritten < fileSize) {
                val read = fis.read(buffer)
                if (read < 0) break
                stream.write(if (read == buffer.size) buffer else buffer.copyOf(read))
                totalWritten += read
                if (totalWritten % (1024 * 1024) == 0L || totalWritten == fileSize) {
                    Log.d(TAG, "Streaming install progress: $totalWritten / $fileSize")
                }
            }
        }

        // Wait for pm to process and send result (device closes the shell)
        val output = java.io.ByteArrayOutputStream()
        try {
            stream.output.collect { data ->
                if (data.isEmpty()) return@collect
                output.write(data)
            }
        } catch (_: Exception) {}

        val result = output.toString().trim()
        Log.d(TAG, "Streaming install result: $result")
        return result
    }

    suspend fun uninstallPackage(deviceId: String, packageName: String): String {
        if (isLocal(deviceId)) return localDeviceManager.uninstallPackage(packageName)
        val conn = getConnection(deviceId)
        return conn.shell("pm uninstall $packageName")
    }

    suspend fun startPackage(deviceId: String, packageName: String): String {
        if (isLocal(deviceId)) return localDeviceManager.startPackage(packageName)
        val conn = getConnection(deviceId)
        // Get the main activity
        val dumpResult = conn.shell("dumpsys package $packageName | grep -A 1 MAIN")
        val activityLine = dumpResult.lines().find { it.contains("$packageName/") }
        val component = activityLine?.trim()?.split(" ")?.find { it.contains("$packageName/") }
            ?: return conn.shell("monkey -p $packageName -c android.intent.category.LAUNCHER 1")
        return conn.shell("am start -n $component")
    }

    suspend fun stopPackage(deviceId: String, packageName: String): String {
        if (isLocal(deviceId)) return localDeviceManager.stopPackage(packageName)
        val conn = getConnection(deviceId)
        return conn.shell("am force-stop $packageName")
    }

    suspend fun clearPackage(deviceId: String, packageName: String): String {
        if (isLocal(deviceId)) return localDeviceManager.clearPackage(packageName)
        val conn = getConnection(deviceId)
        return conn.shell("pm clear $packageName")
    }

    suspend fun disablePackage(deviceId: String, packageName: String): String {
        if (isLocal(deviceId)) return localDeviceManager.disablePackage(packageName)
        val conn = getConnection(deviceId)
        return conn.shell("pm disable-user $packageName")
    }

    suspend fun enablePackage(deviceId: String, packageName: String): String {
        if (isLocal(deviceId)) return localDeviceManager.enablePackage(packageName)
        val conn = getConnection(deviceId)
        return conn.shell("pm enable $packageName")
    }

    suspend fun getTopPackage(deviceId: String): Pair<String, Int> {
        if (isLocal(deviceId)) return Pair(localDeviceManager.getTopPackage(), 0)
        val conn = getConnection(deviceId)
        val result = conn.shell("dumpsys activity activities | grep mResumedActivity")
        val regex = Regex("""u0\s+([\w.]+)/([\w.$]+)""")
        val match = regex.find(result)
        return if (match != null) {
            Pair(match.groupValues[1], 0)
        } else {
            Pair("", 0)
        }
    }

    // ==================== File Management ====================

    suspend fun readDir(deviceId: String, path: String): List<DeviceFile> {
        if (isLocal(deviceId)) return localDeviceManager.readDir(path)
        val conn = getConnection(deviceId)

        // Use ls -la which gives sizes and permissions
        val result = conn.shell("ls -la '$path' 2>/dev/null")
        val files = result.lines()
            .filter { it.isNotBlank() && !it.startsWith("total") }
            .mapNotNull { parseLsLine(it, path) }

        if (files.isNotEmpty()) return files

        // Fallback: ls -1F + stat for sizes
        val simple = conn.shell("ls -1F '$path' 2>/dev/null")
        if (simple.isBlank()) return emptyList()

        // Get sizes in batch using stat (single shell call)
        val entries = simple.lines().filter { it.isNotBlank() }
        val sizeMap = try {
            val names = entries.map { it.trimEnd('/', '*', '@', '|', '=') }
            val statCmd = names.joinToString(";") { "stat -c '%n %s' '$path/$it' 2>/dev/null" }
            val statResult = conn.shell(statCmd)
            statResult.lines().filter { it.isNotBlank() }.associate {
                val parts = it.split(" ", limit = 2)
                parts[0].substringAfterLast('/') to (parts.getOrNull(1)?.toLongOrNull() ?: 0)
            }
        } catch (_: Exception) { emptyMap() }

        return entries.map { entry ->
            val isDir = entry.endsWith("/")
            val name = entry.trimEnd('/', '*', '@', '|', '=')
            val fullPath = if (path.endsWith("/")) "$path$name" else "$path/$name"
            DeviceFile(name = name, path = fullPath, isDirectory = isDir, size = sizeMap[name] ?: 0, permissions = "")
        }
    }

    suspend fun deleteFile(deviceId: String, path: String): String {
        if (isLocal(deviceId)) return localDeviceManager.deleteFile(path)
        val conn = getConnection(deviceId)
        return conn.shell("rm -rf '$path'")
    }

    suspend fun createDir(deviceId: String, path: String): String {
        if (isLocal(deviceId)) return localDeviceManager.createDir(path)
        val conn = getConnection(deviceId)
        return conn.shell("mkdir -p '$path'")
    }

    suspend fun moveFile(deviceId: String, src: String, dest: String): String {
        if (isLocal(deviceId)) return localDeviceManager.moveFile(src, dest)
        val conn = getConnection(deviceId)
        return conn.shell("mv '$src' '$dest'")
    }

    /**
     * Find APK files in common directories on the device.
     */
    suspend fun findApkFiles(deviceId: String): List<String> {
        if (isLocal(deviceId)) return localDeviceManager.findApkFiles()
        val conn = getConnection(deviceId)
        val result = conn.shell("find /sdcard/Download /sdcard /storage/emulated/0/Download /storage/emulated/0 -maxdepth 2 -name '*.apk' -type f 2>/dev/null | sort -u")
        return result.lines().filter { it.isNotBlank() && it.endsWith(".apk") }
    }

    suspend fun pullFile(deviceId: String, remotePath: String, localPath: String) {
        // Use a file server approach or cat + base64 encoding
        val conn = getConnection(deviceId)
        // For small files, use base64
        // For large files, use a temporary HTTP server
        // For now, use base64 approach
        val result = conn.shell("base64 '$remotePath'")
        val bytes = android.util.Base64.decode(result, android.util.Base64.DEFAULT)
        java.io.File(localPath).writeBytes(bytes)
    }

    suspend fun pushFile(deviceId: String, localPath: String, remotePath: String) {
        val conn = getConnection(deviceId)
        val bytes = java.io.File(localPath).readBytes()
        val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        conn.shell("echo '$base64' | base64 -d > '$remotePath'")
    }

    // ==================== Process Management ====================

    suspend fun getProcesses(deviceId: String): List<ProcessInfo> {
        if (isLocal(deviceId)) return localDeviceManager.getProcesses()
        val conn = getConnection(deviceId)
        val result = conn.shell("ps -A -o PID,USER,%CPU,TIME,RSS,NAME")
        val processes = result.lines()
            .drop(1) // header
            .filter { it.isNotBlank() }
            .mapNotNull { parseProcessLine(it) }

        // Batch resolve app labels using a single cmd package dump
        val uniqueNames = processes.filter { it.name.contains('.') }.map { it.name }.distinct()
        if (uniqueNames.isNotEmpty()) {
            try {
                val batchResult = conn.shell("cmd package dump 2>/dev/null | grep -E 'pkg=|label='")
                val labelMap = parseAllLabels(batchResult)
                return processes.map { p ->
                    val label = labelMap[p.name]
                    if (label != null) p.copy(displayName = label) else p
                }
            } catch (_: Exception) {
                // Fallback: try individual lookups for a few key packages
                val labelMap = mutableMapOf<String, String>()
                for (name in uniqueNames.take(20)) {
                    try {
                        val r = conn.shell("pm dump $name 2>/dev/null | grep 'label=' | head -1")
                        val m = Regex("""label=(.+)""").find(r)
                        val l = m?.groupValues?.get(1)?.trim()?.ifBlank { null }
                        if (l != null) labelMap[name] = l
                    } catch (_: Exception) {}
                }
                return processes.map { p ->
                    val label = labelMap[p.name]
                    if (label != null) p.copy(displayName = label) else p
                }
            }
        }
        return processes
    }

    // ==================== Performance ====================

    suspend fun getPerformance(deviceId: String): PerformanceSnapshot {
        if (isLocal(deviceId)) return localDeviceManager.getPerformance()
        val conn = getConnection(deviceId)
        val results = conn.shell(listOf(
            "cat /proc/stat",
            "cat /proc/meminfo",
            "dumpsys battery"
        ))

        val cpuStat = results.getOrElse(0) { "" }
        val memInfo = results.getOrElse(1) { "" }
        val battery = results.getOrElse(2) { "" }

        return PerformanceSnapshot(
            cpuLoads = parseCpuLoads(cpuStat),
            memoryUsed = parseMemoryUsed(memInfo),
            memoryTotal = parseMemoryTotal(memInfo),
            batteryLevel = parseBatteryLevel(battery),
            batteryVoltage = parseBatteryVoltage(battery),
            batteryTemperature = parseBatteryTemperature(battery)
        )
    }

    suspend fun getCpuTemperature(deviceId: String): Float {
        val conn = getConnection(deviceId)
        val result = conn.shell("dumpsys thermalservice")
        return parseCpuTemperature(result)
    }

    suspend fun getFps(deviceId: String, packageName: String): Float {
        val conn = getConnection(deviceId)
        val result = conn.shell("dumpsys SurfaceFlinger --list")
        val layers = result.lines().filter { it.contains(packageName) }
        if (layers.isEmpty()) return 0f

        val layer = layers.first()
        val latencyResult = conn.shell("dumpsys SurfaceFlinger --latency '$layer'")
        return parseFps(latencyResult)
    }

    suspend fun getUptime(deviceId: String): Long {
        val conn = getConnection(deviceId)
        val result = conn.shell("cat /proc/uptime")
        return result.split("\\s+".toRegex()).firstOrNull()?.toFloatOrNull()?.toLong() ?: 0
    }

    // ==================== Logcat ====================

    suspend fun openLogcat(deviceId: String, onEntry: suspend (LogcatEntry) -> Unit): String {
        val conn = getConnection(deviceId)
        val stream = conn.open("shell:logcat -v threadtime")
        val streamId = "logcat_${System.currentTimeMillis()}"

        activeStreams[streamId] = stream

        // Read logcat entries in a coroutine
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val buffer = StringBuilder()
                stream.output.collect { data ->
                    if (data.isEmpty()) return@collect
                    buffer.append(String(data))
                    // Process complete lines
                    while (true) {
                        val newlineIdx = buffer.indexOf('\n')
                        if (newlineIdx < 0) break
                        val line = buffer.substring(0, newlineIdx).trim()
                        buffer.delete(0, newlineIdx + 1)
                        if (line.isNotBlank()) {
                            val entry = parseLogcatLine(line)
                            if (entry != null) {
                                onEntry(entry)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Logcat error: ${e.message}")
            }
        }

        return streamId
    }

    suspend fun closeLogcat(streamId: String) {
        activeStreams.remove(streamId)?.close()
    }

    suspend fun pauseLogcat(streamId: String) {
        // Send SIGSTOP to logcat or close and reopen
        // For simplicity, we just stop collecting
    }

    suspend fun resumeLogcat(streamId: String) {
        // Resume collection
    }

    // ==================== Screenshot ====================

    suspend fun screencap(deviceId: String): ByteArray {
        if (isLocal(deviceId)) return localDeviceManager.screencap()
        val conn = getConnection(deviceId)
        val result = conn.shell("screencap -p | base64")
        return android.util.Base64.decode(result.trim(), android.util.Base64.DEFAULT)
    }

    // ==================== Layout ====================

    suspend fun dumpWindowHierarchy(deviceId: String): String {
        if (isLocal(deviceId)) return localDeviceManager.dumpWindowHierarchy()
        val conn = getConnection(deviceId)
        return conn.shell("uiautomator dump /dev/tty 2>/dev/null || uiautomator dump /sdcard/window_dump.xml && cat /sdcard/window_dump.xml")
    }

    // ==================== Webview ====================

    suspend fun getWebviews(deviceId: String, pid: Int): List<WebviewInfo> {
        val conn = getConnection(deviceId)
        val unixResult = conn.shell("cat /proc/net/unix")
        val sockets = unixResult.lines()
            .filter { it.contains("webview_devtools_remote_$pid") }
            .mapNotNull { line ->
                line.split("\\s+".toRegex()).lastOrNull()?.trimStart('@')
            }

        if (sockets.isEmpty()) return emptyList()

        val socketName = sockets.first()
        val localPort = forwardTcp(deviceId, "localabstract:$socketName")

        // Query Chrome DevTools Protocol
        return try {
            val url = "http://127.0.0.1:$localPort/json"
            val result = withContext(Dispatchers.IO) {
                java.net.URL(url).readText()
            }
            parseWebviewList(result)
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ==================== Port Forwarding ====================

    suspend fun forwardTcp(deviceId: String, remote: String): Int {
        val conn = getConnection(deviceId)
        // List existing forwards to reuse
        val existing = conn.shell("host-serial:${deviceId.split(":").firstOrNull() ?: ""}:list-forward")
        // Parse and find existing or create new
        val localPort = (10000..60000).random()
        conn.shell("tcp:$localPort $remote")
        return localPort
    }

    suspend fun listForwards(deviceId: String): List<PortForward> {
        val conn = getConnection(deviceId)
        val result = conn.shell("host:list-forward")
        return result.lines()
            .filter { it.isNotBlank() }
            .mapNotNull { parseForwardLine(it) }
    }

    // ==================== Input ====================

    suspend fun inputKey(deviceId: String, keyCode: Int): String {
        if (isLocal(deviceId)) return localDeviceManager.inputKey(keyCode)
        val conn = getConnection(deviceId)
        return conn.shell("input keyevent $keyCode")
    }

    suspend fun inputText(deviceId: String, text: String): String {
        if (isLocal(deviceId)) return localDeviceManager.inputText(text)
        val conn = getConnection(deviceId)
        return conn.shell("input text '${text.replace("'", "\\'")}'")
    }

    suspend fun inputTap(deviceId: String, x: Int, y: Int): String {
        if (isLocal(deviceId)) return localDeviceManager.inputTap(x, y)
        val conn = getConnection(deviceId)
        return conn.shell("input tap $x $y")
    }

    suspend fun inputSwipe(deviceId: String, x1: Int, y1: Int, x2: Int, y2: Int, duration: Int = 300): String {
        if (isLocal(deviceId)) return localDeviceManager.inputTap(x1, y1)
        val conn = getConnection(deviceId)
        return conn.shell("input swipe $x1 $y1 $x2 $y2 $duration")
    }

    // ==================== Settings ====================

    suspend fun getFontScale(deviceId: String): Float {
        val conn = getConnection(deviceId)
        val result = conn.shell("settings get system font_scale")
        return result.toFloatOrNull() ?: 1.0f
    }

    suspend fun setFontScale(deviceId: String, scale: Float): String {
        val conn = getConnection(deviceId)
        return conn.shell("settings put system font_scale $scale")
    }

    // ==================== Helper Methods ====================

    private suspend fun getDeviceProperties(conn: AdbConnection): Map<String, String> {
        val result = conn.shell("getprop")
        val props = mutableMapOf<String, String>()
        result.lines().forEach { line ->
            val match = Regex("""\[([^\]]+)\]:\s*\[([^\]]*)\]""").find(line)
            if (match != null) {
                props[match.groupValues[1]] = match.groupValues[2]
            }
        }
        return props
    }

    /**
     * Parse batch package dump output to extract package->label mapping.
     */
    private fun parseAllLabels(output: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        var currentPkg = ""
        for (line in output.lines()) {
            val pkgMatch = Regex("""pkg=([^\s]+)""").find(line)
            if (pkgMatch != null) {
                currentPkg = pkgMatch.groupValues[1]
            }
            val labelMatch = Regex("""label=(.+)""").find(line)
            if (labelMatch != null && currentPkg.isNotEmpty()) {
                val label = labelMatch.groupValues[1].trim()
                if (label.isNotBlank() && label != currentPkg) {
                    map[currentPkg] = label
                }
            }
        }
        return map
    }

    private suspend fun checkRoot(conn: AdbConnection): Boolean {
        return try {
            val result = conn.shell("id")
            result.contains("uid=0")
        } catch (_: Exception) {
            false
        }
    }

    private fun parseMemoryTotal(info: String): Long {
        val match = Regex("""MemTotal:\s+(\d+)\s+kB""").find(info)
        return match?.groupValues?.get(1)?.toLongOrNull()?.times(1024) ?: 0
    }

    private fun parseMemoryUsed(info: String): Long {
        val total = parseMemoryTotal(info)
        val available = Regex("""MemAvailable:\s+(\d+)\s+kB""").find(info)
            ?.groupValues?.get(1)?.toLongOrNull()?.times(1024) ?: 0
        return total - available
    }

    private fun parseBatteryLevel(battery: String): Int {
        val match = Regex("""level:\s*(\d+)""").find(battery)
        return match?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }

    /**
     * Parse df output line to get storage total and used.
     * Format: Filesystem     1K-blocks    Used Available Use% Mounted on
     *         /dev/block/...  12345678  1234567  11111111  10% /data
     * Returns Pair(totalBytes, usedBytes)
     */
    /**
     * Parse density from wm density output.
     * Possible formats: "Physical density: 480", "480", "Override density: 320"
     */
    private fun parseDensity(raw: String): Int {
        val numbers = Regex("""\d+""").findAll(raw).map { it.value.toIntOrNull() ?: 0 }.toList()
        // Return override density if present, else physical density, else first number
        val overrideMatch = Regex("""Override density:\s*(\d+)""").find(raw)
        if (overrideMatch != null) return overrideMatch.groupValues[1].toIntOrNull() ?: 0
        return numbers.firstOrNull { it > 0 } ?: 0
    }

    /**
     * Parse storage info from df and dumpsys diskstats outputs.
     */
    private fun parseStorageInfo(dfLine: String, diskstats: String): Pair<Long, Long> {
        // Try df output first
        val dfResult = parseDfOutput(dfLine)
        if (dfResult.first > 0) return dfResult

        // Fallback: parse from dumpsys diskstats
        // Format: "Data-Free: 66455356K / 244474720K total = 27% free"
        val diskMatch = Regex("""Data-Free:\s*([\d.]+)\s*([KMGT]?)\s*/\s*([\d.]+)\s*([KMGT]?)""", RegexOption.IGNORE_CASE).find(diskstats)
        if (diskMatch != null) {
            val freeBytes = parseSizeWithUnit(diskMatch.groupValues[1], diskMatch.groupValues[2])
            val totalBytes = parseSizeWithUnit(diskMatch.groupValues[3], diskMatch.groupValues[4])
            return Pair(totalBytes, totalBytes - freeBytes)
        }

        return Pair(0, 0)
    }

    private fun parseSizeWithUnit(value: String, unit: String): Long {
        val num = value.toDoubleOrNull() ?: return 0
        return when (unit.uppercase()) {
            "K" -> (num * 1024).toLong()
            "M" -> (num * 1024 * 1024).toLong()
            "G" -> (num * 1024 * 1024 * 1024).toLong()
            "T" -> (num * 1024 * 1024 * 1024 * 1024).toLong()
            else -> num.toLong()
        }
    }

    private fun parseDfOutput(line: String): Pair<Long, Long> {
        val parts = line.trim().split("\\s+".toRegex())
        if (parts.size < 4) return Pair(0, 0)
        // Try to find the total and used columns
        // Format: Filesystem 1K-blocks Used Available Use% Mounted
        // But some devices have different column counts
        val nums = parts.mapNotNull { it.toLongOrNull() }
        if (nums.size >= 3) {
            // First number is usually total, second is used
            return Pair(nums[0] * 1024, nums[1] * 1024)
        }
        if (nums.size >= 2) {
            return Pair(nums[0] * 1024, nums[1] * 1024)
        }
        return Pair(0, 0)
    }

    private fun parseBatteryVoltage(battery: String): Float {
        val match = Regex("""voltage:\s*(\d+)""").find(battery)
        return (match?.groupValues?.get(1)?.toFloatOrNull() ?: 0f) / 1000f
    }

    private fun parseBatteryTemperature(battery: String): Float {
        val match = Regex("""temperature:\s*(\d+)""").find(battery)
        return (match?.groupValues?.get(1)?.toFloatOrNull() ?: 0f) / 10f
    }

    private fun parseCpuLoads(stat: String): List<Float> {
        val loads = mutableListOf<Float>()
        stat.lines().forEach { line ->
            if (line.startsWith("cpu") && !line.startsWith("cpu ")) {
                val parts = line.split("\\s+".toRegex()).drop(1)
                if (parts.size >= 4) {
                    val user = parts[0].toLongOrNull() ?: 0
                    val nice = parts[1].toLongOrNull() ?: 0
                    val sys = parts[2].toLongOrNull() ?: 0
                    val idle = parts[3].toLongOrNull() ?: 0
                    val iowait = parts.getOrElse(4) { "0" }.toLongOrNull() ?: 0
                    val total = user + nice + sys + idle + iowait
                    val used = total - idle - iowait
                    if (total > 0) {
                        loads.add(used * 100f / total)
                    }
                }
            }
        }
        return loads
    }

    private fun parseCpuTemperature(thermalservice: String): Float {
        val temps = Regex("""Temperature\{mValue=([0-9.]+),\s*mType=\d+,\s*mName=CPU""")
            .findAll(thermalservice)
            .mapNotNull { it.groupValues[1].toFloatOrNull() }
            .toList()
        return if (temps.isNotEmpty()) temps.average().toFloat() else 0f
    }

    private fun parseFps(latency: String): Float {
        val lines = latency.lines().filter { it.isNotBlank() }
        if (lines.size < 3) return 0f

        val timestamps = lines.drop(2) // skip header lines
            .mapNotNull { line ->
                val parts = line.split("\\s+".toRegex())
                parts.getOrNull(0)?.toLongOrNull()
            }
            .filter { it > 0 }

        if (timestamps.size < 2) return 0f

        val duration = (timestamps.last() - timestamps.first()) / 1_000_000_000.0
        return if (duration > 0) ((timestamps.size - 1) / duration).toFloat() else 0f
    }

    private fun parseLsLine(line: String, parentPath: String): DeviceFile? {
        val parts = line.split("\\s+".toRegex(), limit = 9)
        if (parts.size < 9) return null

        val perms = parts[0]
        val size = parts[4].toLongOrNull() ?: 0
        val name = parts[8]
        if (name == "." || name == "..") return null

        return DeviceFile(
            name = name,
            path = if (parentPath.endsWith("/")) "$parentPath$name" else "$parentPath/$name",
            isDirectory = perms.startsWith("d"),
            size = size,
            permissions = perms
        )
    }

    private fun parseProcessLine(line: String): ProcessInfo? {
        val parts = line.split("\\s+".toRegex())
        if (parts.size < 6) return null

        return ProcessInfo(
            pid = parts[0].toIntOrNull() ?: return null,
            user = parts[1],
            cpuPercent = parts[2].toFloatOrNull() ?: 0f,
            cpuTime = parts[3],
            memoryKb = (parts[4].toLongOrNull() ?: 0) * 1024, // RSS is in pages (4KB typically)
            name = parts.drop(5).joinToString(" ")
        )
    }

    private fun parseLogcatLine(line: String): LogcatEntry? {
        // Format: MM-DD HH:MM:SS.mmm  PID  TID LEVEL TAG: MESSAGE
        val regex = Regex("""(\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d{3})\s+(\d+)\s+(\d+)\s+([VDIWEF])\s+(.+?):\s*(.*)""")
        val match = regex.find(line) ?: return null

        return LogcatEntry(
            timestamp = System.currentTimeMillis(),
            pid = match.groupValues[2].toIntOrNull() ?: 0,
            tid = match.groupValues[3].toIntOrNull() ?: 0,
            priority = match.groupValues[4].firstOrNull() ?: 'I',
            tag = match.groupValues[5].trim(),
            message = match.groupValues[6]
        )
    }

    private fun parseWebviewList(json: String): List<WebviewInfo> {
        // Simple JSON array parsing
        val items = mutableListOf<WebviewInfo>()
        val regex = Regex("""\{[^}]+\}""")
        regex.findAll(json).forEach { match ->
            val obj = match.value
            val title = extractJsonValue(obj, "title")
            val url = extractJsonValue(obj, "url")
            val wsUrl = extractJsonValue(obj, "webSocketDebuggerUrl")
            val favicon = extractJsonValue(obj, "faviconUrl")
            if (url.isNotBlank()) {
                items.add(WebviewInfo(title, url, wsUrl, favicon))
            }
        }
        return items
    }

    private fun extractJsonValue(json: String, key: String): String {
        val regex = Regex(""""$key"\s*:\s*"([^"]*)"""")
        return regex.find(json)?.groupValues?.get(1) ?: ""
    }

    private fun extractField(lines: List<String>, field: String): String {
        for (line in lines) {
            if (line.contains(field)) {
                val regex = Regex("""$field\s*[=:]\s*(.+?)\s*$""")
                val match = regex.find(line)
                if (match != null) {
                    return match.groupValues[1].trim().trim('"', '\'')
                }
            }
        }
        return ""
    }

    private fun parseForwardLine(line: String): PortForward? {
        val parts = line.split("\\s+".toRegex())
        if (parts.size < 3) return null
        return PortForward(local = parts[1], remote = parts[2])
    }
}
