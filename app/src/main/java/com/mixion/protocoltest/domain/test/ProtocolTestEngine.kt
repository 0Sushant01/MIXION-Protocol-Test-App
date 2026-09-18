package com.mixion.protocoltest.domain.test

import com.mixion.protocoltest.core.protocol.CanonicalJson
import com.mixion.protocoltest.core.protocol.ConnectionState
import com.mixion.protocoltest.core.protocol.ProtocolCommand
import com.mixion.protocoltest.core.protocol.ProtocolRegistry
import com.mixion.protocoltest.core.protocol.ProtocolStatus
import com.mixion.protocoltest.core.protocol.ProtocolValidator
import com.mixion.protocoltest.core.protocol.TestHistoryItem
import com.mixion.protocoltest.core.protocol.TrafficDirection
import com.mixion.protocoltest.core.protocol.TrafficLogEntry
import com.mixion.protocoltest.core.protocol.ValidationReport
import com.mixion.protocoltest.core.transport.HexUtil
import com.mixion.protocoltest.core.transport.MockEmbeddedTransport
import com.mixion.protocoltest.core.transport.TransportInterface
import com.mixion.protocoltest.core.transport.UsbSerialTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class TestExecutionStatus {
    IDLE,
    SENDING,
    WAITING_RESPONSE,
    DISPENSE_ACCEPTED_EXECUTING,
    PASS,
    FAIL,
    TIMEOUT,
    ERROR
}

data class ActiveTestState(
    val status: TestExecutionStatus = TestExecutionStatus.IDLE,
    val command: ProtocolCommand = ProtocolCommand.HELLO,
    val requestId: String = "",
    val rawTxString: String = "",
    val rawTxHex: String = "",
    val rawRxString: String = "",
    val rawRxHex: String = "",
    val latencyMs: Long = 0,
    val validationReport: ValidationReport? = null,
    val requestValidationReport: ValidationReport? = null,
    val dispensePhase1RxFrame: String? = null,
    val dispensePhase2RxFrame: String? = null,
    val errorMessage: String? = null,
    val isRetriable: Boolean = false,
    val isRetryAttempt: Boolean = false,
    val wireTransmissionCount: Int = 0
)

data class RetriableRequest(
    val command: ProtocolCommand,
    val requestId: String,
    val customPayload: JSONObject?,
    val customCommandName: String?,
    val timeoutMs: Long?
)

