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
            assertTrue(active.rawRxString.contains("ACCEPTED"))
            assertTrue(active.rawRxString.contains("COMPLETED"))
            assertTrue(active.rawRxString.contains("SUCCESS"))
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
}
