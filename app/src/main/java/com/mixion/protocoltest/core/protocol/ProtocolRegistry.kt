package com.mixion.protocoltest.core.protocol

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

/**
 * Definition and registry for MIXION Protocol V1.0 commands.
 */
data class ProtocolDefinition(
    val command: ProtocolCommand,
    val description: String,
    val defaultTimeoutMs: Long,
    val expectedSuccessStatus: ProtocolStatus,
    val defaultPayloadBuilder: () -> JSONObject,
    val isPotentiallyDangerous: Boolean = false
)

object ProtocolRegistry {

    private val reqCounter = AtomicInteger(100)

    fun nextRequestId(prefix: String = "REQ"): String {
        return "$prefix-${reqCounter.incrementAndGet()}"
    }

    val definitions: Map<ProtocolCommand, ProtocolDefinition> = mapOf(
        ProtocolCommand.HELLO to ProtocolDefinition(
            command = ProtocolCommand.HELLO,
            description = "Handshake: Verifies protocol compatibility and identifies embedded controller.",
            defaultTimeoutMs = 2500,
            expectedSuccessStatus = ProtocolStatus.ACCEPTED,
            defaultPayloadBuilder = {
                val p = JSONObject()
                p.put("device", "MIXION-BACKEND")
                p.put("protocol_version", 1)
                p
            }
        ),
        ProtocolCommand.CAPABILITIES to ProtocolDefinition(
            command = ProtocolCommand.CAPABILITIES,
            description = "Discovery: Discovers pump counts, supported pump IDs, and supported commands.",
            defaultTimeoutMs = 2000,
            expectedSuccessStatus = ProtocolStatus.OK,
            defaultPayloadBuilder = { JSONObject() }
        ),
        ProtocolCommand.STATUS to ProtocolDefinition(
            command = ProtocolCommand.STATUS,
            description = "Machine State: Queries internal machine operational state (STARTING, IDLE, etc.).",
            defaultTimeoutMs = 2000,
            expectedSuccessStatus = ProtocolStatus.OK,
            defaultPayloadBuilder = { JSONObject() }
        ),
        ProtocolCommand.GLASS_STATUS to ProtocolDefinition(
            command = ProtocolCommand.GLASS_STATUS,
            description = "Glass Detection: Queries whether a glass is placed under the dispenser (boolean).",
            defaultTimeoutMs = 2000,
            expectedSuccessStatus = ProtocolStatus.OK,
            defaultPayloadBuilder = { JSONObject() }
        ),
        ProtocolCommand.DISPENSE to ProtocolDefinition(
            command = ProtocolCommand.DISPENSE,
            description = "Main Dispense: Executes multi-pump pour. Multi-phase response: ACCEPTED -> COMPLETED.",
            defaultTimeoutMs = 20000,
            expectedSuccessStatus = ProtocolStatus.ACCEPTED, // Initial response is ACCEPTED, followed by COMPLETED
            defaultPayloadBuilder = {
                val p = JSONObject()
                p.put("order_id", "ORD-10452")
                val pumps = JSONArray()
                val p1 = JSONObject().apply {
                    put("pump_id", 1)
                    put("duration_ms", 2000)
                }
                val p2 = JSONObject().apply {
                    put("pump_id", 3)
                    put("duration_ms", 1500)
                }
                pumps.put(p1)
                pumps.put(p2)
                p.put("pumps", pumps)
                p
            },
            isPotentiallyDangerous = true
        ),
        ProtocolCommand.STOP to ProtocolDefinition(
            command = ProtocolCommand.STOP,
            description = "Emergency Stop: Immediately halts any running dispense operation.",
            defaultTimeoutMs = 2000,
            expectedSuccessStatus = ProtocolStatus.COMPLETED,
            defaultPayloadBuilder = { JSONObject() }
        ),
        ProtocolCommand.RESET to ProtocolDefinition(
            command = ProtocolCommand.RESET,
            description = "Fault Reset: Clears error/fault states and returns machine to IDLE.",
            defaultTimeoutMs = 2500,
            expectedSuccessStatus = ProtocolStatus.COMPLETED,
            defaultPayloadBuilder = { JSONObject() }
        ),
        ProtocolCommand.HEARTBEAT to ProtocolDefinition(
            command = ProtocolCommand.HEARTBEAT,
            description = "Liveness: Verifies active link and retrieves power mode (direct / backup).",
            defaultTimeoutMs = 2000,
            expectedSuccessStatus = ProtocolStatus.OK,
            defaultPayloadBuilder = { JSONObject() }
        ),
        ProtocolCommand.CUSTOM to ProtocolDefinition(
            command = ProtocolCommand.CUSTOM,
            description = "Custom Request: Test custom payloads, altered fields, or intentional protocol errors.",
            defaultTimeoutMs = 3000,
            expectedSuccessStatus = ProtocolStatus.OK,
            defaultPayloadBuilder = { JSONObject() }
        )
    )

    fun getDefinition(command: ProtocolCommand): ProtocolDefinition {
        return definitions[command] ?: error("Unknown command: $command")
    }

    /**
     * Builds a full protocol request object.
     */
    fun buildRequest(
        command: ProtocolCommand,
        requestId: String = nextRequestId(commandPrefix(command)),
        customPayload: JSONObject? = null,
        customCommandName: String? = null
    ): JSONObject {
        val req = JSONObject()
        req.put("version", 1)
        req.put("type", "request")
        req.put("request_id", requestId)
        req.put("command", customCommandName ?: command.commandName)
        req.put("payload", customPayload ?: getDefinition(command).defaultPayloadBuilder())
        return req
    }

    private fun commandPrefix(command: ProtocolCommand): String {
        return when (command) {
            ProtocolCommand.HELLO -> "HS"
            ProtocolCommand.CAPABILITIES -> "CAP"
            ProtocolCommand.STATUS -> "REQ-STATUS"
            ProtocolCommand.GLASS_STATUS -> "REQ-GLASS"
            ProtocolCommand.DISPENSE -> "REQ"
            ProtocolCommand.STOP -> "REQ-STOP"
            ProtocolCommand.RESET -> "REQ-RESET"
            ProtocolCommand.HEARTBEAT -> "HB"
            ProtocolCommand.CUSTOM -> "TEST"
        }
    }
}
