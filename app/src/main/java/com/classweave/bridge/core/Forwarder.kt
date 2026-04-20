package com.classweave.bridge.core

import com.classweave.bridge.bluetooth.RfcommHub
import com.classweave.bridge.model.AppLog
import com.classweave.bridge.model.LogLevel
import com.classweave.bridge.protocol.BridgeIdentity
import com.classweave.bridge.protocol.Envelope
import com.classweave.bridge.protocol.Relay
import com.classweave.bridge.protocol.TargetType
import com.classweave.bridge.usb.UsbTransportClient

class Forwarder(
    private val usbTransport: UsbTransportClient,
    private val endpointRegistry: EndpointRegistry,
    private val identity: BridgeIdentity,
    private val onLog: (AppLog) -> Unit
) {
    var rfcommHub: RfcommHub? = null

    /**
     * Upstream: Android → Bridge → Host.
     * Preserves origin, rewrites sender to bridge, sets relay.
     */
    suspend fun forwardUpstream(envelope: Envelope) {
        val forwarded = envelope.copy(
            sender = identity.asParticipant(),
            relay = Relay(
                viaBridge = true,
                bridgeId = identity.bridgeId,
                hop = (envelope.relay?.hop ?: 0) + 1
            )
        )
        usbTransport.send(forwarded)
        log("↑ Upstream: ${envelope.domain.name}.${envelope.action} from ${envelope.origin?.deviceId ?: "?"}")
    }

    /**
     * Downstream: Host → Bridge → Android clients via Bluetooth RFCOMM.
     * Routes by target type.
     */
    fun forwardDownstream(envelope: Envelope) {
        val hub = rfcommHub
        val target = envelope.target ?: run {
            log("↓ Downstream: no target, dropping", LogLevel.WARN)
            return
        }

        when (target.type) {
            TargetType.ANDROID_CLIENTS, TargetType.ALL_CLIENTS -> {
                val count = endpointRegistry.onlineCount()
                log("↓ Broadcast ${envelope.domain.name}.${envelope.action} → $count android clients")
                hub?.broadcastAll(envelope)
            }

            TargetType.DEVICE -> {
                val deviceId = target.deviceId
                if (deviceId == null) {
                    log("↓ Device target without deviceId, dropping", LogLevel.WARN)
                    return
                }
                if (deviceId == identity.deviceId) {
                    val count = endpointRegistry.onlineCount()
                    log("↓ Bridge-targeted ${envelope.domain.name}.${envelope.action} → broadcast to $count android clients")
                    hub?.broadcastAll(envelope)
                } else {
                    val endpoint = endpointRegistry.findByDeviceId(deviceId)
                    if (endpoint != null) {
                        log("↓ Unicast ${envelope.domain.name}.${envelope.action} → $deviceId")
                        hub?.sendTo(endpoint.endpointId, envelope)
                    } else {
                        log("↓ Device $deviceId not in registry, broadcasting to all")
                        hub?.broadcastAll(envelope)
                    }
                }
            }

            TargetType.IOS_CLIENTS, TargetType.HOST -> {
                // Not Bridge's responsibility
            }
        }
    }

    private fun log(message: String, level: LogLevel = LogLevel.INFO) {
        onLog(AppLog(tag = "FWD", message = message, level = level))
    }
}
