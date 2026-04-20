package com.classweave.bridge.core

import com.classweave.bridge.bluetooth.RfcommEvent
import com.classweave.bridge.bluetooth.RfcommHub
import com.classweave.bridge.bluetooth.RfcommHubState
import com.classweave.bridge.model.AppLog
import com.classweave.bridge.model.LogLevel
import com.classweave.bridge.protocol.AndroidDeviceInfo
import com.classweave.bridge.protocol.BridgeIdentity
import com.classweave.bridge.protocol.Domain
import com.classweave.bridge.protocol.Envelope
import com.classweave.bridge.protocol.MessageFactory
import com.classweave.bridge.usb.UsbConnectionState
import com.classweave.bridge.usb.UsbTransportClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class BridgeSessionState {
    DISCONNECTED, HELLO_SENT, ACTIVE
}

class BridgeSessionManager(
    private val usbTransport: UsbTransportClient,
    private val forwarder: Forwarder,
    private val endpointRegistry: EndpointRegistry,
    private val identity: BridgeIdentity,
    private val onLog: (AppLog) -> Unit
) {
    var rfcommHub: RfcommHub? = null

    private val _sessionState = MutableStateFlow(BridgeSessionState.DISCONNECTED)
    val sessionState: StateFlow<BridgeSessionState> = _sessionState.asStateFlow()

    private val _sessionId = MutableStateFlow<String?>(null)
    val sessionId: StateFlow<String?> = _sessionId.asStateFlow()

    private var scope: CoroutineScope? = null
    private var heartbeatJob: Job? = null
    private var statusJob: Job? = null
    private var inboundJob: Job? = null
    private var connectionWatchJob: Job? = null
    private var rfcommEventJob: Job? = null

    fun start(scope: CoroutineScope) {
        this.scope = scope

        connectionWatchJob = scope.launch {
            usbTransport.connectionState.collect { state ->
                when (state) {
                    UsbConnectionState.CONNECTED -> onUsbConnected()
                    UsbConnectionState.DISCONNECTED,
                    UsbConnectionState.RECONNECTING -> onUsbDisconnected()
                    UsbConnectionState.CONNECTING -> { /* waiting */ }
                }
            }
        }

        inboundJob = scope.launch {
            usbTransport.inbound.collect { envelope ->
                handleInbound(envelope)
            }
        }

        startRfcommEventCollection(scope)
    }

    fun stop() {
        stopTimers()
        connectionWatchJob?.cancel()
        inboundJob?.cancel()
        rfcommEventJob?.cancel()
        connectionWatchJob = null
        inboundJob = null
        rfcommEventJob = null
        scope = null
        _sessionState.value = BridgeSessionState.DISCONNECTED
        _sessionId.value = null
    }

    // ── RFCOMM event handling ─────────────────────────────────────────

    private fun startRfcommEventCollection(scope: CoroutineScope) {
        val hub = rfcommHub ?: return
        rfcommEventJob = scope.launch {
            hub.events.collect { event ->
                when (event) {
                    is RfcommEvent.Connected -> {
                        log("Android BT client connected: ${event.endpointId} (${event.endpointName})")
                    }
                    is RfcommEvent.Disconnected -> {
                        val ep = endpointRegistry.findByEndpointId(event.endpointId)
                        if (ep != null) {
                            endpointRegistry.unregisterByEndpointId(event.endpointId)
                            log("Android device removed: ${ep.deviceId}")
                        }
                    }
                    is RfcommEvent.MessageReceived -> {
                        handleRfcommMessage(event.endpointId, event.envelope)
                    }
                }
            }
        }
    }

    private suspend fun handleRfcommMessage(endpointId: String, envelope: Envelope) {
        val deviceId = envelope.origin?.deviceId
        if (deviceId != null && endpointRegistry.findByDeviceId(deviceId) == null) {
            val name = envelope.origin?.deviceId ?: endpointId
            endpointRegistry.register(deviceId, name, endpointId)
            log("Registered Android device: $deviceId ↔ $endpointId")
        }

        if (envelope.domain == Domain.SYSTEM && envelope.action == "heartbeat.ping") {
            val sid = _sessionId.value
            if (sid != null && deviceId != null) {
                val pong = MessageFactory.heartbeatPong(
                    identity = identity,
                    sessionId = sid,
                    targetDeviceId = deviceId
                )
                rfcommHub?.sendTo(endpointId, pong)
            }
            return
        }

        if (envelope.domain == Domain.SYSTEM && envelope.action == "session.hello") {
            val sid = _sessionId.value
            if (sid != null) {
                log("Client $endpointId sent session.hello, replying with welcome (sessionId=$sid)")
                val welcome = MessageFactory.sessionWelcome(
                    identity = identity,
                    sessionId = sid,
                    targetDeviceId = deviceId ?: endpointId
                )
                rfcommHub?.sendTo(endpointId, welcome)
            } else {
                log("Client $endpointId sent session.hello but Bridge has no active session yet — welcome deferred", LogLevel.WARN)
            }
        }

        forwarder.forwardUpstream(envelope)
    }

    // ── USB inbound handling ──────────────────────────────────────────

    private fun handleInbound(envelope: Envelope) {
        if (envelope.domain == Domain.SYSTEM) {
            when (envelope.action) {
                "session.welcome" -> {
                    if (_sessionState.value != BridgeSessionState.ACTIVE) {
                        val sid = envelope.sessionId
                        _sessionId.value = sid
                        _sessionState.value = BridgeSessionState.ACTIVE
                        log("Session active: $sid", LogLevel.SUCCESS)
                        startTimers()
                    }
                    forwarder.forwardDownstream(envelope)
                }
                "heartbeat.pong" -> {
                    log("Heartbeat pong received")
                    forwarder.forwardDownstream(envelope)
                }
                else -> {
                    forwarder.forwardDownstream(envelope)
                }
            }
        } else {
            forwarder.forwardDownstream(envelope)
        }
    }

    // ── USB lifecycle ─────────────────────────────────────────────────

    private suspend fun onUsbConnected() {
        log("USB connected, sending session.hello")
        _sessionState.value = BridgeSessionState.HELLO_SENT
        try {
            usbTransport.send(MessageFactory.sessionHello(identity))
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            log("Failed to send session.hello: ${e.message}", LogLevel.ERROR)
        }
    }

    private fun onUsbDisconnected() {
        stopTimers()
        _sessionState.value = BridgeSessionState.DISCONNECTED
        _sessionId.value = null
        log("USB disconnected, session reset")
    }

    // ── Timers ────────────────────────────────────────────────────────

    private fun startTimers() {
        stopTimers()
        val s = scope ?: return

        heartbeatJob = s.launch {
            while (true) {
                delay(HEARTBEAT_INTERVAL_MS)
                val sid = _sessionId.value ?: break
                try {
                    usbTransport.send(MessageFactory.heartbeatPing(identity, sid))
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    log("Heartbeat send failed: ${e.message}", LogLevel.WARN)
                }
            }
        }

        statusJob = s.launch {
            while (true) {
                delay(STATUS_INTERVAL_MS)
                val sid = _sessionId.value ?: break
                try {
                    val devices = endpointRegistry.allDevices().map { ep ->
                        AndroidDeviceInfo(
                            deviceId = ep.deviceId,
                            name = ep.name,
                            online = ep.online
                        )
                    }
                    val hub = rfcommHub
                    val statusEnvelope = MessageFactory.bridgeStatus(
                        identity = identity,
                        sessionId = sid,
                        usbConnected = usbTransport.connectionState.value == UsbConnectionState.CONNECTED,
                        nearbyAdvertising = hub?.state?.value == RfcommHubState.LISTENING,
                        androidDevices = devices
                    )
                    usbTransport.send(statusEnvelope)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    log("Status send failed: ${e.message}", LogLevel.WARN)
                }
            }
        }
    }

    private fun stopTimers() {
        heartbeatJob?.cancel()
        statusJob?.cancel()
        heartbeatJob = null
        statusJob = null
    }

    private fun log(message: String, level: LogLevel = LogLevel.INFO) {
        onLog(AppLog(tag = "SESSION", message = message, level = level))
    }

    companion object {
        private const val HEARTBEAT_INTERVAL_MS = 15_000L
        private const val STATUS_INTERVAL_MS = 30_000L
    }
}
