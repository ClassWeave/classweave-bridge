package com.classweave.bridge.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class AndroidEndpoint(
    val deviceId: String,
    val name: String,
    val endpointId: String,
    val online: Boolean = true
)

class EndpointRegistry {

    private val _devices = MutableStateFlow<Map<String, AndroidEndpoint>>(emptyMap())
    val devices: StateFlow<Map<String, AndroidEndpoint>> = _devices.asStateFlow()

    fun register(deviceId: String, name: String, endpointId: String) {
        _devices.update { it + (deviceId to AndroidEndpoint(deviceId, name, endpointId)) }
    }

    fun unregister(deviceId: String) {
        _devices.update { it - deviceId }
    }

    fun unregisterByEndpointId(endpointId: String) {
        _devices.update { map ->
            val key = map.entries.firstOrNull { it.value.endpointId == endpointId }?.key
            if (key != null) map - key else map
        }
    }

    fun markOffline(deviceId: String) {
        _devices.update { map ->
            val dev = map[deviceId] ?: return@update map
            map + (deviceId to dev.copy(online = false))
        }
    }

    fun markOnline(deviceId: String) {
        _devices.update { map ->
            val dev = map[deviceId] ?: return@update map
            map + (deviceId to dev.copy(online = true))
        }
    }

    fun findByDeviceId(deviceId: String): AndroidEndpoint? = _devices.value[deviceId]

    fun findByEndpointId(endpointId: String): AndroidEndpoint? =
        _devices.value.values.firstOrNull { it.endpointId == endpointId }

    fun onlineDevices(): List<AndroidEndpoint> = _devices.value.values.filter { it.online }

    fun onlineCount(): Int = _devices.value.values.count { it.online }

    fun allDevices(): List<AndroidEndpoint> = _devices.value.values.toList()
}
