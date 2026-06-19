package io.liriliri.aya.adb

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * ADB protocol message types and constants.
 * Based on the ADB protocol specification.
 */
object AdbProtocol {
    // Command constants (little-endian)
    const val CMD_CNXN = 0x4e584e43u  // Connect
    const val CMD_AUTH = 0x48545541u  // Authenticate
    const val CMD_OPEN = 0x4e45504fu  // Open stream
    const val CMD_OKAY = 0x59414b4fu  // Ready/ack
    const val CMD_WRTE = 0x45545257u  // Write
    const val CMD_CLSE = 0x45534c43u  // Close

    // AUTH types
    const val AUTH_TYPE_TOKEN = 1
    const val AUTH_TYPE_SIGNATURE = 2
    const val AUTH_TYPE_RSA_PUBLIC = 3

    // ADB version and max data
    const val ADB_VERSION = 0x01000001
    const val MAX_DATA = 1024 * 1024  // 1MB

    // System identity string
    const val SYSTEM_IDENTITY_STRING = "device::"

    /**
     * ADB message header size: 6 fields × 4 bytes = 24 bytes
     */
    const val HEADER_SIZE = 24

    /**
     * Encode an ADB message to bytes.
     */
    fun encodeMessage(
        command: UInt,
        arg0: Int,
        arg1: Int,
        data: ByteArray? = null
    ): ByteArray {
        val dataLength = data?.size ?: 0
        val buffer = ByteBuffer.allocate(HEADER_SIZE + dataLength).order(ByteOrder.LITTLE_ENDIAN)

        // command
        buffer.putInt(command.toInt())
        // arg0
        buffer.putInt(arg0)
        // arg1
        buffer.putInt(arg1)
        // length
        buffer.putInt(dataLength)
        // data checksum (sum of all bytes in data)
        buffer.putInt(checksum(data))
        // magic (command ^ 0xffffffff)
        buffer.putInt((command xor 0xffffffffu).toInt())

        if (data != null) {
            buffer.put(data)
        }

        return buffer.array()
    }

    /**
     * Parse an ADB message header from bytes.
     */
    fun parseHeader(headerBytes: ByteArray): AdbMessage {
        val buffer = ByteBuffer.wrap(headerBytes).order(ByteOrder.LITTLE_ENDIAN)
        return AdbMessage(
            command = buffer.getInt().toUInt(),
            arg0 = buffer.getInt(),
            arg1 = buffer.getInt(),
            dataLength = buffer.getInt(),
            checksum = buffer.getInt(),
            magic = buffer.getInt().toUInt()
        )
    }

    /**
     * Validate a parsed message header.
     */
    fun validate(msg: AdbMessage): Boolean {
        if (msg.dataLength < 0 || msg.dataLength > MAX_DATA) return false
        if ((msg.command xor 0xffffffffu) != msg.magic) return false
        return true
    }

    /**
     * Create a CNXN (connect) message.
     */
    fun connect(version: Int = ADB_VERSION, maxData: Int = MAX_DATA): ByteArray {
        val data = SYSTEM_IDENTITY_STRING.toByteArray()
        return encodeMessage(CMD_CNXN, version, maxData, data)
    }

    /**
     * Create an AUTH message.
     */
    fun auth(type: Int, data: ByteArray): ByteArray {
        return encodeMessage(CMD_AUTH, type, 0, data)
    }

    /**
     * Create an OPEN message.
     */
    fun open(localId: Int, destination: String): ByteArray {
        val data = destination.toByteArray() + byteArrayOf(0) // null-terminated
        return encodeMessage(CMD_OPEN, localId, 0, data)
    }

    /**
     * Create a WRTE message.
     */
    fun write(localId: Int, remoteId: Int, data: ByteArray): ByteArray {
        return encodeMessage(CMD_WRTE, localId, remoteId, data)
    }

    /**
     * Create an OKAY message.
     */
    fun okay(localId: Int, remoteId: Int): ByteArray {
        return encodeMessage(CMD_OKAY, localId, remoteId)
    }

    /**
     * Create a CLSE message.
     */
    fun close(localId: Int, remoteId: Int): ByteArray {
        return encodeMessage(CMD_CLSE, localId, remoteId)
    }

    private fun checksum(data: ByteArray?): Int {
        if (data == null) return 0
        var sum = 0
        for (b in data) {
            sum += b.toInt() and 0xff
        }
        return sum
    }
}
