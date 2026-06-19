package io.liriliri.aya.adb

/**
 * Executes shell commands on a remote device via ADB connection.
 */
class AdbCommandExecutor(private val connection: AdbConnection) : CommandExecutor {
    override suspend fun execute(command: String): String {
        return connection.shell(command)
    }

    override suspend fun execute(commands: List<String>, separator: String): List<String> {
        return connection.shell(commands, separator)
    }
}
