package com.classweave.bridge.protocol

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonArray
import java.util.UUID

object MessageFactory {

    fun sessionHello(identity: BridgeIdentity, sessionId: String? = null): Envelope =
        Envelope(
            msgId = uuid(),
            ts = now(),
            sessionId = sessionId,
            kind = Kind.CMD,
            domain = Domain.SYSTEM,
            action = "session.hello",
            sender = identity.asParticipant(),
            origin = identity.asParticipant(),
            target = Target(type = TargetType.HOST),
            delivery = Delivery(qos = QoS.ACK, sync = SyncMode.EVENT),
            payload = buildJsonObject {
                put("role", JsonPrimitive("bridge"))
                put("deviceId", JsonPrimitive(identity.deviceId))
                put("username", JsonPrimitive("Bridge ${identity.deviceId}"))
            }
        )

    fun heartbeatPing(identity: BridgeIdentity, sessionId: String): Envelope =
        Envelope(
            msgId = uuid(),
            ts = now(),
            sessionId = sessionId,
            kind = Kind.EVENT,
            domain = Domain.SYSTEM,
            action = "heartbeat.ping",
            sender = identity.asParticipant(),
            origin = identity.asParticipant(),
            target = Target(type = TargetType.HOST),
            delivery = Delivery(qos = QoS.REALTIME, sync = SyncMode.EVENT)
        )

    fun bridgeStatus(
        identity: BridgeIdentity,
        sessionId: String,
        usbConnected: Boolean,
        nearbyAdvertising: Boolean,
        androidDevices: List<AndroidDeviceInfo>
    ): Envelope =
        Envelope(
            msgId = uuid(),
            ts = now(),
            sessionId = sessionId,
            kind = Kind.EVENT,
            domain = Domain.SYSTEM,
            action = "bridge.status",
            sender = identity.asParticipant(),
            origin = identity.asParticipant(),
            target = Target(type = TargetType.HOST),
            delivery = Delivery(qos = QoS.REALTIME, sync = SyncMode.EVENT),
            payload = buildJsonObject {
                put("usbConnected", JsonPrimitive(usbConnected))
                put("nearbyAdvertising", JsonPrimitive(nearbyAdvertising))
                put("androidOnlineCount", JsonPrimitive(androidDevices.count { it.online }))
                putJsonArray("androidDevices") {
                    for (dev in androidDevices) {
                        add(buildJsonObject {
                            put("deviceId", JsonPrimitive(dev.deviceId))
                            put("name", JsonPrimitive(dev.name))
                            put("online", JsonPrimitive(dev.online))
                        })
                    }
                }
                put("lastError", JsonNull)
            }
        )

    fun heartbeatPong(
        identity: BridgeIdentity,
        sessionId: String,
        targetDeviceId: String
    ): Envelope =
        Envelope(
            msgId = uuid(),
            ts = now(),
            sessionId = sessionId,
            kind = Kind.EVENT,
            domain = Domain.SYSTEM,
            action = "heartbeat.pong",
            sender = identity.asParticipant(),
            origin = identity.asParticipant(),
            target = Target(type = TargetType.DEVICE, deviceId = targetDeviceId),
            delivery = Delivery(qos = QoS.REALTIME, sync = SyncMode.EVENT)
        )

    fun sessionWelcome(
        identity: BridgeIdentity,
        sessionId: String,
        targetDeviceId: String
    ): Envelope =
        Envelope(
            msgId = uuid(),
            ts = now(),
            sessionId = sessionId,
            kind = Kind.EVENT,
            domain = Domain.SYSTEM,
            action = "session.welcome",
            sender = identity.asParticipant(),
            origin = identity.asParticipant(),
            target = Target(type = TargetType.DEVICE, deviceId = targetDeviceId),
            delivery = Delivery(qos = QoS.ACK, sync = SyncMode.EVENT),
            payload = buildJsonObject {
                put("sessionId", JsonPrimitive(sessionId))
                put("activeMode", JsonPrimitive("deck"))
                put("sceneVersion", JsonPrimitive(0))
                put("deckVersion", JsonPrimitive(0))
                put("wbVersion", JsonPrimitive(0))
                put("screenWidth", JsonPrimitive(1920.0))
                put("screenHeight", JsonPrimitive(1080.0))
            }
        )

    private fun uuid(): String = UUID.randomUUID().toString()
    private fun now(): Long = System.currentTimeMillis()
}

data class AndroidDeviceInfo(
    val deviceId: String,
    val name: String,
    val online: Boolean
)
