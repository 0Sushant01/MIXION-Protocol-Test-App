package com.mixion.protocoltest.core.transport

import com.mixion.protocoltest.core.protocol.CanonicalJson
import com.mixion.protocoltest.core.protocol.ConnectionState
import com.mixion.protocoltest.core.protocol.ProtocolCommand
import com.mixion.protocoltest.core.protocol.ProtocolRegistry
import com.mixion.protocoltest.core.protocol.ProtocolValidator
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MockEmbeddedTransportTest {

    private val testScope = TestScope()
    private val transport = MockEmbeddedTransport(testScope)

    @Test
    fun testMockConnectAndHello() = runTest {
        transport.connect()
        assertEquals(ConnectionState.READY, transport.connectionState.value)

        val req = ProtocolRegistry.buildRequest(ProtocolCommand.HELLO, "HS-00001")
        val signedFrame = CanonicalJson.createSignedFrame(req)

        transport.sendFrame(signedFrame, "HS-00001", "HELLO")

        val responseFrame = transport.receivedFramesFlow.filter { it.contains("HS-00001") }.first()
        val report = ProtocolValidator.validateResponse(responseFrame, "HS-00001", ProtocolCommand.HELLO)

        assertTrue(report.isPass)
        assertEquals("ACCEPTED", report.parsedMessage?.status?.value)
        assertEquals("MIXION-EMBEDDED", report.parsedMessage?.payload?.optString("device"))
    }

    @Test
    fun testMockStatusAndCapabilities() = runTest {
        transport.connect()

        // Test STATUS
        val reqStatus = ProtocolRegistry.buildRequest(ProtocolCommand.STATUS, "REQ-STATUS-001")
        transport.sendFrame(CanonicalJson.createSignedFrame(reqStatus), "REQ-STATUS-001", "STATUS")
        val statusFrame = transport.receivedFramesFlow.filter { it.contains("REQ-STATUS-001") }.first()
        val statusReport = ProtocolValidator.validateResponse(statusFrame, "REQ-STATUS-001", ProtocolCommand.STATUS)
        assertTrue(statusReport.isPass)
        assertEquals("IDLE", statusReport.parsedMessage?.payload?.optString("state"))

        // Test CAPABILITIES
        val reqCap = ProtocolRegistry.buildRequest(ProtocolCommand.CAPABILITIES, "CAP-001")
        transport.sendFrame(CanonicalJson.createSignedFrame(reqCap), "CAP-001", "CAPABILITIES")
        val capFrame = transport.receivedFramesFlow.filter { it.contains("CAP-001") }.first()
        val capReport = ProtocolValidator.validateResponse(capFrame, "CAP-001", ProtocolCommand.CAPABILITIES)
        assertTrue(capReport.isPass)
        assertEquals(6, capReport.parsedMessage?.payload?.optInt("pump_count"))
    }
}
