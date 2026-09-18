package com.mixion.protocoltest.core.protocol

import org.json.JSONArray
import org.json.JSONObject

/**
 * Protocol command names defined in MIXION Protocol V1.0 Section 30.
 */
enum class ProtocolCommand(
    val commandName: String,
    val displayName: String,
    val isPotentiallyDangerous: Boolean,
    val isTesterDiagnosticOnly: Boolean = false
) {
    HELLO("HELLO", "HELLO (Handshake)", false),
    CAPABILITIES("CAPABILITIES", "CAPABILITIES (Discovery)", false),
    STATUS("STATUS", "STATUS (Machine State)", false),
    GLASS_STATUS("GLASS_STATUS", "GLASS_STATUS (Sensor)", false),
    DISPENSE("DISPENSE", "DISPENSE (Pour Drink)", true),
    STOP("STOP", "STOP (Halt Operation)", false),
    RESET("RESET", "RESET (Clear Faults)", false),
    HEARTBEAT("HEARTBEAT", "HEARTBEAT (Liveness)", false),
    CUSTOM("CUSTOM", "CUSTOM (Tester Diagnostic Mode)", false, isTesterDiagnosticOnly = true);

    companion object {
        fun fromName(name: String): ProtocolCommand? {
            return entries.find { it.commandName.equals(name, ignoreCase = true) }
        }
    }
}

/**
 * Discovered hardware capabilities retrieved from Embedded Brain via CAPABILITIES command.
 */
data class DiscoveredCapabilities(
    val pumpCount: Int,
    val supportedPumpIds: Set<Int>,
    val commands: Set<String>,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Response statuses defined in MIXION Protocol V1.0 Section 31.
 */
enum class ProtocolStatus(val value: String) {
    ACCEPTED("ACCEPTED"),
    COMPLETED("COMPLETED"),
    ERROR("ERROR"),
    OK("OK");

    companion object {
        fun fromValue(value: String): ProtocolStatus? {
            return entries.find { it.value.equals(value, ignoreCase = true) }
        }
    }
}

/**
 * Error codes defined in MIXION Protocol V1.0 Section 24.
 */
enum class ProtocolErrorCode(val code: String, val meaning: String) {
    E001("E001", "Invalid command"),
    E002("E002", "Invalid JSON/message"),
    E003("E003", "Protocol/version error"),
    E004("E004", "Invalid parameter"),
    E005("E005", "Machine busy"),
    E006("E006", "Pump execution failure"),
    E007("E007", "Sensor error"),
    E009("E009", "Operation timeout"),
    E010("E010", "Machine not ready"),
    E011("E011", "Duplicate request"),
    E012("E012", "Internal Embedded error"),
    E013("E013", "CRC/integrity failure");

    companion object {
        fun fromCode(code: String): ProtocolErrorCode? {
            return entries.find { it.code.equals(code, ignoreCase = true) }
        }
    }
}

/**
 * Connection State defined in MIXION Protocol V1.0 Section 27:
 * Describes Backend <-> Embedded communication/session status.
 */
enum class ConnectionState(val displayName: String) {
    OFFLINE("OFFLINE"),
    USB_CONNECTED("USB CONNECTED"),
    CONNECTING("CONNECTING"),
    HANDSHAKING("HANDSHAKING"),
    ONLINE("ONLINE"),
    READY("READY"),
    BUSY("BUSY")
}

/**
 * Machine Operational State defined in MIXION Protocol V1.0 Section 12:
 * Describes Embedded hardware condition reported by STATUS/HEARTBEAT.
 */
enum class MachineOperationalState(val displayName: String) {
    STARTING("STARTING"),
    IDLE("IDLE"),
    DISPENSING("DISPENSING"),
    ERROR("ERROR"),
    STOPPED("STOPPED");

    companion object {
        fun fromString(state: String): MachineOperationalState? {
            return entries.find { it.name.equals(state, ignoreCase = true) }
        }
    }
}

/**
 * Single pump dispensing operation.
 */
data class PumpOperation(
    val pumpId: Int,
    val durationMs: Long
) {
    fun toJsonObject(): JSONObject {
        val obj = JSONObject()
        obj.put("pump_id", pumpId)
        obj.put("duration_ms", durationMs)
        return obj
    }

    companion object {
        fun fromJsonObject(obj: JSONObject): PumpOperation {
            return PumpOperation(
                pumpId = obj.getInt("pump_id"),
                durationMs = obj.getLong("duration_ms")
            )
        }
    }
}

/**
 * Parsed MIXION V1 message.
 */
data class ProtocolMessage(
    val version: Int = 1,
    val type: String, // "request" or "response"
    val requestId: String,
    val command: String,
    val status: ProtocolStatus? = null,
    val payload: JSONObject = JSONObject(),
    val error: ProtocolErrorData? = null,
    val crc32: String? = null,
    val rawJson: String = ""
)

data class ProtocolErrorData(
    val code: String,
    val message: String,
    val failedPumps: List<Int> = emptyList()
)

/**
 * Traffic log entry for the Raw Communication Monitor.
 */
data class TrafficLogEntry(
    val id: Long = System.currentTimeMillis() + (0..999).random(),
    val timestamp: String,
    val direction: TrafficDirection,
    val bytesCount: Int,
    val asciiContent: String,
    val hexContent: String,
    val latencyMs: Long? = null,
    val requestId: String? = null,
    val command: String? = null,
    val status: String? = null
)

enum class TrafficDirection {
    TX,
    RX,
    INFO,
    WARN,
    ERROR
}

/**
 * Saved historical test record.
 */
data class TestHistoryItem(
    val id: String = java.util.UUID.randomUUID().toString(),
    val timestamp: String,
    val command: String,
    val requestId: String,
    val isPass: Boolean,
    val latencyMs: Long,
    val requestJson: String,
    val responseJson: String,
    val rawTxHex: String,
    val rawRxHex: String,
    val validationSummary: String,
    val failureReasons: List<String> = emptyList()
)
