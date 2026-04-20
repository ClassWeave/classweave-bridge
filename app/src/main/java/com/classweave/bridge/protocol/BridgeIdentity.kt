package com.classweave.bridge.protocol

data class BridgeIdentity(
    val deviceId: String = "bridge_001",
    val bridgeId: String = "bridge_001"
) {
    fun asParticipant(): Participant = Participant(
        role = Role.BRIDGE,
        deviceId = deviceId,
        platform = "android"
    )
}
