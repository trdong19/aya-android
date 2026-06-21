package io.liriliri.aya.adb

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * ADB connection to a remote device over TCP.
 * Implements the full ADB protocol including AUTH handshake.
 */
class AdbConnection(
    private val host: String,
    private val port: Int = 5555,
    private val crypto: AdbCrypto
) {
    companion object {
        private const val TAG = "AdbConnection"
        private const val CONNECT_TIMEOUT = 15_000
        private const val READ_TIMEOUT = 120_000  // 2 minutes - batch commands can be slow
    }

    private var socket: Socket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null
    private var readJob: Job? = null

    private val streams = ConcurrentHashMap<Int, AdbStream>()
    private val nextLocalId = AtomicInteger(1)
    private val pendingOpen = ConcurrentHashMap<Int, CompletableDeferred<AdbStream>>()

    @Volatile
    var isConnected = false; private set

    @Volatile
    private var isRunning = true

    @Volatile
    private var authAttempts = 0

    @Volatile
    var deviceBanner: String = ""; private set

    private val _events = MutableSharedFlow<AdbEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<AdbEvent> = _events.asSharedFlow()

    /**
     * Connect to the device and perform AUTH handshake.
     */
    suspend fun connect() = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Connecting to $host:$port")
            val sock = Socket()
            sock.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT)
            sock.soTimeout = READ_TIMEOUT
            sock.tcpNoDelay = true
            sock.keepAlive = true

            socket = sock
            inputStream = sock.getInputStream()
            outputStream = sock.getOutputStream()
            isRunning = true

            // Start reading messages
            readJob = CoroutineScope(Dispatchers.IO).launch {
                readLoop()
            }

            // Small delay to ensure readLoop is ready
            delay(50)

            // Send CNXN
            Log.d(TAG, "Sending CNXN message")
            sendMessage(AdbProtocol.connect())

            // Wait for connection to be established (CNXN response)
            val deadline = System.currentTimeMillis() + CONNECT_TIMEOUT
            while (!isConnected && isRunning && System.currentTimeMillis() < deadline) {
                delay(100)
            }
            if (!isConnected) {
                throw IOException("连接超时 - 未收到CNXN响应 ($host:$port)")
            }

            Log.d(TAG, "Connected to device: $deviceBanner")
        } catch (e: Exception) {
            disconnect()
            throw e
        }
    }

    /**
     * Disconnect from the device.
     */
    fun disconnect() {
        isConnected = false
        isRunning = false
        readJob?.cancel()
        try {
            inputStream?.close()
            outputStream?.close()
            socket?.close()
        } catch (_: Exception) {}
        streams.values.forEach { it.onClose() }
        streams.clear()
        pendingOpen.values.forEach { it.completeExceptionally(IOException("Disconnected")) }
        pendingOpen.clear()
    }

    /**
     * Open a stream to a destination (e.g., "shell:", "tcp:1234", etc.).
     */
    suspend fun open(destination: String): AdbStream {
        if (!isConnected) throw IOException("Not connected")

        val localId = nextLocalId.getAndIncrement()
        val deferred = CompletableDeferred<AdbStream>()
        pendingOpen[localId] = deferred

        Log.d(TAG, "Opening stream: localId=$localId, dest=$destination")
        sendMessage(AdbProtocol.open(localId, destination))

        // Wait for OKAY response. No hard timeout — the socket's read timeout (30s)
        // and the readLoop will handle disconnection detection.
        // Use coroutine cancellation to avoid hanging forever if connection drops.
        return try {
            deferred.await()
        } catch (e: Exception) {
            pendingOpen.remove(localId)
            throw IOException("Stream open failed: ${e.message}")
        }
    }

    /**
     * Execute a shell command and return the output as a string.
     */
    suspend fun shell(command: String): String {
        Log.d(TAG, "shell: opening stream for: ${command.take(80)}")
        val stream = open("shell:$command")
        Log.d(TAG, "shell: stream opened, collecting output...")
        val output = ByteArrayOutputStream()

        try {
            // Collect output until stream closes
            // Channel flow completes with ClosedReceiveChannelException when channel is closed
            stream.output.collect { data ->
                if (data.isEmpty()) {
                    // End signal — channel is closing
                    return@collect
                }
                output.write(data)
            }
        } catch (_: Exception) {
            // Expected: ClosedReceiveChannelException or CancellationException when stream closes
        }

        val result = output.toString().trimEnd()
        Log.d(TAG, "shell: done, output length=${result.length}")
        return result
    }

    /**
     * Execute multiple shell commands separated by a delimiter.
     */
    suspend fun shell(commands: List<String>, separator: String = "aya_separator"): List<String> {
        // Use ; instead of && so failed commands don't block subsequent ones
        val combined = commands.joinToString(" ; echo $separator ; ")
        val result = shell(combined)
        return result.split(separator).map { it.trim() }
    }

    /**
     * Send a message to the device.
     */
    internal suspend fun sendMessage(data: ByteArray) = withContext(Dispatchers.IO) {
        val out = outputStream ?: throw IOException("Not connected")
        synchronized(out) {
            out.write(data)
            out.flush()
        }
    }

    private suspend fun readLoop() {
        val input = inputStream ?: return
        val headerBuf = ByteArray(AdbProtocol.HEADER_SIZE)

        Log.d(TAG, "Read loop started")

        try {
            while (isRunning) {
                // Read header
                readFully(input, headerBuf)
                val msg = AdbProtocol.parseHeader(headerBuf)

                Log.d(TAG, "Received: ${msg.commandString} arg0=${msg.arg0} arg1=${msg.arg1} dataLen=${msg.dataLength}")

                if (!AdbProtocol.validate(msg)) {
                    Log.e(TAG, "Invalid message (magic mismatch): $msg")
                    break
                }

                // Read data payload if present
                val data = if (msg.dataLength > 0) {
                    val buf = ByteArray(msg.dataLength)
                    readFully(input, buf)
                    buf
                } else null

                handleMessage(AdbMessage(
                    command = msg.command,
                    arg0 = msg.arg0,
                    arg1 = msg.arg1,
                    dataLength = msg.dataLength,
                    checksum = msg.checksum,
                    magic = msg.magic,
                    data = data
                ))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Read loop error: ${e.message}")
        } finally {
            Log.d(TAG, "Read loop ended")
            isConnected = false
            // Fail all pending stream opens so they don't hang forever
            pendingOpen.values.forEach { it.completeExceptionally(IOException("Connection closed")) }
            pendingOpen.clear()
            _events.tryEmit(AdbEvent.Disconnected)
        }
    }

    private suspend fun handleMessage(msg: AdbMessage) {
        when {
            msg.isConnect -> {
                deviceBanner = msg.data?.let { String(it).trimEnd(' ') } ?: ""
                isConnected = true
                Log.d(TAG, "CNXN received: $deviceBanner")
                _events.emit(AdbEvent.Connected(deviceBanner))
            }

            msg.isAuth -> {
                when (msg.arg0) {
                    AdbProtocol.AUTH_TYPE_TOKEN -> {
                        // Device sent a token, sign it with our private key
                        val token = msg.data ?: return
                        Log.d(TAG, "AUTH_TOKEN received, tokenLen=${token.size}, authAttempts=$authAttempts")

                        if (authAttempts == 0) {
                            // First attempt: sign with our private key
                            val signed = crypto.signPayload(token)
                            authAttempts++
                            Log.d(TAG, "Sending AUTH_SIGNATURE, sigLen=${signed.size}")
                            sendMessage(AdbProtocol.auth(AdbProtocol.AUTH_TYPE_SIGNATURE, signed))
                        } else {
                            // Second attempt: device doesn't know our key, send public key
                            val publicKey = crypto.getAdbPublicKeyPayload()
                            authAttempts++
                            Log.d(TAG, "Sending AUTH_RSA_PUBLIC, keyLen=${publicKey.size}")
                            sendMessage(AdbProtocol.auth(AdbProtocol.AUTH_TYPE_RSA_PUBLIC, publicKey))
                        }
                    }
                    AdbProtocol.AUTH_TYPE_SIGNATURE -> {
                        Log.w(TAG, "Unexpected AUTH_SIGNATURE from device")
                    }
                }
            }

            msg.isOpen -> {
                // Device opened a stream in response to our OPEN
                val localId = msg.arg1
                val remoteId = msg.arg0
                val stream = AdbStream(localId, remoteId, this)
                streams[localId] = stream

                // Send OKAY to acknowledge
                sendMessage(AdbProtocol.okay(localId, remoteId))

                // Resolve the pending open
                pendingOpen.remove(localId)?.complete(stream)

                Log.d(TAG, "Stream opened: localId=$localId, remoteId=$remoteId")
                _events.emit(AdbEvent.StreamOpened(stream))
            }

            msg.isOkay -> {
                // ADB protocol: arg0 = sender's local id, arg1 = sender's remote id (= our local id)
                // For OPEN response: arg0 = device's new local id (our remote), arg1 = our local id
                // For WRTE ACK: arg0 = device's local id, arg1 = our local id

                // Check if this is an OPEN response (we have a pending open for arg1 = our local id)
                val ourLocalId = msg.arg1
                val pending = pendingOpen[ourLocalId]
                if (pending != null) {
                    val remoteId = msg.arg0  // Device's local id = our remote id
                    val stream = AdbStream(ourLocalId, remoteId, this)
                    streams[ourLocalId] = stream
                    pendingOpen.remove(ourLocalId)
                    stream.onReady()
                    // MUST complete the deferred that open() is awaiting!
                    pending.complete(stream)
                    Log.d(TAG, "Stream opened (OKAY): localId=$ourLocalId, remoteId=$remoteId")
                    _events.emit(AdbEvent.StreamOpened(stream))
                } else {
                    // This is a WRTE ACK — use arg1 (our local id) to find the stream
                    streams[ourLocalId]?.onReady()
                }
            }

            msg.isWrite -> {
                // arg0 = sender's local id (remote id for us), arg1 = our local id
                val localId = msg.arg1  // Our local id, used as key in streams map
                val stream = streams[localId]
                if (stream != null && msg.data != null) {
                    stream.onData(msg.data)
                } else {
                    // Stream not found, send CLSE back
                    Log.w(TAG, "WRTE for unknown stream: ourLocalId=$localId, remoteId=${msg.arg0}")
                    if (msg.data != null) {
                        sendMessage(AdbProtocol.close(localId, msg.arg0))
                    }
                }
            }

            msg.isClose -> {
                // arg0 = sender's local id (remote id for us), arg1 = our local id
                val localId = msg.arg1  // Our local id
                val stream = streams.remove(localId)
                stream?.onClose()
                Log.d(TAG, "Stream closed: localId=$localId")
                _events.emit(AdbEvent.StreamClosed(localId))
            }

            else -> {
                Log.w(TAG, "Unknown command: ${msg.commandString}")
            }
        }
    }

    private fun readFully(input: InputStream, buffer: ByteArray) {
        var offset = 0
        while (offset < buffer.size) {
            val read = input.read(buffer, offset, buffer.size - offset)
            if (read < 0) throw IOException("End of stream")
            offset += read
        }
    }
}

/**
 * Events emitted by an ADB connection.
 */
sealed class AdbEvent {
    data class Connected(val banner: String) : AdbEvent()
    data object Disconnected : AdbEvent()
    data class StreamOpened(val stream: AdbStream) : AdbEvent()
    data class StreamClosed(val localId: Int) : AdbEvent()
}
