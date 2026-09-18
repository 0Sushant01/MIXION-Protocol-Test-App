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
    val parsedMessage: ProtocolMessage? = null,
    val crcResult: CrcVerificationResult? = null
)

object ProtocolValidator {

    /**
     * Validates a generated or transmitted MIXION V1.0 request frame against the wire specification.
     */
    fun validateRequest(
        rawFrame: String,
        expectedCommand: ProtocolCommand? = null,
        capabilities: DiscoveredCapabilities? = null
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
                    "Valid CRC32: ${crcCheck.receivedCrc} (matches canonical hash)"
                } else {
                    crcCheck.errorMessage ?: "CRC verification failed"
                }
            )
        )
        if (!crcCheck.isValid) {
            failureReasons.add(crcCheck.errorMessage ?: "CRC verification failed.")
        }

        // Check 4: Protocol Version
        val rawVersion = json.opt("version")
        val isVersionValid = (rawVersion is Int || rawVersion is Long) && (rawVersion as Number).toInt() == 1
        val version = if (rawVersion is Number) (rawVersion as Number).toInt() else -1
        checks.add(
            ValidationCheckItem(
                name = "Protocol Version",
                isPassed = isVersionValid,
                details = if (isVersionValid) "version: 1 (MIXION V1.0)" else "Invalid version: $rawVersion (expected integer 1)"
            )
        )
        if (!isVersionValid) {
            failureReasons.add("Protocol version mismatch: got $rawVersion, expected integer 1.")
        }

        // Check 5: Message Type
        val type = json.optString("type", "")
        val isTypeValid = type == "request"
        checks.add(
            ValidationCheckItem(
                name = "Message Type",
                isPassed = isTypeValid,
                details = if (isTypeValid) "type: 'request'" else "Invalid type: '$type' (expected 'request')"
            )
        )
        if (!isTypeValid) {
            failureReasons.add("Invalid message type: got '$type', expected 'request'.")
        }

        // Check 6: Request ID
        val requestId = json.optString("request_id", "")
        val isReqIdValid = requestId.isNotBlank()
        checks.add(
            ValidationCheckItem(
                name = "Request ID Present",
                isPassed = isReqIdValid,
                details = if (isReqIdValid) "request_id: '$requestId'" else "Missing or blank request_id"
            )
        )
        if (!isReqIdValid) {
            failureReasons.add("Missing or blank request_id.")
        }

        // Check 7: Command Match
        val command = json.optString("command", "")
        val isCommandValid = if (expectedCommand != null && expectedCommand != ProtocolCommand.CUSTOM) {
            command.equals(expectedCommand.commandName, ignoreCase = true)
        } else {
            command.isNotBlank()
        }
        checks.add(
            ValidationCheckItem(
                name = "Command Specification",
                isPassed = isCommandValid,
                details = if (isCommandValid) "command: '$command'" else "Command mismatch: '$command' (expected ${expectedCommand?.commandName})"
            )
        )
        if (!isCommandValid) {
            failureReasons.add("Invalid or unexpected command: '$command'.")
        }

        // Check 8: Payload Schema Validation
        val payload = json.optJSONObject("payload")
        var schemaError: String? = null

        if (payload == null) {
            schemaError = "Missing 'payload' object in request"
        } else {
            when (command.uppercase()) {
                "HELLO" -> {
                    val device = payload.optString("device", "")
                    val rawProtoVer = payload.opt("protocol_version")
                    if (device.isBlank()) schemaError = "HELLO request missing payload.device"
                    if (rawProtoVer !is Int && rawProtoVer !is Long) {
                        schemaError = "HELLO request protocol_version must be integer 1 (got $rawProtoVer)"
                    } else if ((rawProtoVer as Number).toInt() != 1) {
                        schemaError = "HELLO request protocol_version must be 1 (got $rawProtoVer)"
                    }
                }
                "DISPENSE" -> {
                    val orderId = payload.optString("order_id", "")
                    val pumps = payload.optJSONArray("pumps")
                    if (orderId.isBlank()) {
                        schemaError = "DISPENSE request missing non-empty payload.order_id"
                    } else if (pumps == null || pumps.length() == 0) {
                        schemaError = "DISPENSE request payload.pumps array must be non-empty"
                    } else {
                        val seenPumps = mutableSetOf<Int>()
                        for (i in 0 until pumps.length()) {
                            val pump = pumps.optJSONObject(i)
                            if (pump == null) {
                                schemaError = "DISPENSE pump item #$i is not an object"
                                break
                            }

                            // Strict Integer Validation: Protocol V1.0 requires positive integers.
                            // Floating point representations (e.g. 1500.5 or 1.0) are strictly forbidden.
                            val rawPid = pump.opt("pump_id")
                            if (rawPid !is Int && rawPid !is Long) {
                                schemaError = "DISPENSE pump_id must be a positive integer; floating-point or non-integer values are strictly forbidden (got $rawPid)"
                                break
                            }
                            val rawDur = pump.opt("duration_ms")
                            if (rawDur !is Int && rawDur !is Long) {
                                schemaError = "DISPENSE duration_ms must be a positive integer; floating-point or non-integer values are strictly forbidden (got $rawDur)"
                                break
                            }

                            val pid = (rawPid as Number).toInt()
                            val dur = (rawDur as Number).toLong()

                            if (pid <= 0) {
                                schemaError = "DISPENSE pump_id must be a positive integer (got $pid)"
                                break
                            }
                            if (dur <= 0) {
                                schemaError = "DISPENSE duration_ms must be a positive integer in milliseconds (got $dur)"
                                break
                            }
                            if (seenPumps.contains(pid)) {
                                schemaError = "Duplicate pump_id $pid in DISPENSE request (violates Section 31)"
                                break
                            }
                            seenPumps.add(pid)

                            // Capability Gating: Validate against discovered capabilities if available
                            if (capabilities != null && !capabilities.supportedPumpIds.contains(pid)) {
                                schemaError = "Pump ID $pid is not supported by Embedded capabilities (supported: ${capabilities.supportedPumpIds.sorted()})"
                                break
                            }
                        }
                    }
                }
                else -> {
                    // Other V1 commands require a payload object (can be empty)
                }
            }
        }

        val isSchemaValid = schemaError == null
        checks.add(
            ValidationCheckItem(
                name = "Request Payload Schema",
                isPassed = isSchemaValid,
                details = schemaError ?: "Request payload conforms to MIXION V1.0 specification"
            )
        )
        if (!isSchemaValid) {
            failureReasons.add(schemaError!!)
        }

        val parsedMsg = ProtocolMessage(
            version = version,
            type = type,
            requestId = requestId,
            command = command,
            status = null,
            payload = payload ?: JSONObject(),
            error = null,
            crc32 = crcCheck.receivedCrc,
            rawJson = clean
        )

        return ValidationReport(
            isPass = failureReasons.isEmpty(),
            checks = checks,
            failureReasons = failureReasons,
            parsedMessage = parsedMsg,
            crcResult = crcCheck
        )
    }

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
                    "Valid CRC32: ${crcCheck.receivedCrc} (matches canonical checksum)"
                } else {
                    crcCheck.errorMessage ?: "CRC verification failed"
                }
            )
        )
        if (!crcCheck.isValid) {
            failureReasons.add(crcCheck.errorMessage ?: "CRC verification failed.")
        }

        // Check 4: Protocol Version
        val rawVersion = json.opt("version")
        val isVersionValid = (rawVersion is Int || rawVersion is Long) && (rawVersion as Number).toInt() == 1
        val version = if (rawVersion is Number) (rawVersion as Number).toInt() else -1
        checks.add(
            ValidationCheckItem(
                name = "Protocol Version",
                isPassed = isVersionValid,
                details = if (isVersionValid) "version: 1 (MIXION V1.0)" else "Invalid version: $rawVersion (expected integer 1)"
            )
        )
        if (!isVersionValid) {
            failureReasons.add("Protocol version mismatch: received $rawVersion, expected integer 1.")
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
                        val rawProtoVer = payload.opt("protocol_version")
                        if (device.isBlank()) schemaError = "HELLO response missing payload.device"
                        if (rawProtoVer !is Int && rawProtoVer !is Long) {
                            schemaError = "HELLO response protocol_version must be integer 1 (got $rawProtoVer)"
                        } else if ((rawProtoVer as Number).toInt() != 1) {
                            schemaError = "HELLO response protocol_version != 1 (got $rawProtoVer)"
                        }
                    }
                }
                ProtocolCommand.CAPABILITIES -> {
                    if (status == ProtocolStatus.OK) {
                        val rawPumpCount = payload.opt("pump_count")
                        if (rawPumpCount !is Int && rawPumpCount !is Long) {
                            schemaError = "CAPABILITIES missing or invalid pump_count (must be positive integer, got $rawPumpCount)"
                        } else {
                            val pumpCount = (rawPumpCount as Number).toInt()
                            val supportedPumps = payload.optJSONArray("supported_pump_ids")
                            val commands = payload.optJSONArray("commands")
                            if (pumpCount <= 0) {
                                schemaError = "CAPABILITIES pump_count must be a positive integer (got $pumpCount)"
                            } else if (supportedPumps == null || supportedPumps.length() == 0) {
                                schemaError = "CAPABILITIES missing supported_pump_ids array"
                            } else if (commands == null || commands.length() == 0) {
                                schemaError = "CAPABILITIES missing commands array"
                            } else {
                                for (i in 0 until supportedPumps.length()) {
                                    val rawId = supportedPumps.opt(i)
                                    if (rawId !is Int && rawId !is Long) {
                                        schemaError = "CAPABILITIES supported_pump_ids item #$i must be an integer (got $rawId)"
                                        break
                                    }
                                }
                            }
                        }
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
                    if (status == ProtocolStatus.ACCEPTED) {
                        // Section 17: ACCEPTED response carries empty payload object
                    } else if (status == ProtocolStatus.COMPLETED) {
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
            parsedMessage = parsedMsg,
            crcResult = crcCheck
        )
    }
}
