package com.mixion.protocoltest.core.protocol

import org.json.JSONObject

data class ValidationCheckItem(
    val name: String,
    val isPassed: Boolean,
    val details: String
)

data class ValidationReport(
    val isPass: Boolean,
    val checks: List<ValidationCheckItem>,
    val failureReasons: List<String>,
    val parsedMessage: ProtocolMessage? = null
)

object ProtocolValidator {

    fun validateResponse(
        rawFrame: String,
        expectedRequestId: String,
        expectedCommand: ProtocolCommand
    ): ValidationReport {
        val checks = mutableListOf<ValidationCheckItem>()
        val failureReasons = mutableListOf<String>()

        // Check 1: NDJSON Framing
        val hasTrailingLf = rawFrame.endsWith("\n")
        checks.add(
            ValidationCheckItem(
                name = "NDJSON Frame Framing",
                isPassed = hasTrailingLf,
                details = if (hasTrailingLf) "Terminated by newline (LF \\n)" else "Missing trailing LF framing delimiter"
            )
        )
        if (!hasTrailingLf) {
            failureReasons.add("Frame is not properly terminated with LF (\\n).")
        }

        // Check 2: JSON Parsing
        val clean = rawFrame.trim()
        val json = try {
            JSONObject(clean)
        } catch (e: Exception) {
            checks.add(
                ValidationCheckItem(
                    name = "JSON Parsing",
                    isPassed = false,
                    details = "Syntax error: ${e.message}"
                )
            )
            failureReasons.add("Malformed JSON: ${e.message}")
            return ValidationReport(
                isPass = false,
                checks = checks,
                failureReasons = failureReasons
            )
        }
        checks.add(
            ValidationCheckItem(
                name = "JSON Parsing",
                isPassed = true,
                details = "Valid JSON object structure"
            )
        )

        // Check 3: CRC32 Integrity
        val crcCheck = CanonicalJson.verifyCrc(clean)
        checks.add(
            ValidationCheckItem(
                name = "CRC-32/ISO-HDLC Integrity",
                isPassed = crcCheck.isValid,
                details = if (crcCheck.isValid) {
                    "Valid CRC32: ${crcCheck.receivedCrc} (matches calculated)"
                } else {
                    crcCheck.errorMessage ?: "CRC verification failed"
                }
            )
        )
        if (!crcCheck.isValid) {
            failureReasons.add(crcCheck.errorMessage ?: "CRC verification failed.")
        }

        // Check 4: Protocol Version
        val version = json.optInt("version", -1)
        val isVersionValid = version == 1
        checks.add(
            ValidationCheckItem(
                name = "Protocol Version",
                isPassed = isVersionValid,
                details = if (isVersionValid) "version: 1 (MIXION V1.0)" else "Invalid version: $version (expected 1)"
            )
        )
        if (!isVersionValid) {
            failureReasons.add("Protocol version mismatch: received $version, expected 1.")
        }

        // Check 5: Message Type
        val type = json.optString("type", "")
        val isTypeValid = type == "response"
        checks.add(
            ValidationCheckItem(
                name = "Message Type",
                isPassed = isTypeValid,
                details = if (isTypeValid) "type: 'response'" else "Invalid type: '$type' (expected 'response')"
            )
        )
        if (!isTypeValid) {
            failureReasons.add("Invalid message type: received '$type', expected 'response'.")
        }

        // Check 6: Request ID Correlation
        val requestId = json.optString("request_id", "")
        val isReqIdValid = requestId.isNotBlank() && (expectedCommand == ProtocolCommand.CUSTOM || requestId == expectedRequestId)
        checks.add(
            ValidationCheckItem(
                name = "Request ID Correlation",
                isPassed = isReqIdValid,
                details = if (isReqIdValid) {
                    "request_id: '$requestId' (matches request)"
                } else {
                    "Mismatch: received '$requestId', expected '$expectedRequestId'"
                }
            )
        )
        if (!isReqIdValid) {
            failureReasons.add("Request ID mismatch: received '$requestId', expected '$expectedRequestId'.")
        }

        // Check 7: Command Match
        val command = json.optString("command", "")
        val isCommandValid = expectedCommand == ProtocolCommand.CUSTOM || command.equals(expectedCommand.commandName, ignoreCase = true)
        checks.add(
            ValidationCheckItem(
                name = "Command Match",
                isPassed = isCommandValid,
                details = if (isCommandValid) "command: '$command'" else "Mismatch: received '$command', expected '${expectedCommand.commandName}'"
            )
        )
        if (!isCommandValid) {
            failureReasons.add("Command mismatch: received '$command', expected '${expectedCommand.commandName}'.")
        }

        // Check 8: Status Field
        val statusStr = json.optString("status", "")
        val status = ProtocolStatus.fromValue(statusStr)
        val isStatusValid = status != null
        checks.add(
            ValidationCheckItem(
                name = "Status Field",
                isPassed = isStatusValid,
                details = if (isStatusValid) "status: '$statusStr'" else "Unknown status: '$statusStr'"
            )
        )
        if (!isStatusValid) {
            failureReasons.add("Invalid or missing response status: '$statusStr'.")
        }

        // Check 9: Payload / Error Schema Validation
        val payload = json.optJSONObject("payload") ?: JSONObject()
        var schemaError: String? = null

        if (status == ProtocolStatus.ERROR) {
            val errorObj = json.optJSONObject("error")
            if (errorObj == null) {
                schemaError = "Response has status 'ERROR' but missing 'error' object."
            } else {
                val code = errorObj.optString("code", "")
                val msg = errorObj.optString("message", "")
                val validCode = ProtocolErrorCode.fromCode(code) != null
                if (!validCode) {
                    schemaError = "Invalid error code '$code' (expected E001..E013)."
                } else if (msg.isBlank()) {
                    schemaError = "Error message is blank."
                }
            }
        } else {
            // Validate specific command payload schemas
            when (expectedCommand) {
                ProtocolCommand.HELLO -> {
                    if (status == ProtocolStatus.ACCEPTED) {
                        val device = payload.optString("device", "")
                        val protoVer = payload.optInt("protocol_version", -1)
                        if (device.isBlank()) schemaError = "HELLO response missing payload.device"
                        if (protoVer != 1) schemaError = "HELLO response protocol_version != 1 (got $protoVer)"
                    }
                }
                ProtocolCommand.CAPABILITIES -> {
                    if (status == ProtocolStatus.OK) {
                        val pumpCount = payload.optInt("pump_count", -1)
                        val supportedPumps = payload.optJSONArray("supported_pump_ids")
                        val commands = payload.optJSONArray("commands")
                        if (pumpCount <= 0) schemaError = "CAPABILITIES missing or invalid pump_count"
                        if (supportedPumps == null || supportedPumps.length() == 0) schemaError = "CAPABILITIES missing supported_pump_ids array"
                        if (commands == null || commands.length() == 0) schemaError = "CAPABILITIES missing commands array"
                    }
                }
                ProtocolCommand.STATUS -> {
                    if (status == ProtocolStatus.OK) {
                        val state = payload.optString("state", "")
                        if (MachineOperationalState.fromString(state) == null) {
                            schemaError = "STATUS returned unknown machine state '$state' (expected STARTING, IDLE, DISPENSING, ERROR, STOPPED)"
                        }
                    }
                }
                ProtocolCommand.GLASS_STATUS -> {
                    if (status == ProtocolStatus.OK) {
                        if (!payload.has("glass_present")) {
                            schemaError = "GLASS_STATUS response missing 'glass_present' boolean"
                        }
                    }
                }
                ProtocolCommand.DISPENSE -> {
                    if (status == ProtocolStatus.COMPLETED) {
                        val result = payload.optString("result", "")
                        if (result != "SUCCESS") {
                            schemaError = "DISPENSE COMPLETED payload result is '$result' (expected 'SUCCESS')"
                        }
                    }
                }
                ProtocolCommand.HEARTBEAT -> {
                    if (status == ProtocolStatus.OK) {
                        val powerMode = payload.optString("power_mode", "")
                        if (powerMode != "direct" && powerMode != "backup") {
                            schemaError = "HEARTBEAT power_mode must be 'direct' or 'backup' (got '$powerMode')"
                        }
                    }
                }
                else -> { /* Other commands */ }
            }
        }

        val isSchemaValid = schemaError == null
        checks.add(
            ValidationCheckItem(
                name = "Payload Schema Validation",
                isPassed = isSchemaValid,
                details = schemaError ?: "Payload conforms to Protocol V1.0 schema"
            )
        )
        if (!isSchemaValid) {
            failureReasons.add(schemaError!!)
        }

        val parsedError = if (json.has("error")) {
            val errObj = json.getJSONObject("error")
            val failedPumps = mutableListOf<Int>()
            errObj.optJSONObject("details")?.optJSONArray("failed_pumps")?.let { arr ->
                for (i in 0 until arr.length()) failedPumps.add(arr.getInt(i))
            }
            ProtocolErrorData(
                code = errObj.optString("code", "UNKNOWN"),
                message = errObj.optString("message", ""),
                failedPumps = failedPumps
            )
        } else null

        val parsedMsg = ProtocolMessage(
            version = version,
            type = type,
            requestId = requestId,
            command = command,
            status = status,
            payload = payload,
            error = parsedError,
            crc32 = crcCheck.receivedCrc,
            rawJson = clean
        )

        return ValidationReport(
            isPass = failureReasons.isEmpty(),
            checks = checks,
            failureReasons = failureReasons,
            parsedMessage = parsedMsg
        )
    }
}
