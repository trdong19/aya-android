package io.liriliri.aya.util

import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import rikka.shizuku.Shizuku

/**
 * Helper for executing shell commands via Shizuku (runs as shell UID).
 */
object ShizukuHelper {
    private const val TAG = "ShizukuHelper"
    private const val REQUEST_CODE = 1001

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
     * Request Shizuku permission.
     */
    fun requestPermission() {
        try {
            Shizuku.requestPermission(REQUEST_CODE)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to request Shizuku permission: ${e.message}")
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
            val process = Shizuku.newProcess(arrayOf("sh", "-c", command), null, null)
            val exitCode = process.waitFor()
            Log.d(TAG, "Shizuku exec '$command' -> exitCode=$exitCode")
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
            val process = Shizuku.newProcess(arrayOf("sh", "-c", command), null, null)
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()
            output.trim()
        } catch (e: Exception) {
            Log.w(TAG, "Shizuku execWithOutput failed: ${e.message}")
            null
        }
    }
}
