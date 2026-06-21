package io.liriliri.aya.adb

import android.content.Context
import android.util.Log
import io.liriliri.aya.data.*

/**
 * DeviceManager for controlling the local device (the phone running this app).
 * Uses Shizuku or Root instead of ADB protocol.
 */
class LocalDeviceManager(private val context: Context) {
    companion object {
        private const val TAG = "LocalDeviceManager"
    }

    private val executor = LocalCommandExecutor(context)

    val hasShizuku get() = executor.hasShizuku
    val hasRoot get() = executor.hasRoot
    val isAvailable get() = hasShizuku || hasRoot

    fun checkShizukuPermission() = executor.checkShizukuPermission()
    fun requestShizukuPermission() = executor.requestShizukuPermission()

    /**
     * Execute a shell command via Shizuku/Root.
     */
    suspend fun execute(command: String): String = executor.execute(command)

    /**
     * Inject our ADB public key into /data/misc/adb/adb_keys via Shizuku/Root.
     * This allows the ADB daemon to recognize our AUTH SIGNATURE.
     * Required on Android 11+ where AUTH RSA_PUBLIC registration is not supported.
     */
    suspend fun injectAdbKey(publicKeyBase64: String): Boolean {
        if (!isAvailable) {
            Log.w(TAG, "Cannot inject ADB key: no Shizuku or Root")
            return false
        }

        return try {
            // Check if key already exists in correct format
            val existing = try {
                executor.execute("cat /data/misc/adb/adb_keys 2>/dev/null")
            } catch (_: Exception) { "" }

            if (existing.contains(publicKeyBase64)) {
                Log.d(TAG, "ADB key already in adb_keys, skipping injection")
                return true
            }

            Log.d(TAG, "Injecting ADB public key (${publicKeyBase64.length} base64 chars)")

            // Append our key to adb_keys
            // Format: base64(android_pubkey_blob_528bytes) AYA
            // The blob is the 528-byte RSAPublicKey struct (without identity suffix)
            val cmd = """printf '%s AYA\n' '$publicKeyBase64' >> /data/misc/adb/adb_keys && chown system:shell /data/misc/adb/adb_keys && chmod 640 /data/misc/adb/adb_keys"""
            executor.execute(cmd)
            Log.d(TAG, "ADB key written to adb_keys")

            // Restart adbd so it re-reads adb_keys (adbd only loads keys at startup)
            restartAdbd()

            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to inject ADB key: ${e.message}")
            false
        }
    }

    /**
     * Restart adbd to force it to re-read /data/misc/adb/adb_keys.
     */
    private suspend fun restartAdbd() {
        try {
            Log.d(TAG, "Restarting adbd to reload keys...")
            executor.execute("stop adbd && start adbd")
            // Give adbd time to fully restart
            kotlinx.coroutines.delay(2000)
            Log.d(TAG, "adbd restarted")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to restart adbd: ${e.message}")
        }
    }

    // ==================== Device Info ====================

