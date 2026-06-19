package io.liriliri.aya.adb

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

/**
 * Executes commands on the local device using Shizuku or Root.
 * For controlling the device this app runs on.
 */
class LocalCommandExecutor(private val context: Context) : CommandExecutor {

    val hasShizuku: Boolean
        get() = try {
            Shizuku.pingBinder()
        } catch (_: Exception) {
            false
        }

    val hasRoot: Boolean
        get() = try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()
            output.contains("uid=0")
        } catch (_: Exception) {
            false
        }

    override suspend fun execute(command: String): String = withContext(Dispatchers.IO) {
        when {
            hasShizuku -> executeViaShizuku(command)
            hasRoot -> executeViaRoot(command)
            else -> executeViaRuntime(command)
        }
    }

    override suspend fun execute(commands: List<String>, separator: String): List<String> {
        val combined = commands.joinToString(" && echo $separator && ")
        val result = execute(combined)
        return result.split(separator).map { it.trim() }
    }

    private fun executeViaShizuku(command: String): String {
        return try {
            val process = Shizuku.newProcess(arrayOf("sh", "-c", command), null, null)
            val output = process.inputStream.bufferedReader().readText()
            val error = process.errorStream.bufferedReader().readText()
            process.waitFor()
            if (error.isNotBlank() && output.isBlank()) error.trim() else output.trim()
        } catch (e: Exception) {
            throw RuntimeException("Shizuku 执行失败: ${e.message}")
        }
    }

    private fun executeViaRoot(command: String): String {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            val output = process.inputStream.bufferedReader().readText()
            val error = process.errorStream.bufferedReader().readText()
            process.waitFor()
            if (error.isNotBlank() && output.isBlank()) error.trim() else output.trim()
        } catch (e: Exception) {
            throw RuntimeException("Root 执行失败: ${e.message}")
        }
    }

    private fun executeViaRuntime(command: String): String {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val output = process.inputStream.bufferedReader().readText()
            val error = process.errorStream.bufferedReader().readText()
            process.waitFor()
            if (error.isNotBlank() && output.isBlank()) error.trim() else output.trim()
        } catch (e: Exception) {
            throw RuntimeException("命令执行失败: ${e.message}")
        }
    }

    /**
     * Check if Shizuku permission is granted.
     */
    fun checkShizukuPermission(): Boolean {
        return try {
            if (!Shizuku.pingBinder()) return false
            Shizuku.checkSelfPermission() == 0
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Request Shizuku permission.
     */
    fun requestShizukuPermission() {
        try {
            Shizuku.requestPermission(0)
        } catch (_: Exception) {}
    }
}
