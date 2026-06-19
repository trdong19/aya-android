package io.liriliri.aya.adb

/**
 * Represents a parsed ADB protocol message.
 */
data class AdbMessage(
    val command: UInt,
    val arg0: Int,
    val arg1: Int,
    val dataLength: Int,
    val checksum: Int,
    val magic: UInt,
    val data: ByteArray? = null
) {
    val isConnect get() = command == AdbProtocol.CMD_CNXN
    val isAuth get() = command == AdbProtocol.CMD_AUTH
    val isOpen get() = command == AdbProtocol.CMD_OPEN
    val isOkay get() = command == AdbProtocol.CMD_OKAY
    val isWrite get() = command == AdbProtocol.CMD_WRTE
    val isClose get() = command == AdbProtocol.CMD_CLSE

    val commandString: String
        get() = when (command) {
            AdbProtocol.CMD_CNXN -> "CNXN"
            AdbProtocol.CMD_AUTH -> "AUTH"
            AdbProtocol.CMD_OPEN -> "OPEN"
            AdbProtocol.CMD_OKAY -> "OKAY"
            AdbProtocol.CMD_WRTE -> "WRTE"
            AdbProtocol.CMD_CLSE -> "CLSE"
            else -> "UNKNOWN(0x${command.toString(16)})"
        }

    override fun toString(): String {
        return "AdbMessage($commandString, arg0=$arg0, arg1=$arg1, len=$dataLength)"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AdbMessage) return false
        return command == other.command && arg0 == other.arg0 && arg1 == other.arg1
    }

    override fun hashCode(): Int {
        var result = command.hashCode()
        result = 31 * result + arg0
        result = 31 * result + arg1
        return result
    }
}
