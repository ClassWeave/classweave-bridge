package com.classweave.bridge.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
enum class Kind {
    @SerialName("cmd") CMD,
    @SerialName("state.full") STATE_FULL,
    @SerialName("state.delta") STATE_DELTA,
    @SerialName("ack") ACK,
    @SerialName("error") ERROR,
    @SerialName("event") EVENT
}

@Serializable
enum class Domain {
    @SerialName("system") SYSTEM,
    @SerialName("scene") SCENE,
    @SerialName("deck") DECK,
    @SerialName("wb") WB
}

@Serializable
enum class Role {
    @SerialName("host") HOST,
    @SerialName("ios") IOS,
    @SerialName("bridge") BRIDGE,
    @SerialName("android") ANDROID
}

@Serializable
enum class TargetType {
    @SerialName("host") HOST,
    @SerialName("device") DEVICE,
    @SerialName("all_clients") ALL_CLIENTS,
    @SerialName("ios_clients") IOS_CLIENTS,
    @SerialName("android_clients") ANDROID_CLIENTS
}

@Serializable
enum class QoS {
    @SerialName("ack") ACK,
    @SerialName("realtime") REALTIME
}

@Serializable
enum class SyncMode {
    @SerialName("event") EVENT,
    @SerialName("full") FULL,
    @SerialName("delta") DELTA,
    @SerialName("repair") REPAIR
}

@Serializable
data class Participant(
    val role: Role,
    val deviceId: String,
    val platform: String? = null
)

@Serializable
data class Target(
    val type: TargetType,
    val deviceId: String? = null
)

@Serializable
data class Relay(
    val viaBridge: Boolean? = null,
    val bridgeId: String? = null,
    val hop: Int? = null
)

@Serializable
data class Delivery(
    val qos: QoS,
    val sync: SyncMode
)

@Serializable
data class StateVersion(
    val domainVersion: Int? = null,
    val baseVersion: Int? = null
)

@Serializable
data class Envelope(
    val v: Int = 1,
    val msgId: String,
    val ts: Long,
    val sessionId: String? = null,
    val kind: Kind,
    val domain: Domain,
    val action: String,
    val sender: Participant? = null,
    val origin: Participant? = null,
    val target: Target? = null,
    val relay: Relay? = null,
    val delivery: Delivery? = null,
    val seq: Int? = null,
    val ackOf: String? = null,
    val state: StateVersion? = null,
    val payload: JsonObject? = null
)
