package com.mixion.protocoltest.domain.test

import com.mixion.protocoltest.core.protocol.ConnectionState
import com.mixion.protocoltest.core.protocol.ProtocolCommand
import com.mixion.protocoltest.core.protocol.TrafficLogEntry
import com.mixion.protocoltest.core.transport.MockEmbeddedTransport
import com.mixion.protocoltest.core.transport.TransportInterface
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProtocolTestEngineTest {

    private fun createFakeUsbTransport(): TransportInterface = object : TransportInterface {
        override val connectionState: StateFlow<ConnectionState> = MutableStateFlow(ConnectionState.OFFLINE)
        override val rawTrafficFlow: SharedFlow<TrafficLogEntry> = MutableSharedFlow()
        override val receivedFramesFlow: SharedFlow<String> = MutableSharedFlow()
        override suspend fun connect(): Result<Unit> = Result.success(Unit)
        override suspend fun disconnect() {}
        override suspend fun sendFrame(ndjsonFrame: String, requestId: String?, command: String?): Result<Unit> = Result.success(Unit)
        override fun isConnected(): Boolean = false
    }

    @Test
    fun testHelloEndToEnd() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val mockTransport = MockEmbeddedTransport(this, dispatcher)
        val engine = ProtocolTestEngine(this, createFakeUsbTransport(), mockTransport, dispatcher)

        try {
            mockTransport.connect()
            engine.executeTest(ProtocolCommand.HELLO, customRequestId = "HS-00001").join()

            val active = engine.activeTest.value
            assertEquals(TestExecutionStatus.PASS, active.status)
            assertTrue(active.validationReport?.isPass == true)
            assertEquals("HS-00001", active.requestId)
            assertTrue(active.rawRxString.contains("MIXION-EMBEDDED"))

            val history = engine.testHistory.value
            assertEquals(1, history.size)
            assertEquals("HELLO", history[0].command)
            assertTrue(history[0].isPass)

            val logs = engine.trafficLogs.value
            assertTrue(logs.size >= 2)
        } finally {
            engine.cleanup()
        }
    }

    @Test
    fun testStatusEndToEnd() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val mockTransport = MockEmbeddedTransport(this, dispatcher)
        val engine = ProtocolTestEngine(this, createFakeUsbTransport(), mockTransport, dispatcher)

        try {
            mockTransport.connect()
            engine.executeTest(ProtocolCommand.STATUS, customRequestId = "REQ-STATUS-001").join()

            val active = engine.activeTest.value
            assertEquals(TestExecutionStatus.PASS, active.status)
            assertTrue(active.validationReport?.isPass == true)
            assertTrue(active.rawRxString.contains("IDLE"))
        } finally {
            engine.cleanup()
        }
    }

    @Test
    fun testHeartbeatEndToEnd() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val mockTransport = MockEmbeddedTransport(this, dispatcher)
        val engine = ProtocolTestEngine(this, createFakeUsbTransport(), mockTransport, dispatcher)

        try {
            mockTransport.connect()
            engine.executeTest(ProtocolCommand.HEARTBEAT, customRequestId = "HB-10001").join()

            val active = engine.activeTest.value
            assertEquals(TestExecutionStatus.PASS, active.status)
            assertTrue(active.validationReport?.isPass == true)
            assertTrue(active.rawRxString.contains("direct"))
        } finally {
            engine.cleanup()
        }
    }

    @Test
    fun testDispenseMultiResponseEndToEnd() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val mockTransport = MockEmbeddedTransport(this, dispatcher)
        val engine = ProtocolTestEngine(this, createFakeUsbTransport(), mockTransport, dispatcher)

        try {
            mockTransport.connect()

            val dispensePayload = JSONObject().apply {
                put("order_id", "ORD-99999")
                val arr = JSONArray().apply {
                    put(JSONObject().apply {
                        put("pump_id", 1)
                        put("duration_ms", 1000)
                    })
                    put(JSONObject().apply {
                        put("pump_id", 2)
                        put("duration_ms", 1500)
                    })
                }
                put("pumps", arr)
            }

            engine.executeTest(ProtocolCommand.DISPENSE, customPayload = dispensePayload, customRequestId = "REQ-78321").join()

            val active = engine.activeTest.value
            assertEquals(TestExecutionStatus.PASS, active.status)
            assertTrue(active.validationReport?.isPass == true)
            assertTrue(active.requestValidationReport?.isPass == true)
            assertTrue(active.dispensePhase1RxFrame?.contains("ACCEPTED") == true)
            assertTrue(active.dispensePhase2RxFrame?.contains("COMPLETED") == true)
            assertTrue(active.dispensePhase2RxFrame?.contains("SUCCESS") == true)
        } finally {
            engine.cleanup()
        }
    }

    @Test
    fun testStopAndReset() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val mockTransport = MockEmbeddedTransport(this, dispatcher)
        val engine = ProtocolTestEngine(this, createFakeUsbTransport(), mockTransport, dispatcher)

        try {
            mockTransport.connect()

            // Send STOP
            engine.executeTest(ProtocolCommand.STOP, customRequestId = "REQ-STOP-001").join()
            assertEquals(TestExecutionStatus.PASS, engine.activeTest.value.status)

            // Send RESET
            engine.executeTest(ProtocolCommand.RESET, customRequestId = "REQ-RESET-001").join()
            assertEquals(TestExecutionStatus.PASS, engine.activeTest.value.status)
        } finally {
            engine.cleanup()
        }
    }

    @Test
    fun testRetryPreservesIdenticalRequestId() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)

        // Transport that fails on initial attempt, simulating timeout or serial failure
        var attemptCount = 0
        val failingTransport = object : TransportInterface {
            override val connectionState: StateFlow<ConnectionState> = MutableStateFlow(ConnectionState.READY)
            override val rawTrafficFlow: SharedFlow<TrafficLogEntry> = MutableSharedFlow()
            override val receivedFramesFlow: SharedFlow<String> = MutableSharedFlow()
            override suspend fun connect(): Result<Unit> = Result.success(Unit)
            override suspend fun disconnect() {}
            override suspend fun sendFrame(ndjsonFrame: String, requestId: String?, command: String?): Result<Unit> {
                attemptCount++
                return if (attemptCount == 1) {
                    Result.failure(Exception("Simulated UART timeout/transmission drop"))
                } else {
                    Result.success(Unit)
                }
            }
            override fun isConnected(): Boolean = true
        }

        val mockTransport = MockEmbeddedTransport(this, dispatcher)
        val engine = ProtocolTestEngine(this, failingTransport, mockTransport, dispatcher)
        engine.setMockMode(false) // Use failingTransport

        try {
            // Initial attempt with request_id = "REQ-1001"
            engine.executeTest(ProtocolCommand.STATUS, customRequestId = "REQ-1001").join()

            val failedState = engine.activeTest.value
            assertEquals("Initial attempt should fail due to transport failure", TestExecutionStatus.ERROR, failedState.status)
            assertEquals("REQ-1001", failedState.requestId)
            assertTrue("Transport failure must be retriable", failedState.isRetriable)

            // Switch to mock transport for successful retry execution
            engine.setMockMode(true)
            mockTransport.connect()

            // Execute retry of the same operation
            val retryJob = engine.retryLastTest()
            assertTrue("Retry job must not be null", retryJob != null)
            retryJob?.join()

            val retriedState = engine.activeTest.value
            // CRITICAL PROTOCOL V1.0 ASSERTION:
            // A retry of the same operation MUST reuse the original request_id
            assertEquals("Retry of the same operation MUST reuse the exact original request_id", "REQ-1001", retriedState.requestId)
            assertTrue("isRetryAttempt flag must be true", retriedState.isRetryAttempt)
            assertEquals("Retried test should succeed", TestExecutionStatus.PASS, retriedState.status)
        } finally {
            engine.cleanup()
        }
    }

    @Test
    fun testCapabilityGatingRejectsUnsupportedPumpBeforeWireTransmission() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val mockTransport = MockEmbeddedTransport(this, dispatcher)
        val engine = ProtocolTestEngine(this, createFakeUsbTransport(), mockTransport, dispatcher)

        try {
            mockTransport.connect()

            // Discovered capabilities only support pumps [1, 2, 3]
            engine.setDiscoveredCapabilities(
                com.mixion.protocoltest.core.protocol.DiscoveredCapabilities(
                    pumpCount = 3,
                    supportedPumpIds = setOf(1, 2, 3),
                    commands = setOf("HELLO", "CAPABILITIES", "STATUS", "DISPENSE", "STOP", "RESET", "HEARTBEAT")
                )
            )

            // Request DISPENSE using unsupported pump 4
            val unsupportedPayload = JSONObject().apply {
                put("order_id", "ORD-TEST-CAP")
                put("pumps", JSONArray().apply {
                    put(JSONObject().apply { put("pump_id", 4); put("duration_ms", 1500) })
                })
            }

            engine.executeTest(ProtocolCommand.DISPENSE, customPayload = unsupportedPayload, customRequestId = "REQ-CAP-REJECT").join()

            val state = engine.activeTest.value
            assertEquals("Pre-flight capability validation must fail locally", TestExecutionStatus.FAIL, state.status)
            assertTrue("Error message must explain unsupported pump", state.errorMessage?.contains("Pump ID 4 is not supported by Embedded capabilities") == true)
            assertTrue("Raw TX string must be empty because wire transmission was blocked locally", state.rawTxString.isEmpty())
            assertEquals("Wire transmission count must be 0", 0, mockTransport.schedulerEvents.size)
            assertFalse("Validation failure must NOT be retriable", state.isRetriable)
        } finally {
            engine.cleanup()
        }
    }
}
