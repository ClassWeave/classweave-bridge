package com.classweave.bridge.bluetooth

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.classweave.bridge.model.AppLog
import com.classweave.bridge.model.LogLevel
import com.classweave.bridge.protocol.Envelope
import com.classweave.bridge.protocol.EnvelopeCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

enum class RfcommHubState {
    IDLE, LISTENING, ERROR
}

sealed class RfcommEvent {
    data class Connected(val endpointId: String, val endpointName: String) : RfcommEvent()
    data class Disconnected(val endpointId: String) : RfcommEvent()
    data class MessageReceived(val endpointId: String, val envelope: Envelope) : RfcommEvent()
}

/**
 * Bluetooth RFCOMM server hub for the Bridge.
 *
 * Protocol: length-prefixed frames.
 *   [4 bytes big-endian length] [UTF-8 JSON payload]
 *
 * Each connected client gets a dedicated read loop coroutine.
 */
class RfcommHub(
    private val context: Context,
    private val localName: String = "ClassWeave-Bridge",
    private val onLog: (AppLog) -> Unit
) {
    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("0000c1a5-5e4e-e000-b1d9-e00000000001")
        private const val SERVICE_NAME = "ClassWeave-Bridge"
        private const val MAX_FRAME_SIZE = 1_048_576 // 1 MB
    }

    private val btAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()

    private val _state = MutableStateFlow(RfcommHubState.IDLE)
    val state: StateFlow<RfcommHubState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<RfcommEvent>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events: SharedFlow<RfcommEvent> = _events.asSharedFlow()

    private val connectedClients = ConcurrentHashMap<String, ClientConnection>()
    private var serverSocket: BluetoothServerSocket? = null
    private var acceptJob: Job? = null
    private var scope: CoroutineScope? = null

    private data class ClientConnection(
        val socket: BluetoothSocket,
        val name: String,
        val outputStream: OutputStream,
        val readJob: Job
    )

    fun startListening(scope: CoroutineScope) {
        if (_state.value == RfcommHubState.LISTENING) return

        this.scope = scope
        val adapter = btAdapter
        if (adapter == null || !adapter.isEnabled) {
            _state.value = RfcommHubState.ERROR
            log("Bluetooth not available or disabled", LogLevel.ERROR)
            return
        }

        if (!hasPermissions()) {
            _state.value = RfcommHubState.ERROR
            log("Missing Bluetooth permissions", LogLevel.ERROR)
            return
        }

        @Suppress("MissingPermission")
        val oldName = adapter.name
        if (oldName != localName) {
            @Suppress("MissingPermission")
            adapter.name = localName
            log("BT name changed: $oldName → $localName")
        }

        try {
            @Suppress("MissingPermission")
            serverSocket = adapter.listenUsingInsecureRfcommWithServiceRecord(SERVICE_NAME, SERVICE_UUID)
            _state.value = RfcommHubState.LISTENING
            @Suppress("MissingPermission")
            val scanMode = adapter.scanMode
            val scanModeStr = when (scanMode) {
                BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE -> "DISCOVERABLE"
                BluetoothAdapter.SCAN_MODE_CONNECTABLE -> "CONNECTABLE (not discoverable!)"
                BluetoothAdapter.SCAN_MODE_NONE -> "NONE"
                else -> "UNKNOWN($scanMode)"
            }
            log("RFCOMM server listening (name=$localName, UUID=$SERVICE_UUID, scanMode=$scanModeStr)", LogLevel.SUCCESS)
        } catch (e: IOException) {
            _state.value = RfcommHubState.ERROR
            log("Failed to create server socket: ${e.message}", LogLevel.ERROR)
            return
        }

        acceptJob = scope.launch(Dispatchers.IO) {
            acceptLoop()
        }
    }

    fun stopListening() {
        acceptJob?.cancel()
        acceptJob = null
        try { serverSocket?.close() } catch (_: IOException) {}
        serverSocket = null

        for ((id, conn) in connectedClients) {
            conn.readJob.cancel()
            try { conn.socket.close() } catch (_: IOException) {}
        }
        connectedClients.clear()
        _state.value = RfcommHubState.IDLE
        log("RFCOMM server stopped")
    }

    fun sendTo(endpointId: String, envelope: Envelope) {
        val conn = connectedClients[endpointId] ?: return
        scope?.launch(Dispatchers.IO) {
            try {
                writeFrame(conn.outputStream, EnvelopeCodec.encode(envelope))
            } catch (e: IOException) {
                log("Send to $endpointId failed: ${e.message}", LogLevel.WARN)
                removeClient(endpointId)
            }
        }
    }

    fun broadcastAll(envelope: Envelope) {
        val ids = connectedClients.keys.toList()
        if (ids.isEmpty()) return
        val json = EnvelopeCodec.encode(envelope)
        scope?.launch(Dispatchers.IO) {
            for (id in ids) {
                val conn = connectedClients[id] ?: continue
                try {
                    writeFrame(conn.outputStream, json)
                } catch (e: IOException) {
                    log("Broadcast to $id failed: ${e.message}", LogLevel.WARN)
                    removeClient(id)
                }
            }
        }
    }

    fun connectedCount(): Int = connectedClients.size

    fun stop() {
        stopListening()
        log("RfcommHub stopped")
    }

    // ── Internal ──

    @Suppress("MissingPermission")
    private suspend fun acceptLoop() {
        val ss = serverSocket ?: return
        while (scope?.isActive == true) {
            try {
                val socket = withContext(Dispatchers.IO) { ss.accept() }
                val device = socket.remoteDevice
                val deviceName = try { device.name ?: device.address } catch (_: SecurityException) { device.address }
                val endpointId = device.address

                log("Client connected: $endpointId ($deviceName)")

                val readJob = scope!!.launch(Dispatchers.IO) {
                    readLoop(endpointId, socket.inputStream)
                }

                connectedClients[endpointId] = ClientConnection(
                    socket = socket,
                    name = deviceName,
                    outputStream = socket.outputStream,
                    readJob = readJob
                )

                _events.tryEmit(RfcommEvent.Connected(endpointId, deviceName))
            } catch (e: IOException) {
                if (scope?.isActive == true) {
                    log("Accept error: ${e.message}", LogLevel.WARN)
                }
                break
            }
        }
    }

    private suspend fun readLoop(endpointId: String, input: InputStream) {
        val lenBuf = ByteArray(4)
        try {
            while (scope?.isActive == true) {
                readFully(input, lenBuf)
                val len = ByteBuffer.wrap(lenBuf).order(ByteOrder.BIG_ENDIAN).getInt()
                if (len <= 0 || len > MAX_FRAME_SIZE) {
                    log("Invalid frame length $len from $endpointId, dropping connection", LogLevel.ERROR)
                    break
                }
                val data = ByteArray(len)
                readFully(input, data)
                val json = String(data, Charsets.UTF_8)
                try {
                    val envelope = EnvelopeCodec.decode(json)
                    log("RX ← BT [$endpointId] ${envelope.domain.name}.${envelope.action}")
                    _events.tryEmit(RfcommEvent.MessageReceived(endpointId, envelope))
                } catch (e: Exception) {
                    log("Decode error from $endpointId: ${e.message}", LogLevel.ERROR)
                }
            }
        } catch (e: IOException) {
            if (scope?.isActive == true) {
                log("Read error from $endpointId: ${e.message}")
            }
        } finally {
            removeClient(endpointId)
        }
    }

    private fun readFully(input: InputStream, buf: ByteArray) {
        var offset = 0
        while (offset < buf.size) {
            val n = input.read(buf, offset, buf.size - offset)
            if (n < 0) throw IOException("EOF")
            offset += n
        }
    }

    @Synchronized
    private fun writeFrame(output: OutputStream, json: String) {
        val payload = json.toByteArray(Charsets.UTF_8)
        val lenBytes = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(payload.size).array()
        output.write(lenBytes)
        output.write(payload)
        output.flush()
    }

    private fun removeClient(endpointId: String) {
        val conn = connectedClients.remove(endpointId) ?: return
        conn.readJob.cancel()
        try { conn.socket.close() } catch (_: IOException) {}
        log("Client disconnected: $endpointId (${conn.name}) — remaining=${connectedClients.size}")
        _events.tryEmit(RfcommEvent.Disconnected(endpointId))
    }

    private fun hasPermissions(): Boolean {
        if (Build.VERSION.SDK_INT >= 31) {
            return ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        }
        return true
    }

    private fun log(message: String, level: LogLevel = LogLevel.INFO) {
        onLog(AppLog(tag = "BT-RFCOMM", message = message, level = level))
    }
}
