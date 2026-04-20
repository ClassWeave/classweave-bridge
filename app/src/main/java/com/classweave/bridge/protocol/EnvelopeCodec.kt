package com.classweave.bridge.protocol

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object EnvelopeCodec {

    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    fun encode(envelope: Envelope): String = json.encodeToString(envelope)

    fun decode(line: String): Envelope = json.decodeFromString(line)
}
