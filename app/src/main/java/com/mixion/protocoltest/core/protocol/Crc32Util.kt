package com.mixion.protocoltest.core.protocol

import java.nio.charset.StandardCharsets
import java.util.zip.CRC32

/**
 * Implements CRC-32/ISO-HDLC according to MIXION Protocol V1.0 Section 8.1:
 * - Width: 32 bits
 * - Polynomial: 0x04C11DB7
 * - Initial value: 0xFFFFFFFF
 * - Input reflection: Yes
 * - Output reflection: Yes
 * - Final XOR: 0xFFFFFFFF
 * - Output format: Exactly 8 uppercase hexadecimal characters
 *
 * Verified with Section 8.8 Reference Test Vector:
 * Input canonical JSON: {"command":"HEARTBEAT","payload":{},"request_id":"HB-10001","type":"request","version":1}
 * Expected CRC: A4781FA2
 */
object Crc32Util {

    fun calculate(utf8Bytes: ByteArray): String {
        val crc = CRC32()
        crc.update(utf8Bytes)
        val value = crc.value and 0xFFFFFFFFL
        return String.format("%08X", value)
    }

    fun calculate(text: String): String {
        return calculate(text.toByteArray(StandardCharsets.UTF_8))
    }

    fun verify(canonicalJsonWithoutCrc: String, expectedHex: String): Boolean {
        val calculated = calculate(canonicalJsonWithoutCrc)
        return calculated.equals(expectedHex.trim(), ignoreCase = true)
    }
}