class ProtocolTestEngine(
    private val scope: CoroutineScope,
    val usbTransport: TransportInterface,
    val mockTransport: MockEmbeddedTransport,
    private val dispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Default
) {

    private val _isMockMode = MutableStateFlow(true)
    val isMockMode: StateFlow<Boolean> = _isMockMode.asStateFlow()

    val activeTransport: TransportInterface
        get() = if (_isMockMode.value) mockTransport else usbTransport

    val connectionState: StateFlow<ConnectionState>
        get() = if (_isMockMode.value) mockTransport.connectionState else usbTransport.connectionState

    private val _activeTest = MutableStateFlow(ActiveTestState())
    val activeTest: StateFlow<ActiveTestState> = _activeTest.asStateFlow()

    private val _testHistory = MutableStateFlow<List<TestHistoryItem>>(emptyList())
    val testHistory: StateFlow<List<TestHistoryItem>> = _testHistory.asStateFlow()

    private val _trafficLogs = MutableStateFlow<List<TrafficLogEntry>>(emptyList())
    val trafficLogs: StateFlow<List<TrafficLogEntry>> = _trafficLogs.asStateFlow()

    // Capability discovery state representing currently connected Embedded session
    private val _discoveredCapabilities = MutableStateFlow<com.mixion.protocoltest.core.protocol.DiscoveredCapabilities?>(null)
    val discoveredCapabilities: StateFlow<com.mixion.protocoltest.core.protocol.DiscoveredCapabilities?> = _discoveredCapabilities.asStateFlow()

    fun setDiscoveredCapabilities(capabilities: com.mixion.protocoltest.core.protocol.DiscoveredCapabilities?) {
        _discoveredCapabilities.value = capabilities
    }

    // Retriable request cache preserving exact request_id across retries
    private var lastRetriableRequest: RetriableRequest? = null

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    private var testJob: Job? = null
    private val engineJob = kotlinx.coroutines.SupervisorJob()
    private val engineScope = CoroutineScope(scope.coroutineContext + engineJob)

    init {
        // Collect traffic from mock transport
        engineScope.launch(dispatcher) {
            mockTransport.rawTrafficFlow.collect { entry ->
                if (_isMockMode.value) appendTrafficLog(entry)
            }
        }
        // Collect traffic from usb transport
        engineScope.launch(dispatcher) {
            usbTransport.rawTrafficFlow.collect { entry ->
                if (!_isMockMode.value) appendTrafficLog(entry)
            }
        }
    }

    fun cleanup() {
        engineJob.cancel()
    }

    fun setMockMode(useMock: Boolean) {
        if (_isMockMode.value != useMock) {
            _isMockMode.value = useMock
            _discoveredCapabilities.value = null
            engineScope.launch(dispatcher) {
                if (useMock) {
                    usbTransport.disconnect()
                    mockTransport.connect()
                } else {
                    mockTransport.disconnect()
                    usbTransport.connect()
                }
            }
        }
    }

    private fun appendTrafficLog(entry: TrafficLogEntry) {
        val current = _trafficLogs.value.toMutableList()
        if (current.size > 200) current.removeAt(0)
        current.add(entry)
        _trafficLogs.value = current
    }

    fun clearTrafficLogs() {
        _trafficLogs.value = emptyList()
    }

    fun clearHistory() {
        _testHistory.value = emptyList()
    }

    fun clearCurrentTest() {
        testJob?.cancel()
        testJob = null
        lastRetriableRequest = null
        _activeTest.value = ActiveTestState()
    }

    fun disconnect() {
        scope.launch {
            activeTransport.disconnect()
            _discoveredCapabilities.value = null
            lastRetriableRequest = null
        }
    }

    fun executeTest(
        command: ProtocolCommand,
        customPayload: JSONObject? = null,
        customRequestId: String? = null,
        customCommandName: String? = null,
        timeoutMs: Long? = null,
        isRetry: Boolean = false
    ): Job {
        testJob?.cancel()
        val job = scope.launch(dispatcher) {
            val reqId = customRequestId ?: ProtocolRegistry.nextRequestId()
            val requestJson = ProtocolRegistry.buildRequest(
                command = command,
                requestId = reqId,
                customPayload = customPayload,
                customCommandName = customCommandName
            )

            val signedFrame = CanonicalJson.createSignedFrame(requestJson)
            val txHex = HexUtil.toHexString(signedFrame)

            // Local Pre-Flight Gating: Validate against discovered capabilities before transmission!
            val reqValidation = ProtocolValidator.validateRequest(
                rawFrame = signedFrame,
                expectedCommand = command,
                capabilities = _discoveredCapabilities.value
            )

            if (!reqValidation.isPass) {
                val errorMsg = "Local validation rejected request: ${reqValidation.failureReasons.joinToString("; ")}"
                _activeTest.value = ActiveTestState(
                    status = TestExecutionStatus.FAIL,
                    command = command,
                    requestId = reqId,
                    rawTxString = "", // Blocked locally: not transmitted on wire
                    rawTxHex = "",
                    requestValidationReport = reqValidation,
                    errorMessage = errorMsg,
                    isRetriable = false, // Validation rejections must NOT be retried!
                    isRetryAttempt = isRetry,
                    wireTransmissionCount = 0
                )
                lastRetriableRequest = null
                recordTestHistory(
                    command = customCommandName ?: command.commandName,
                    requestId = reqId,
                    isPass = false,
                    latencyMs = 0,
                    txJson = signedFrame,
                    rxJson = "",
                    txHex = txHex,
                    rxHex = "",
                    summary = "REJECTED (Local Gating): ${reqValidation.failureReasons.firstOrNull()}",
                    failureReasons = reqValidation.failureReasons
                )
                return@launch
            }

            _activeTest.value = ActiveTestState(
                status = TestExecutionStatus.SENDING,
                command = command,
                requestId = reqId,
                rawTxString = signedFrame,
                rawTxHex = txHex,
                requestValidationReport = reqValidation,
                isRetriable = false,
                isRetryAttempt = isRetry,
                wireTransmissionCount = 1
            )

            val def = ProtocolRegistry.getDefinition(command)
            val actualTimeout = timeoutMs ?: def.defaultTimeoutMs
            val sendTime = System.currentTimeMillis()

            val sendResult = activeTransport.sendFrame(signedFrame, reqId, command.commandName)
            if (sendResult.isFailure) {
                lastRetriableRequest = RetriableRequest(command, reqId, customPayload, customCommandName, timeoutMs)
                _activeTest.value = _activeTest.value.copy(
                    status = TestExecutionStatus.ERROR,
                    errorMessage = "Send failed: ${sendResult.exceptionOrNull()?.message}",
                    isRetriable = true
                )
                return@launch
            }

            _activeTest.value = _activeTest.value.copy(status = TestExecutionStatus.WAITING_RESPONSE)

            // Special handling for DISPENSE: Expects ACCEPTED first, then COMPLETED or ERROR
            if (command == ProtocolCommand.DISPENSE) {
                handleDispenseMultiResponse(signedFrame, txHex, reqId, sendTime, actualTimeout, customPayload, customCommandName, timeoutMs)
            } else {
                handleStandardResponse(signedFrame, txHex, command, reqId, sendTime, actualTimeout, customPayload, customCommandName, timeoutMs)
            }
        }
        testJob = job
        return job
    }

    /**
     * Retries the previous timed-out or transport-failed test.
     * Crucial Protocol V1.0 Invariant: Strictly reuses the exact same request_id!
     */
    fun retryLastTest(): Job? {
        val req = lastRetriableRequest ?: return null
        return executeTest(
            command = req.command,
            customPayload = req.customPayload,
            customRequestId = req.requestId, // Crucial: Reuses original request_id!
            customCommandName = req.customCommandName,
            timeoutMs = req.timeoutMs,
            isRetry = true
        )
    }

    private suspend fun handleStandardResponse(
        txFrame: String,
        txHex: String,
        command: ProtocolCommand,
        requestId: String,
        sendTime: Long,
        timeoutMs: Long,
        customPayload: JSONObject?,
        customCommandName: String?,
        originalTimeoutMs: Long?
    ) {
        val resultFrame = withTimeoutOrNull(timeoutMs) {
            activeTransport.receivedFramesFlow.first { frame ->
                try {
                    val json = JSONObject(frame.trim())
                    val rid = json.optString("request_id")
                    command == ProtocolCommand.CUSTOM || rid == requestId
                } catch (_: Exception) {
                    false
                }
            }
        }

        val latency = System.currentTimeMillis() - sendTime

        if (resultFrame == null) {
            lastRetriableRequest = RetriableRequest(command, requestId, customPayload, customCommandName, originalTimeoutMs)
            _activeTest.value = _activeTest.value.copy(
                status = TestExecutionStatus.TIMEOUT,
                latencyMs = latency,
                errorMessage = "No valid response received within $timeoutMs ms.",
                isRetriable = true
            )
            recordTestHistory(
                command = command.commandName,
                requestId = requestId,
                isPass = false,
                latencyMs = latency,
                txJson = txFrame,
                rxJson = "",
                txHex = txHex,
                rxHex = "",
                summary = "TIMEOUT: No response within $timeoutMs ms",
                failureReasons = listOf("Timeout exceeded ($timeoutMs ms)")
            )
            return
        }

        val rxHex = HexUtil.toHexString(resultFrame)
        val report = ProtocolValidator.validateResponse(resultFrame, requestId, command)

        // If response arrived, clear retry unless it's a transient transport failure
        lastRetriableRequest = null

        _activeTest.value = _activeTest.value.copy(
            status = if (report.isPass) TestExecutionStatus.PASS else TestExecutionStatus.FAIL,
            rawRxString = resultFrame,
            rawRxHex = rxHex,
            latencyMs = latency,
            validationReport = report,
            errorMessage = if (report.isPass) null else report.failureReasons.joinToString("\n"),
            isRetriable = false
        )

        // Capability Caching & Session Lifecycle Updates
        if (report.isPass && report.parsedMessage?.status == ProtocolStatus.OK) {
            if (command == ProtocolCommand.CAPABILITIES) {
                val payload = report.parsedMessage.payload
                val pumpCount = payload.optInt("pump_count", 0)
                val supportedPumps = mutableSetOf<Int>()
                payload.optJSONArray("supported_pump_ids")?.let { arr ->
                    for (i in 0 until arr.length()) supportedPumps.add(arr.getInt(i))
                }
                val cmds = mutableSetOf<String>()
                payload.optJSONArray("commands")?.let { arr ->
                    for (i in 0 until arr.length()) cmds.add(arr.getString(i))
                }
                _discoveredCapabilities.value = com.mixion.protocoltest.core.protocol.DiscoveredCapabilities(
                    pumpCount = pumpCount,
                    supportedPumpIds = supportedPumps,
                    commands = cmds
                )
            } else if (command == ProtocolCommand.RESET) {
                // Protocol V1.0: RESET invalidates active session state, requiring fresh handshake & discovery
                _discoveredCapabilities.value = null
            }
        }

        recordTestHistory(
            command = command.commandName,
            requestId = requestId,
            isPass = report.isPass,
            latencyMs = latency,
            txJson = txFrame,
            rxJson = resultFrame,
            txHex = txHex,
            rxHex = rxHex,
            summary = if (report.isPass) "PASS" else "FAIL: ${report.failureReasons.firstOrNull() ?: "Validation failure"}",
            failureReasons = report.failureReasons
        )
    }

    private suspend fun handleDispenseMultiResponse(
        txFrame: String,
        txHex: String,
        requestId: String,
        sendTime: Long,
        timeoutMs: Long,
        customPayload: JSONObject?,
        customCommandName: String?,
        originalTimeoutMs: Long?
    ) {
        // Step 1: Wait for ACCEPTED response (within 3500 ms)
        val acceptedFrame = withTimeoutOrNull(3500) {
            activeTransport.receivedFramesFlow.first { frame ->
                try {
                    val json = JSONObject(frame.trim())
                    json.optString("request_id") == requestId && json.optString("status") == "ACCEPTED"
                } catch (_: Exception) {
                    false
                }
            }
        }

        if (acceptedFrame == null) {
            val latency = System.currentTimeMillis() - sendTime
            lastRetriableRequest = RetriableRequest(ProtocolCommand.DISPENSE, requestId, customPayload, customCommandName, originalTimeoutMs)
            _activeTest.value = _activeTest.value.copy(
                status = TestExecutionStatus.TIMEOUT,
                latencyMs = latency,
                errorMessage = "Expected initial 'ACCEPTED' response within 3500 ms (Timeout).",
                isRetriable = true
            )
            recordTestHistory(
                command = "DISPENSE",
                requestId = requestId,
                isPass = false,
                latencyMs = latency,
                txJson = txFrame,
                rxJson = "",
                txHex = txHex,
                rxHex = "",
                summary = "TIMEOUT: Missing initial ACCEPTED response",
                failureReasons = listOf("Embedded did not return ACCEPTED status frame within 3500ms")
            )
            return
        }

        val acceptedReport = ProtocolValidator.validateResponse(acceptedFrame, requestId, ProtocolCommand.DISPENSE)
        if (!acceptedReport.isPass) {
            val latency = System.currentTimeMillis() - sendTime
            lastRetriableRequest = null
            _activeTest.value = _activeTest.value.copy(
                status = TestExecutionStatus.FAIL,
                rawRxString = acceptedFrame,
                rawRxHex = HexUtil.toHexString(acceptedFrame),
                latencyMs = latency,
                validationReport = acceptedReport,
                errorMessage = "ACCEPTED frame validation failed: ${acceptedReport.failureReasons.joinToString("; ")}",
                isRetriable = false
            )
            return
        }

        // Step 2: Now wait for terminal COMPLETED or ERROR response
        _activeTest.value = _activeTest.value.copy(
            status = TestExecutionStatus.DISPENSE_ACCEPTED_EXECUTING,
            rawRxString = acceptedFrame,
            dispensePhase1RxFrame = acceptedFrame,
            dispensePhase2RxFrame = null,
            latencyMs = System.currentTimeMillis() - sendTime,
            isRetriable = false
        )

        val terminalFrame = withTimeoutOrNull(timeoutMs) {
            activeTransport.receivedFramesFlow.first { frame ->
                try {
                    val json = JSONObject(frame.trim())
                    val rid = json.optString("request_id")
                    val st = json.optString("status")
                    rid == requestId && (st == "COMPLETED" || st == "ERROR")
                } catch (_: Exception) {
                    false
                }
            }
        }

        val totalLatency = System.currentTimeMillis() - sendTime

        if (terminalFrame == null) {
            lastRetriableRequest = RetriableRequest(ProtocolCommand.DISPENSE, requestId, customPayload, customCommandName, originalTimeoutMs)
            _activeTest.value = _activeTest.value.copy(
                status = TestExecutionStatus.TIMEOUT,
                latencyMs = totalLatency,
                errorMessage = "Dispense timed out waiting for COMPLETED/ERROR within $timeoutMs ms.",
                isRetriable = true
            )
            recordTestHistory(
                command = "DISPENSE",
                requestId = requestId,
                isPass = false,
                latencyMs = totalLatency,
                txJson = txFrame,
                rxJson = acceptedFrame,
                txHex = txHex,
                rxHex = HexUtil.toHexString(acceptedFrame),
                summary = "TIMEOUT: Dispense execution exceeded $timeoutMs ms",
                failureReasons = listOf("Timeout waiting for COMPLETED")
            )
            return
        }

        val terminalReport = ProtocolValidator.validateResponse(terminalFrame, requestId, ProtocolCommand.DISPENSE)
        lastRetriableRequest = null

        _activeTest.value = _activeTest.value.copy(
            status = if (terminalReport.isPass) TestExecutionStatus.PASS else TestExecutionStatus.FAIL,
            rawRxString = terminalFrame,
            rawRxHex = HexUtil.toHexString(terminalFrame),
            dispensePhase1RxFrame = acceptedFrame,
            dispensePhase2RxFrame = terminalFrame,
            latencyMs = totalLatency,
            validationReport = terminalReport,
            errorMessage = if (terminalReport.isPass) null else terminalReport.failureReasons.joinToString("\n"),
            isRetriable = false
        )

        recordTestHistory(
            command = "DISPENSE",
            requestId = requestId,
            isPass = terminalReport.isPass,
            latencyMs = totalLatency,
            txJson = txFrame,
            rxJson = "${acceptedFrame.trimEnd()}\n${terminalFrame.trimEnd()}\n",
            txHex = txHex,
            rxHex = HexUtil.toHexString(terminalFrame),
            summary = if (terminalReport.isPass) "PASS: ACCEPTED -> COMPLETED" else "FAIL: ${terminalReport.failureReasons.firstOrNull()}",
            failureReasons = terminalReport.failureReasons
        )
    }

    private fun recordTestHistory(
        command: String,
        requestId: String,
        isPass: Boolean,
        latencyMs: Long,
        txJson: String,
        rxJson: String,
        txHex: String,
        rxHex: String,
        summary: String,
        failureReasons: List<String>
    ) {
        val item = TestHistoryItem(
            timestamp = dateFormat.format(Date()),
            command = command,
            requestId = requestId,
            isPass = isPass,
            latencyMs = latencyMs,
            requestJson = txJson,
            responseJson = rxJson,
            rawTxHex = txHex,
            rawRxHex = rxHex,
            validationSummary = summary,
            failureReasons = failureReasons
        )
        val list = _testHistory.value.toMutableList()
        list.add(0, item) // newest first
        if (list.size > 100) list.removeAt(list.size - 1)
        _testHistory.value = list
    }
}
