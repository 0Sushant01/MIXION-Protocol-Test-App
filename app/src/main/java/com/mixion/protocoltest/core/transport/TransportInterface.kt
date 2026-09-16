package com.mixion.protocoltest.core.transport

import com.mixion.protocoltest.core.protocol.ConnectionState
import com.mixion.protocoltest.core.protocol.TrafficLogEntry
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

interface TransportInterface {
    val connectionState: StateFlow<ConnectionState>
    val rawTrafficFlow: SharedFlow<TrafficLogEntry>
    val receivedFramesFlow: SharedFlow<String>

    suspend fun connect(): Result<Unit>
    suspend fun disconnect()
    suspend fun sendFrame(ndjsonFrame: String, requestId: String? = null, command: String? = null): Result<Unit>
    fun isConnected(): Boolean
}
