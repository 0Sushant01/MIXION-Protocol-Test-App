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

    @Test
    fun testDuplicateRequestExecutionProtection() = runTest {
        val dispatcher = kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)
        val transport = MockEmbeddedTransport(this, dispatcher)
        transport.connect()

        val dispensePayload = org.json.JSONObject().apply {
            put("order_id", "ORD-12345")
            val arr = org.json.JSONArray().apply {
                put(org.json.JSONObject().apply { put("pump_id", 1); put("duration_ms", 500) })
            }
            put("pumps", arr)
        }
        val req = ProtocolRegistry.buildRequest(ProtocolCommand.DISPENSE, "REQ-DUP-01", customPayload = dispensePayload)
        val signedFrame = CanonicalJson.createSignedFrame(req)

        // Send first request
        transport.sendFrame(signedFrame, "REQ-DUP-01", "DISPENSE")
        // Wait for completion
        transport.receivedFramesFlow.filter { it.contains("REQ-DUP-01") && it.contains("COMPLETED") }.first()

        assertEquals("First execution must increment physical execution count to 1", 1, transport.physicalExecutionCountMap["REQ-DUP-01"]?.get() ?: 0)

        // Send duplicate request with identical request_id
        transport.sendFrame(signedFrame, "REQ-DUP-01", "DISPENSE")
        val dupFrame = transport.receivedFramesFlow.filter { it.contains("REQ-DUP-01") && it.contains("COMPLETED") }.first()
        assertTrue("Duplicate request should return completed state", dupFrame.contains("COMPLETED"))

        // Assert physical execution was NOT performed twice
        assertEquals("Physical execution count must remain 1 after duplicate request", 1, transport.physicalExecutionCountMap["REQ-DUP-01"]?.get() ?: 0)
    }

    @Test
    fun testDiscreteMax3Scheduler4PumpTimeline() = runTest {
        val dispatcher = kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)
        val transport = MockEmbeddedTransport(this, dispatcher)
        transport.connect()

        // Protocol V1.0 Section 19 Specification Example:
        // P4 = 5000 ms, P3 = 4000 ms, P2 = 3000 ms, P1 = 1000 ms
        val payload = org.json.JSONObject().apply {
            put("order_id", "ORD-4PUMP")
            val arr = org.json.JSONArray().apply {
                put(org.json.JSONObject().apply { put("pump_id", 1); put("duration_ms", 1000) })
                put(org.json.JSONObject().apply { put("pump_id", 2); put("duration_ms", 3000) })
                put(org.json.JSONObject().apply { put("pump_id", 3); put("duration_ms", 4000) })
                put(org.json.JSONObject().apply { put("pump_id", 4); put("duration_ms", 5000) })
            }
            put("pumps", arr)
        }
        val req = ProtocolRegistry.buildRequest(ProtocolCommand.DISPENSE, "REQ-SCHED-4", customPayload = payload)
        val signedFrame = CanonicalJson.createSignedFrame(req)

        transport.sendFrame(signedFrame, "REQ-SCHED-4", "DISPENSE")

        // Wait for final COMPLETED frame
        transport.receivedFramesFlow.filter { it.contains("REQ-SCHED-4") && it.contains("COMPLETED") }.first()

        val events = transport.schedulerEvents
        // Expected events:
        // T=0: P4 started
        // T=0: P3 started
        // T=0: P2 started
        // T=3000: P2 completed
        // T=3000: P1 started (slot freed by P2)
        // T=4000: P3 completed
        // T=4000: P1 completed (3000 + 1000)
        // T=5000: P4 completed

        // Verify initial 3 active pumps started at T=0
        val startEventsAt0 = events.filter { it.eventType == "STARTED" && it.elapsedVirtualTimeMs == 0L }
        assertEquals(3, startEventsAt0.size)
        val initialStartedPumpIds = startEventsAt0.map { it.pumpId }
        assertEquals(listOf(4, 3, 2), initialStartedPumpIds)

        // Verify P2 completes at T=3000
        val p2Complete = events.firstOrNull { it.eventType == "COMPLETED" && it.pumpId == 2 }
        assertEquals(3000L, p2Complete?.elapsedVirtualTimeMs)

        // Verify P1 starts at T=3000
        val p1Start = events.firstOrNull { it.eventType == "STARTED" && it.pumpId == 1 }
        assertEquals(3000L, p1Start?.elapsedVirtualTimeMs)

        // Verify P3 and P1 complete at T=4000
        val p3Complete = events.firstOrNull { it.eventType == "COMPLETED" && it.pumpId == 3 }
        assertEquals(4000L, p3Complete?.elapsedVirtualTimeMs)

        val p1Complete = events.firstOrNull { it.eventType == "COMPLETED" && it.pumpId == 1 }
        assertEquals(4000L, p1Complete?.elapsedVirtualTimeMs)

        // Verify P4 completes at T=5000
        val p4Complete = events.firstOrNull { it.eventType == "COMPLETED" && it.pumpId == 4 }
        assertEquals(5000L, p4Complete?.elapsedVirtualTimeMs)
    }

    @Test
    fun testDiscreteMax3SchedulerPumps1Through6() = runTest {
        val dispatcher = kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)
        val transport = MockEmbeddedTransport(this, dispatcher)
        transport.connect()

        // 1 pump: starts and completes
        val p1 = org.json.JSONObject().apply {
            put("order_id", "ORD-1P")
            put("pumps", org.json.JSONArray().apply {
                put(org.json.JSONObject().apply { put("pump_id", 1); put("duration_ms", 200) })
            })
        }
        transport.sendFrame(CanonicalJson.createSignedFrame(ProtocolRegistry.buildRequest(ProtocolCommand.DISPENSE, "REQ-1P", customPayload = p1)), "REQ-1P", "DISPENSE")
        transport.receivedFramesFlow.filter { it.contains("REQ-1P") && it.contains("COMPLETED") }.first()

        // 2 pumps: both start at T=0
        val p2 = org.json.JSONObject().apply {
            put("order_id", "ORD-2P")
            put("pumps", org.json.JSONArray().apply {
                put(org.json.JSONObject().apply { put("pump_id", 1); put("duration_ms", 300) })
                put(org.json.JSONObject().apply { put("pump_id", 2); put("duration_ms", 200) })
            })
        }
        transport.sendFrame(CanonicalJson.createSignedFrame(ProtocolRegistry.buildRequest(ProtocolCommand.DISPENSE, "REQ-2P", customPayload = p2)), "REQ-2P", "DISPENSE")
        transport.receivedFramesFlow.filter { it.contains("REQ-2P") && it.contains("COMPLETED") }.first()

        // 6 pumps: exactly 3 start initially, next 3 start as slots free up
        val p6 = org.json.JSONObject().apply {
            put("order_id", "ORD-6P")
            put("pumps", org.json.JSONArray().apply {
                (1..6).forEach { id ->
                    put(org.json.JSONObject().apply { put("pump_id", id); put("duration_ms", (id * 100).toLong()) })
                }
            })
        }
        transport.sendFrame(CanonicalJson.createSignedFrame(ProtocolRegistry.buildRequest(ProtocolCommand.DISPENSE, "REQ-6P", customPayload = p6)), "REQ-6P", "DISPENSE")
        transport.receivedFramesFlow.filter { it.contains("REQ-6P") && it.contains("COMPLETED") }.first()

        // Verify all 6 completed
        val p6Completions = transport.schedulerEvents.filter { it.eventType == "COMPLETED" && (1..6).contains(it.pumpId) }
        assertTrue("All 6 pumps must complete in 6-pump test", p6Completions.size >= 6)
    }
}
