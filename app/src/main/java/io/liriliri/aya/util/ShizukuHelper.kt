package io.liriliri.aya.util

import android.content.pm.PackageManager
import android.util.Log
import rikka.shizuku.Shizuku

/**
 * Helper for executing shell commands via Shizuku (runs as shell UID).
 */
object ShizukuHelper {
    private const val TAG = "ShizukuHelper"

    // Cached reflection method for Shizuku.newProcess
    private var newProcessMethod: java.lang.reflect.Method? = null
    private var methodResolved = false

    /**
     * Check if Shizuku is installed and running.
     */
    fun isShizukuAvailable(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (e: Exception) {
            Log.d(TAG, "Shizuku not available: ${e.message}")
            false
        }
    }

    /**
     * Check if we have Shizuku permission.
     */
    fun hasPermission(): Boolean {
        return try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Get Shizuku UID (-1 if not available).
     */
    fun getUid(): Int {
        return try {
            if (isShizukuAvailable()) Shizuku.getUid() else -1
        } catch (e: Exception) {
            -1
        }
    }

    /**
     * Resolve Shizuku.newProcess via reflection (it's @hide but available at runtime).
     */
    private fun getNewProcessMethod(): java.lang.reflect.Method? {
        if (methodResolved) return newProcessMethod
        methodResolved = true
        return try {
            val method = Shizuku::class.java.getMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            newProcessMethod = method
            method
        } catch (e: Exception) {
            Log.w(TAG, "Shizuku.newProcess not available: ${e.message}")
            null
        }
    }

    /**
     * Execute a shell command via Shizuku.
     * Returns the exit code (-1 on error).
     */
    fun exec(command: String): Int {
        if (!isShizukuAvailable()) {
            Log.w(TAG, "Shizuku not available")
            return -1
        }
        if (!hasPermission()) {
            Log.w(TAG, "Shizuku permission not granted")
            return -1
        }
        return try {
            val method = getNewProcessMethod() ?: return -1
            val process = method.invoke(null, arrayOf("sh", "-c", command), null, null) as Process
            val exitCode = process.waitFor()
            Log.d(TAG, "Shizuku exec (exitCode=$exitCode): ${command.take(80)}")
            exitCode
        } catch (e: Exception) {
            Log.w(TAG, "Shizuku exec failed: ${e.message}")
            -1
        }
    }

    /**
     * Execute a shell command via Shizuku and return stdout output.
     */
    fun execWithOutput(command: String): String? {
        if (!isShizukuAvailable() || !hasPermission()) return null
        return try {
            val method = getNewProcessMethod() ?: return null
            val process = method.invoke(null, arrayOf("sh", "-c", command), null, null) as Process
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()
            output.trim()
        } catch (e: Exception) {
            Log.w(TAG, "Shizuku execWithOutput failed: ${e.message}")
            null
        }
    }
}
