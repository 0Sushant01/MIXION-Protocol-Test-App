package com.mixion.protocoltest.core.protocol

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Crc32UtilTest {

    /**
     * MIXION Protocol V1.0 Section 8.8:
     * Test Vector 1:
     * Input canonical JSON: {"command":"HEARTBEAT","payload":{},"request_id":"HB-10001","type":"request","version":1}
     * Encoding: UTF-8
     * Algorithm: CRC-32/ISO-HDLC
     * Expected CRC: A4781FA2
     * Expected final JSON: {"command":"HEARTBEAT","crc32":"A4781FA2","payload":{},"request_id":"HB-10001","type":"request","version":1}
     * Frame: [final JSON] + LF
     */
    @Test
    fun testReferenceTestVector1() {
        val inputCanonical = "{\"command\":\"HEARTBEAT\",\"payload\":{},\"request_id\":\"HB-10001\",\"type\":\"request\",\"version\":1}"
        val crc = Crc32Util.calculate(inputCanonical)
        assertEquals("A4781FA2", crc)
    }

    @Test
    fun testCreateSignedFrameMatchesTestVector1() {
        val json = JSONObject()
        json.put("version", 1)
        json.put("type", "request")
        json.put("request_id", "HB-10001")
        json.put("command", "HEARTBEAT")
        json.put("payload", JSONObject())

        val frame = CanonicalJson.createSignedFrame(json)
        val expected = "{\"command\":\"HEARTBEAT\",\"crc32\":\"A4781FA2\",\"payload\":{},\"request_id\":\"HB-10001\",\"type\":\"request\",\"version\":1}\n"
        assertEquals(expected, frame)
    }

    @Test
    fun testVerifyCrcSuccess() {
        val frame = "{\"command\":\"HEARTBEAT\",\"crc32\":\"A4781FA2\",\"payload\":{},\"request_id\":\"HB-10001\",\"type\":\"request\",\"version\":1}\n"
        val result = CanonicalJson.verifyCrc(frame)
        assertTrue(result.isValid)
        assertEquals("A4781FA2", result.receivedCrc)
        assertEquals("A4781FA2", result.calculatedCrc)
    }

    @Test
    fun testVerifyCrcTamperedPayloadFails() {
        // Tampered payload with "state":"IDLE"
        val frame = "{\"command\":\"HEARTBEAT\",\"crc32\":\"A4781FA2\",\"payload\":{\"state\":\"IDLE\"},\"request_id\":\"HB-10001\",\"type\":\"request\",\"version\":1}\n"
        val result = CanonicalJson.verifyCrc(frame)
        assertFalse(result.isValid)
        assertTrue(result.errorMessage?.contains("CRC mismatch") == true)
    }

    @Test
    fun testVerifyCrcMissingCrcFails() {
        val frame = "{\"command\":\"HEARTBEAT\",\"payload\":{},\"request_id\":\"HB-10001\",\"type\":\"request\",\"version\":1}\n"
        val result = CanonicalJson.verifyCrc(frame)
        assertFalse(result.isValid)
        assertTrue(result.errorMessage?.contains("Missing 'crc32'") == true)
    }
}
