package io.liriliri.aya.adb

/**
 * Abstraction for executing shell commands on a remote device.
 * This can use either the ADB protocol connection or Shizuku for local device.
 */
interface CommandExecutor {
    suspend fun execute(command: String): String
    suspend fun execute(commands: List<String>, separator: String = "aya_separator"): List<String>
}
