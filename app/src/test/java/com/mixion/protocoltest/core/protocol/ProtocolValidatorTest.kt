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
}
