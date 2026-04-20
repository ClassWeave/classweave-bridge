package com.classweave.bridge.usb

import com.classweave.bridge.model.AppLog
import com.classweave.bridge.model.LogLevel
import com.classweave.bridge.protocol.Envelope
import com.classweave.bridge.protocol.EnvelopeCodec
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket

enum class UsbConnectionState {
    DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING
}

class UsbTransportClient(
    private val host: String = "127.0.0.1",
    private val port: Int = 9000,
    private val onLog: (AppLog) -> Unit
) {
    private val _connectionState = MutableStateFlow(UsbConnectionState.DISCONNECTED)
    val connectionState: StateFlow<UsbConnectionState> = _connectionState.asStateFlow()

    private val _inbound = MutableSharedFlow<Envelope>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val inbound: SharedFlow<Envelope> = _inbound.asSharedFlow()

    private val writeMutex = Mutex()
    private var writer: BufferedWriter? = null
    private var socket: Socket? = null
    private var connectJob: Job? = null

    private var backoffMs = INITIAL_BACKOFF_MS

    fun start(scope: CoroutineScope) {
        connectJob = scope.launch(Dispatchers.IO) {
            connectLoop()
        }
    }

    fun stop() {
        connectJob?.cancel()
        connectJob = null
        closeSocket()
        _connectionState.value = UsbConnectionState.DISCONNECTED
        backoffMs = INITIAL_BACKOFF_MS
    }

    suspend fun send(envelope: Envelope) {
        val w = writer
        if (w == null) {
            log("Cannot send: not connected", LogLevel.WARN)
            return
        }
        try {
            val line = EnvelopeCodec.encode(envelope)
            writeMutex.withLock {
                withContext(Dispatchers.IO) {
                    w.write(line)
                    w.write("\n")
                    w.flush()
                }
            }
            log("TX → ${envelope.domain.name}.${envelope.action} [${envelope.msgId.take(8)}]")
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            log("Send failed: ${e.message}", LogLevel.ERROR)
        }
    }

    private suspend fun connectLoop() {
        while (coroutineContext.isActive) {
            _connectionState.value = if (backoffMs > INITIAL_BACKOFF_MS)
                UsbConnectionState.RECONNECTING else UsbConnectionState.CONNECTING
            try {
                val s = Socket()
                s.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                socket = s
                writer = BufferedWriter(OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8))
                _connectionState.value = UsbConnectionState.CONNECTED
                backoffMs = INITIAL_BACKOFF_MS
                log("Connected to $host:$port", LogLevel.SUCCESS)
                readLoop(s)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("Connection failed: ${e.message}", LogLevel.WARN)
            }
            closeSocket()
            _connectionState.value = UsbConnectionState.RECONNECTING
            log("Reconnecting in ${backoffMs}ms...")
            delay(backoffMs)
            backoffMs = (backoffMs * BACKOFF_MULTIPLIER).toLong().coerceAtMost(MAX_BACKOFF_MS)
        }
    }

    private suspend fun readLoop(s: Socket) {
        val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
        while (coroutineContext.isActive && !s.isClosed) {
            val line = withContext(Dispatchers.IO) { reader.readLine() } ?: break
            if (line.isBlank()) continue
            try {
                val envelope = EnvelopeCodec.decode(line)
                log("RX ← ${envelope.domain.name}.${envelope.action} [${envelope.msgId.take(8)}]")
                _inbound.emit(envelope)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                log("Decode error: ${e.message}", LogLevel.ERROR)
            }
        }
    }

    private fun closeSocket() {
        try {
            writer = null
            socket?.close()
            socket = null
        } catch (_: Exception) {}
    }

    private fun log(message: String, level: LogLevel = LogLevel.INFO) {
        onLog(AppLog(tag = "USB", message = message, level = level))
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 5000
        private const val INITIAL_BACKOFF_MS = 1000L
        private const val MAX_BACKOFF_MS = 30000L
        private const val BACKOFF_MULTIPLIER = 2.0
    }
}