    suspend fun getOverview(): DeviceOverview {
        val results = executor.execute(listOf(
            "getprop ro.product.model",
            "getprop ro.product.brand",
            "getprop ro.product.device",
            "getprop ro.serialno",
            "getprop ro.build.version.release",
            "getprop ro.build.version.sdk",
            "uname -r",
            "getprop ro.hardware",
            "cat /proc/cpuinfo | grep processor | wc -l",
            "getprop ro.product.cpu.abi",
            "cat /proc/meminfo | grep MemTotal",
            "wm size",
            "wm density",
            "dumpsys battery"
        ))

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
            resolution = results.getOrElse(11) { "" }.trim(),
            density = results.getOrElse(12) { "0" }.trim().toIntOrNull() ?: 0,
            batteryLevel = parseBatteryLevel(results.getOrElse(13) { "" }),
            batteryTemperature = parseBatteryTemperature(results.getOrElse(13) { "" }),
            isRooted = hasRoot
        )
    }

    // ==================== Package Management ====================

    suspend fun getPackages(includeSystem: Boolean = false): List<String> {
        val cmd = if (includeSystem) "pm list packages" else "pm list packages -3"
        val result = executor.execute(cmd)
        return result.lines()
            .filter { it.startsWith("package:") }
            .map { it.removePrefix("package:").trim() }
            .sorted()
    }

    suspend fun getPackageInfos(packageNames: List<String>): List<PackageInfo> {
        val infos = mutableListOf<PackageInfo>()

        // Batch get all labels at once using cmd package dump
        // This is much faster than calling per-package
        val labelMap = try {
            val allLabels = executor.execute("cmd package dump 2>/dev/null | grep -E 'pkg=|label=' ")
            parseAllLabels(allLabels)
        } catch (_: Exception) {
            emptyMap()
        }

        for (pkg in packageNames) {
            try {
                val result = executor.execute("dumpsys package $pkg")
                val lines = result.lines()

                // Get label: try batch result first, then per-package pm dump
                val label = labelMap[pkg] ?: try {
                    val pmResult = executor.execute("pm dump $pkg 2>/dev/null | grep 'label=' | head -1")
                    val match = Regex("""label=(.+)""").find(pmResult)
                    match?.groupValues?.get(1)?.trim()?.ifBlank { null }
                } catch (_: Exception) { null } ?: pkg

                infos.add(PackageInfo(
                    packageName = pkg,
                    label = label,
                    versionName = extractField(lines, "versionName"),
                    versionCode = extractField(lines, "versionCode=").split("/").firstOrNull()
                        ?.toLongOrNull() ?: 0,
                    apkPath = extractField(lines, "codePath"),
                    isSystem = result.contains("pkgFlags=") && result.contains("SYSTEM"),
                    isEnabled = !result.contains("enabled=false")
                ))
            } catch (e: Exception) {
                infos.add(PackageInfo(packageName = pkg))
            }
        }
        return infos
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

    suspend fun installPackage(apkPath: String): String {
        return executor.execute("pm install -r '$apkPath'")
    }

    suspend fun uninstallPackage(packageName: String): String {
        return executor.execute("pm uninstall $packageName")
    }

    suspend fun startPackage(packageName: String): String {
        return executor.execute("monkey -p $packageName -c android.intent.category.LAUNCHER 1")
    }

    suspend fun stopPackage(packageName: String): String {
        return executor.execute("am force-stop $packageName")
    }

    suspend fun clearPackage(packageName: String): String {
        return executor.execute("pm clear $packageName")
    }

    suspend fun disablePackage(packageName: String): String {
        return executor.execute("pm disable-user $packageName")
    }

    suspend fun enablePackage(packageName: String): String {
        return executor.execute("pm enable $packageName")
    }

    // ==================== File Management ====================

    suspend fun readDir(path: String): List<DeviceFile> {
        val result = executor.execute("ls -la '$path' 2>/dev/null")
        val files = result.lines()
            .filter { it.isNotBlank() && !it.startsWith("total") }
            .mapNotNull { parseLsLine(it, path) }

        if (files.isNotEmpty()) return files

        // Fallback: use ls -1F (appends / to dirs, * to exec)
        val simple = executor.execute("ls -1F '$path' 2>/dev/null")
        return simple.lines()
            .filter { it.isNotBlank() }
            .map { entry ->
                val isDir = entry.endsWith("/")
                val name = entry.trimEnd('/', '*', '@', '|', '=')
                val fullPath = if (path.endsWith("/")) "$path$name" else "$path/$name"
                DeviceFile(name = name, path = fullPath, isDirectory = isDir, size = 0, permissions = "")
            }
    }

    suspend fun deleteFile(path: String): String {
        return executor.execute("rm -rf '$path'")
    }

    suspend fun createDir(path: String): String {
        return executor.execute("mkdir -p '$path'")
    }

    suspend fun moveFile(src: String, dest: String): String {
        return executor.execute("mv '$src' '$dest'")
    }

    // ==================== Process Management ====================

    suspend fun getProcesses(): List<ProcessInfo> {
        val result = executor.execute("ps -A -o PID,USER,%CPU,TIME,RSS,NAME")
        val processes = result.lines()
            .drop(1)
            .filter { it.isNotBlank() }
            .mapNotNull { parseProcessLine(it) }

        // Batch resolve app labels
        val uniqueNames = processes.filter { it.name.contains('.') }.map { it.name }.distinct()
        if (uniqueNames.isNotEmpty()) {
            try {
                val batchResult = executor.execute("cmd package dump 2>/dev/null | grep -E 'pkg=|label='")
                val labelMap = parseAllLabels(batchResult)
                return processes.map { p ->
                    val label = labelMap[p.name]
                    if (label != null) p.copy(displayName = label) else p
                }
            } catch (_: Exception) {}
        }
        return processes
    }

    // ==================== Performance ====================

    suspend fun getPerformance(): PerformanceSnapshot {
        val results = executor.execute(listOf(
            "cat /proc/stat",
            "cat /proc/meminfo",
            "dumpsys battery"
        ))

        return PerformanceSnapshot(
            cpuLoads = parseCpuLoads(results.getOrElse(0) { "" }),
            memoryUsed = parseMemoryUsed(results.getOrElse(1) { "" }),
            memoryTotal = parseMemoryTotal(results.getOrElse(1) { "" }),
            batteryLevel = parseBatteryLevel(results.getOrElse(2) { "" }),
            batteryTemperature = parseBatteryTemperature(results.getOrElse(2) { "" })
        )
    }

    suspend fun getTopPackage(): String {
        val result = executor.execute("dumpsys activity activities | grep mResumedActivity")
        val regex = Regex("""u0\s+([\w.]+)/""")
        return regex.find(result)?.groupValues?.get(1) ?: ""
    }

    // ==================== Logcat ====================

    suspend fun readLogcat(maxLines: Int = 100): List<LogcatEntry> {
        val result = executor.execute("logcat -d -v threadtime -t $maxLines")
        return result.lines().mapNotNull { parseLogcatLine(it) }
    }

    // ==================== Screenshot ====================

    suspend fun screencap(): ByteArray {
        // Use base64 encoding through elevated executor (Shizuku/Root needed for screencap)
        val result = executor.execute("screencap -p | base64")
        return android.util.Base64.decode(result.trim(), android.util.Base64.DEFAULT)
    }

    // ==================== Layout ====================

    suspend fun dumpWindowHierarchy(): String {
        return executor.execute("uiautomator dump /dev/tty 2>/dev/null || uiautomator dump /sdcard/window_dump.xml && cat /sdcard/window_dump.xml")
    }

    // ==================== Input ====================

    suspend fun inputKey(keyCode: Int): String {
        return executor.execute("input keyevent $keyCode")
    }

    suspend fun inputTap(x: Int, y: Int): String {
        return executor.execute("input tap $x $y")
    }

    suspend fun inputText(text: String): String {
        return executor.execute("input text '${text.replace("'", "\\'")}'")
    }

    // ==================== Helper Methods ====================

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
            memoryKb = (parts[4].toLongOrNull() ?: 0) * 1024,
            name = parts.drop(5).joinToString(" ")
        )
    }

    private fun parseLogcatLine(line: String): LogcatEntry? {
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
}
