package com.mixion.protocoltest.core.protocol

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolValidatorTest {

    @Test
    fun testValidHelloResponsePasses() {
        val resp = JSONObject().apply {
            put("version", 1)
            put("type", "response")
            put("request_id", "HS-00001")
            put("command", "HELLO")
            put("status", "ACCEPTED")
            put("payload", JSONObject().apply {
                put("device", "MIXION-EMBEDDED")
                put("device_id", "EMB-001")
                put("firmware_version", "1.0.0")
                put("protocol_version", 1)
            })
        }
        val signedFrame = CanonicalJson.createSignedFrame(resp)
        val report = ProtocolValidator.validateResponse(signedFrame, "HS-00001", ProtocolCommand.HELLO)

        assertTrue("Report should be pass", report.isPass)
        assertEquals(0, report.failureReasons.size)
        assertEquals(ProtocolStatus.ACCEPTED, report.parsedMessage?.status)
    }

    @Test
    fun testStatusResponsePasses() {
        val resp = JSONObject().apply {
            put("version", 1)
            put("type", "response")
            put("request_id", "REQ-STATUS-001")
            put("command", "STATUS")
            put("status", "OK")
            put("payload", JSONObject().apply {
                put("state", "IDLE")
            })
        }
        val signedFrame = CanonicalJson.createSignedFrame(resp)
        val report = ProtocolValidator.validateResponse(signedFrame, "REQ-STATUS-001", ProtocolCommand.STATUS)

        assertTrue(report.isPass)
        assertEquals(0, report.failureReasons.size)
    }

    @Test
    fun testGlassStatusResponsePasses() {
        val resp = JSONObject().apply {
            put("version", 1)
            put("type", "response")
            put("request_id", "REQ-GLASS-001")
            put("command", "GLASS_STATUS")
            put("status", "OK")
            put("payload", JSONObject().apply {
                put("glass_present", true)
            })
        }
        val signedFrame = CanonicalJson.createSignedFrame(resp)
        val report = ProtocolValidator.validateResponse(signedFrame, "REQ-GLASS-001", ProtocolCommand.GLASS_STATUS)

        assertTrue(report.isPass)
        assertEquals(0, report.failureReasons.size)
    }

    @Test
    fun testHeartbeatResponsePasses() {
        val resp = JSONObject().apply {
            put("version", 1)
            put("type", "response")
            put("request_id", "HB-10001")
            put("command", "HEARTBEAT")
            put("status", "OK")
            put("payload", JSONObject().apply {
                put("state", "IDLE")
                put("power_mode", "direct")
            })
        }
        val signedFrame = CanonicalJson.createSignedFrame(resp)
        val report = ProtocolValidator.validateResponse(signedFrame, "HB-10001", ProtocolCommand.HEARTBEAT)

        assertTrue(report.isPass)
    }

    @Test
    fun testHeartbeatInvalidPowerModeFails() {
        val resp = JSONObject().apply {
            put("version", 1)
            put("type", "response")
            put("request_id", "HB-10001")
            put("command", "HEARTBEAT")
            put("status", "OK")
            put("payload", JSONObject().apply {
                put("state", "IDLE")
                put("power_mode", "solar") // Invalid! Must be direct or backup
            })
        }
        val signedFrame = CanonicalJson.createSignedFrame(resp)
        val report = ProtocolValidator.validateResponse(signedFrame, "HB-10001", ProtocolCommand.HEARTBEAT)

        assertFalse(report.isPass)
        assertTrue(report.failureReasons.any { it.contains("power_mode") })
    }

    @Test
    fun testCrcMismatchFailsValidation() {
        val resp = JSONObject().apply {
            put("version", 1)
            put("type", "response")
            put("request_id", "HS-00001")
            put("command", "HELLO")
            put("status", "ACCEPTED")
            put("payload", JSONObject())
        }
        val signedFrame = CanonicalJson.createSignedFrame(resp)
        val json = JSONObject(signedFrame.trim())
        // Replace CRC with incorrect value
        json.put("crc32", "12345678")
        val corruptedFrame = CanonicalJson.canonicalize(json) + "\n"

        val report = ProtocolValidator.validateResponse(corruptedFrame, "HS-00001", ProtocolCommand.HELLO)

        assertFalse(report.isPass)
        assertTrue(report.failureReasons.any { it.contains("CRC") })
    }

    @Test
    fun testRequestIdMismatchFails() {
        val resp = JSONObject().apply {
            put("version", 1)
            put("type", "response")
            put("request_id", "WRONG-ID-999")
            put("command", "STATUS")
            put("status", "OK")
            put("payload", JSONObject().apply { put("state", "IDLE") })
        }
        val signedFrame = CanonicalJson.createSignedFrame(resp)
        val report = ProtocolValidator.validateResponse(signedFrame, "REQ-STATUS-001", ProtocolCommand.STATUS)

        assertFalse(report.isPass)
        assertTrue(report.failureReasons.any { it.contains("Request ID mismatch") })
    }

    @Test
    fun testMissingLfFramingFails() {
        val resp = JSONObject().apply {
            put("version", 1)
            put("type", "response")
            put("request_id", "REQ-001")
            put("command", "STATUS")
            put("status", "OK")
            put("payload", JSONObject().apply { put("state", "IDLE") })
        }
        val signedFrameNoLf = CanonicalJson.createSignedFrame(resp).trimEnd() // remove \n
        val report = ProtocolValidator.validateResponse(signedFrameNoLf, "REQ-001", ProtocolCommand.STATUS)

        assertFalse(report.isPass)
        assertTrue(report.failureReasons.any { it.contains("LF") })
    }

    @Test
    fun testValidateRequestValidHelloPasses() {
        val req = ProtocolRegistry.buildRequest(ProtocolCommand.HELLO, "HS-00001")
        val signedFrame = CanonicalJson.createSignedFrame(req)
        val report = ProtocolValidator.validateRequest(signedFrame, ProtocolCommand.HELLO)

        assertTrue(report.isPass)
        assertEquals(0, report.failureReasons.size)
        assertEquals(ProtocolCommand.HELLO.commandName, report.parsedMessage?.command)
        assertEquals("HS-00001", report.parsedMessage?.requestId)
        assertTrue(report.crcResult?.isValid == true)
    }

    @Test
    fun testValidateRequestValidDispensePasses() {
        val req = ProtocolRegistry.buildRequest(ProtocolCommand.DISPENSE, "REQ-78321")
        val signedFrame = CanonicalJson.createSignedFrame(req)
        val report = ProtocolValidator.validateRequest(signedFrame, ProtocolCommand.DISPENSE)

        assertTrue(report.isPass)
        assertEquals(0, report.failureReasons.size)
        assertTrue(report.crcResult?.isValid == true)
    }

    @Test
    fun testValidateRequestDispenseDuplicatePumpFails() {
        val payload = JSONObject().apply {
            put("order_id", "ORD-12345")
            val arr = org.json.JSONArray().apply {
                put(JSONObject().apply { put("pump_id", 1); put("duration_ms", 1000) })
                put(JSONObject().apply { put("pump_id", 1); put("duration_ms", 2000) }) // Duplicate pump_id 1
            }
            put("pumps", arr)
        }
        val req = ProtocolRegistry.buildRequest(ProtocolCommand.DISPENSE, "REQ-002", customPayload = payload)
        val signedFrame = CanonicalJson.createSignedFrame(req)
        val report = ProtocolValidator.validateRequest(signedFrame, ProtocolCommand.DISPENSE)

        assertFalse(report.isPass)
        assertTrue(report.failureReasons.any { it.contains("Duplicate pump_id") })
    }

    @Test
    fun testValidateRequestMalformedCrcPatternFails() {
        val req = ProtocolRegistry.buildRequest(ProtocolCommand.HELLO, "HS-00001")
        val signedFrame = CanonicalJson.createSignedFrame(req)
        val json = JSONObject(signedFrame.trim())
        json.put("crc32", "lowercase") // Not 8 uppercase hex!
        val badFrame = CanonicalJson.canonicalize(json) + "\n"

        val report = ProtocolValidator.validateRequest(badFrame, ProtocolCommand.HELLO)
        assertFalse(report.isPass)
        assertTrue(report.failureReasons.any { it.contains("Malformed 'crc32'") || it.contains("CRC") })
    }

    @Test
    fun testValidateResponseMalformedCrcPatternFails() {
        val resp = JSONObject().apply {
            put("version", 1)
            put("type", "response")
            put("request_id", "HS-00001")
            put("command", "HELLO")
            put("status", "ACCEPTED")
            put("payload", JSONObject())
            put("crc32", "123") // Less than 8 chars!
        }
        val badFrame = CanonicalJson.canonicalize(resp) + "\n"
        val report = ProtocolValidator.validateResponse(badFrame, "HS-00001", ProtocolCommand.HELLO)
        assertFalse(report.isPass)
        assertTrue(report.failureReasons.any { it.contains("Malformed 'crc32'") || it.contains("CRC") })
    }

    @Test
    fun testStrictIntegerValidationDecimalDurationFails() {
        // Raw NDJSON with duration_ms as floating point 1500.5
        val rawJson = """{"command":"DISPENSE","payload":{"order_id":"ORD-1","pumps":[{"duration_ms":1500.5,"pump_id":1}]},"request_id":"REQ-01","type":"request","version":1}"""
        val crc = Crc32Util.calculate(rawJson)
        val frame = """{"command":"DISPENSE","crc32":"$crc","payload":{"order_id":"ORD-1","pumps":[{"duration_ms":1500.5,"pump_id":1}]},"request_id":"REQ-01","type":"request","version":1}""" + "\n"

        val report = ProtocolValidator.validateRequest(frame, ProtocolCommand.DISPENSE)
        assertFalse("1500.5 duration_ms must be rejected", report.isPass)
        assertTrue(report.failureReasons.any { it.contains("positive integer") && it.contains("duration_ms") })
    }

    @Test
    fun testStrictIntegerValidationDecimalPumpIdFails() {
        // Raw NDJSON with pump_id as floating point 1.0
        val rawJson = """{"command":"DISPENSE","payload":{"order_id":"ORD-1","pumps":[{"duration_ms":1500,"pump_id":1.0}]},"request_id":"REQ-01","type":"request","version":1}"""
        val crc = Crc32Util.calculate(rawJson)
        val frame = """{"command":"DISPENSE","crc32":"$crc","payload":{"order_id":"ORD-1","pumps":[{"duration_ms":1500,"pump_id":1.0}]},"request_id":"REQ-01","type":"request","version":1}""" + "\n"

        val report = ProtocolValidator.validateRequest(frame, ProtocolCommand.DISPENSE)
        assertFalse("1.0 pump_id must be rejected", report.isPass)
        assertTrue(report.failureReasons.any { it.contains("positive integer") && it.contains("pump_id") })
    }

    @Test
    fun testStrictIntegerValidationZeroAndNegativeFails() {
        val payloadZero = JSONObject().apply {
            put("order_id", "ORD-1")
            put("pumps", org.json.JSONArray().apply {
                put(JSONObject().apply { put("pump_id", 0); put("duration_ms", 1000) })
            })
        }
        val reqZero = ProtocolRegistry.buildRequest(ProtocolCommand.DISPENSE, "REQ-01", customPayload = payloadZero)
        val frameZero = CanonicalJson.createSignedFrame(reqZero)
        val reportZero = ProtocolValidator.validateRequest(frameZero, ProtocolCommand.DISPENSE)
        assertFalse(reportZero.isPass)
        assertTrue(reportZero.failureReasons.any { it.contains("positive integer") })

        val payloadNeg = JSONObject().apply {
            put("order_id", "ORD-1")
            put("pumps", org.json.JSONArray().apply {
                put(JSONObject().apply { put("pump_id", 1); put("duration_ms", -500) })
            })
        }
        val reqNeg = ProtocolRegistry.buildRequest(ProtocolCommand.DISPENSE, "REQ-02", customPayload = payloadNeg)
        val frameNeg = CanonicalJson.createSignedFrame(reqNeg)
        val reportNeg = ProtocolValidator.validateRequest(frameNeg, ProtocolCommand.DISPENSE)
        assertFalse(reportNeg.isPass)
        assertTrue(reportNeg.failureReasons.any { it.contains("positive integer") })
    }

    @Test
    fun testCapabilityGatingRejectsUnsupportedPumpId() {
        val caps = DiscoveredCapabilities(
            pumpCount = 3,
            supportedPumpIds = setOf(1, 2, 3),
            commands = setOf("HELLO", "CAPABILITIES", "STATUS", "DISPENSE", "STOP", "RESET", "HEARTBEAT")
        )

        // Request pump 4 (not in capabilities [1, 2, 3])
        val payload = JSONObject().apply {
            put("order_id", "ORD-1")
            put("pumps", org.json.JSONArray().apply {
                put(JSONObject().apply { put("pump_id", 4); put("duration_ms", 2000) })
            })
        }
        val req = ProtocolRegistry.buildRequest(ProtocolCommand.DISPENSE, "REQ-03", customPayload = payload)
        val frame = CanonicalJson.createSignedFrame(req)

        val report = ProtocolValidator.validateRequest(frame, ProtocolCommand.DISPENSE, capabilities = caps)
        assertFalse("Pump 4 must be rejected when capabilities only support [1, 2, 3]", report.isPass)
        assertTrue(report.failureReasons.any { it.contains("Pump ID 4 is not supported by Embedded capabilities") })
    }

    @Test
    fun testCapabilityGatingPassesSupportedPumpIds() {
        val caps = DiscoveredCapabilities(
            pumpCount = 3,
            supportedPumpIds = setOf(1, 2, 3),
            commands = setOf("HELLO", "CAPABILITIES", "STATUS", "DISPENSE", "STOP", "RESET", "HEARTBEAT")
        )

        // Request pumps 1 and 3
        val payload = JSONObject().apply {
            put("order_id", "ORD-1")
            put("pumps", org.json.JSONArray().apply {
                put(JSONObject().apply { put("pump_id", 1); put("duration_ms", 2000) })
                put(JSONObject().apply { put("pump_id", 3); put("duration_ms", 1500) })
            })
        }
        val req = ProtocolRegistry.buildRequest(ProtocolCommand.DISPENSE, "REQ-04", customPayload = payload)
        val frame = CanonicalJson.createSignedFrame(req)

        val report = ProtocolValidator.validateRequest(frame, ProtocolCommand.DISPENSE, capabilities = caps)
        assertTrue("Pumps [1, 3] must pass when capabilities support [1, 2, 3]", report.isPass)
    }

    @Test
    fun testStrictVersionValidationDecimalFails() {
        val rawJson = """{"command":"STATUS","payload":{},"request_id":"REQ-V1","type":"request","version":1.0}"""
        val crc = Crc32Util.calculate(rawJson)
        val frame = """{"command":"STATUS","crc32":"$crc","payload":{},"request_id":"REQ-V1","type":"request","version":1.0}""" + "\n"
        val report = ProtocolValidator.validateRequest(frame, ProtocolCommand.STATUS)
        assertFalse("version 1.0 must be rejected", report.isPass)
        assertTrue(report.failureReasons.any { it.contains("version") && it.contains("integer") })
    }

    @Test
    fun testStrictHelloProtocolVersionDecimalFails() {
        val rawJson = """{"command":"HELLO","payload":{"device":"MIXION-BACKEND","protocol_version":1.0},"request_id":"HS-01","type":"request","version":1}"""
        val crc = Crc32Util.calculate(rawJson)
        val frame = """{"command":"HELLO","crc32":"$crc","payload":{"device":"MIXION-BACKEND","protocol_version":1.0},"request_id":"HS-01","type":"request","version":1}""" + "\n"
        val report = ProtocolValidator.validateRequest(frame, ProtocolCommand.HELLO)
        assertFalse("protocol_version 1.0 must be rejected", report.isPass)
        assertTrue(report.failureReasons.any { it.contains("protocol_version") && it.contains("integer") })
    }

    @Test
    fun testStrictCapabilitiesPumpCountDecimalFails() {
        val rawJson = """{"command":"CAPABILITIES","payload":{"commands":["HELLO","DISPENSE"],"pump_count":6.0,"supported_pump_ids":[1,2,3,4,5,6]},"request_id":"CAP-01","status":"OK","type":"response","version":1}"""
        val crc = Crc32Util.calculate(rawJson)
        val frame = """{"command":"CAPABILITIES","crc32":"$crc","payload":{"commands":["HELLO","DISPENSE"],"pump_count":6.0,"supported_pump_ids":[1,2,3,4,5,6]},"request_id":"CAP-01","status":"OK","type":"response","version":1}""" + "\n"
        val report = ProtocolValidator.validateResponse(frame, "CAP-01", ProtocolCommand.CAPABILITIES)
        assertFalse("pump_count 6.0 must be rejected", report.isPass)
        assertTrue(report.failureReasons.any { it.contains("pump_count") && it.contains("integer") })
    }
}

