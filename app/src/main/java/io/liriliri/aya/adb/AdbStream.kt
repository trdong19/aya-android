package io.liriliri.aya.adb

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import java.io.ByteArrayOutputStream

/**
 * Represents an open ADB stream (analogous to a shell session or socket connection).
 */
class AdbStream(
    val localId: Int,
    val remoteId: Int = 0,
    private val connection: AdbConnection
) {
    // Use Channel instead of SharedFlow to prevent readLoop from blocking on backpressure.
    // Channel never suspends the sender (trySend) and buffers data for the receiver.
    private val _channel = Channel<ByteArray>(Channel.UNLIMITED)
    val output: Flow<ByteArray> = _channel.consumeAsFlow()

    private val ready = CompletableDeferred<Unit>()
    @Volatile var isOpen = true; private set

    // Flow control: track pending writes and wait for OKAY
    private val writeAck = Channel<Unit>(Channel.UNLIMITED)
    private var pendingWrites = 0

    /**
     * Called by AdbConnection when OKAY is received for this stream.
     */
    fun onReady() {
        ready.complete(Unit)
        // Signal that a write was acknowledged
        writeAck.trySend(Unit)
    }

    /**
     * Wait until the stream is ready (OKAY received).
     */
    suspend fun waitReady() {
        ready.await()
    }

    /**
     * Called by AdbConnection when WRTE is received for this stream.
     */
    suspend fun onData(data: ByteArray) {
        // Send ACK (OKAY) back to the device
        connection.sendMessage(AdbProtocol.okay(localId, remoteId))
        // Use trySend (non-blocking) to prevent readLoop from suspending on backpressure
        _channel.trySend(data)
    }

    /**
     * Called by AdbConnection when CLSE is received for this stream.
     */
    fun onClose() {
        isOpen = false
        _channel.trySend(ByteArray(0)) // signal end
        _channel.close()
    }

    /**
     * Write data to this stream.
     */
    suspend fun write(data: ByteArray) {
        if (!isOpen) throw IllegalStateException("Stream is closed")
        ready.await()
        // Flow control: wait for ACK if too many pending writes
        if (pendingWrites >= 4) {
            writeAck.receive()
            pendingWrites--
        }
        pendingWrites++
        connection.sendMessage(AdbProtocol.write(localId, remoteId, data))
    }

    /**
     * Write a string to this stream.
     */
    suspend fun writeString(text: String) {
        write(text.toByteArray())
    }

    /**
     * Close this stream.
     */
    suspend fun close() {
        if (isOpen) {
            isOpen = false
            connection.sendMessage(AdbProtocol.close(localId, remoteId))
        }
    }
}
