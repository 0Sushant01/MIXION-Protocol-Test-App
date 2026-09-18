package com.mixion.protocoltest.core.transport

import com.mixion.protocoltest.core.protocol.CanonicalJson
import com.mixion.protocoltest.core.protocol.ConnectionState
import com.mixion.protocoltest.core.protocol.MachineOperationalState
import com.mixion.protocoltest.core.protocol.ProtocolCommand
import com.mixion.protocoltest.core.protocol.ProtocolErrorCode
import com.mixion.protocoltest.core.protocol.ProtocolStatus
import com.mixion.protocoltest.core.protocol.TrafficDirection
import com.mixion.protocoltest.core.protocol.TrafficLogEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class MockTransaction(
    val requestId: String,
    val command: String,
    var status: String,
    var phase1Frame: String?,
    var terminalFrame: String?,
    var isCompleted: Boolean = false
)

data class PumpScheduleEvent(
    val eventType: String, // "STARTED" or "COMPLETED"
    val pumpId: Int,
    val durationMs: Long,
    val elapsedVirtualTimeMs: Long
)

data class ActivePump(
    val pumpId: Int,
    val initialDurationMs: Long,
    var remainingDurationMs: Long
)

/**
 * High-fidelity Mock Embedded Controller simulating the MIXION Embedded Brain V1.0.
 * Implements CRC32 verification, error handling, state transitions,
 * session-level duplicate protection, and Section 39 discrete concurrent pump scheduling.
 */
class MockEmbeddedTransport(
    private val scope: CoroutineScope,
    private val dispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Default
) : TransportInterface {

    private val _connectionState = MutableStateFlow(ConnectionState.OFFLINE)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _rawTrafficFlow = MutableSharedFlow<TrafficLogEntry>(replay = 50)
    override val rawTrafficFlow: SharedFlow<TrafficLogEntry> = _rawTrafficFlow.asSharedFlow()

    private val _receivedFramesFlow = MutableSharedFlow<String>(replay = 10)
    override val receivedFramesFlow: SharedFlow<String> = _receivedFramesFlow.asSharedFlow()

    private var machineState = MachineOperationalState.IDLE
    private var activeDispenseJob: Job? = null
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    // Session duplicate protection & scheduler event tracking
    val transactionMap = java.util.concurrent.ConcurrentHashMap<String, MockTransaction>()
    val physicalExecutionCountMap = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>()
    val schedulerEvents = java.util.Collections.synchronizedList(mutableListOf<PumpScheduleEvent>())
    var timeScaleFactor: Double = 1.0

    override suspend fun connect(): Result<Unit> {
        _connectionState.value = ConnectionState.CONNECTING
        delay(100)
        _connectionState.value = ConnectionState.USB_CONNECTED
        delay(50)
        _connectionState.value = ConnectionState.READY
        emitLog(
            direction = TrafficDirection.INFO,
            content = "Mock Embedded Controller attached and ready (Simulating ESP32 V1.0)",
            bytes = 0
        )
        return Result.success(Unit)
    }

    override suspend fun disconnect() {
        activeDispenseJob?.cancel()
        activeDispenseJob = null
        machineState = MachineOperationalState.IDLE
        transactionMap.clear()
        physicalExecutionCountMap.clear()
        schedulerEvents.clear()
        _connectionState.value = ConnectionState.OFFLINE
        emitLog(
            direction = TrafficDirection.INFO,
            content = "Mock Embedded Controller disconnected",
            bytes = 0
        )
    }

    override fun isConnected(): Boolean {
        return _connectionState.value != ConnectionState.OFFLINE
    }

    override suspend fun sendFrame(
        ndjsonFrame: String,
        requestId: String?,
        command: String?
    ): Result<Unit> {
        val sendTime = System.currentTimeMillis()
        val txBytes = ndjsonFrame.toByteArray(Charsets.UTF_8)

        // Log TX
        emitLog(
            direction = TrafficDirection.TX,
            content = ndjsonFrame,
            bytes = txBytes.size,
            requestId = requestId,
            command = command
        )

        // Process frame on simulated embedded controller
        scope.launch(dispatcher) {
            handleReceivedFrameOnEmbedded(ndjsonFrame, sendTime)
        }

        return Result.success(Unit)
    }

    private suspend fun handleReceivedFrameOnEmbedded(frame: String, sendTime: Long) {
        // Embedded simulation latency: 15-35 ms
        delay((15..35).random().toLong())

        val clean = frame.trim()
        val parsedJson = try {
            JSONObject(clean)
        } catch (_: Exception) {
            // E002: Malformed JSON
            val errFrame = buildErrorResponse("UNKNOWN", "UNKNOWN", ProtocolErrorCode.E002, "Malformed JSON")
            sendResponseFromEmbedded(errFrame, sendTime, "UNKNOWN", "UNKNOWN", "ERROR")
            return
        }

        val reqId = parsedJson.optString("request_id", "REQ-UNKNOWN")
        val cmdStr = parsedJson.optString("command", "UNKNOWN")

        // 1. Verify CRC32
        val crcResult = CanonicalJson.verifyCrc(clean)
        if (!crcResult.isValid) {
            // Section 8.7: CRC mismatch -> Return E013 with valid response CRC
            val errFrame = buildErrorResponse(reqId, cmdStr, ProtocolErrorCode.E013, "CRC/integrity failure")
            sendResponseFromEmbedded(errFrame, sendTime, reqId, cmdStr, "ERROR")
            return
        }

        // 2. Validate Protocol Version
        val version = parsedJson.optInt("version", 0)
        if (version != 1) {
            val errFrame = buildErrorResponse(reqId, cmdStr, ProtocolErrorCode.E003, "Unsupported protocol version: $version")
            sendResponseFromEmbedded(errFrame, sendTime, reqId, cmdStr, "ERROR")
            return
        }

        // 3. Duplicate Transaction Protection (Protocol V1.0 Section 17 & 18):
        // The same request_id represents the same logical transaction.
        // If a duplicate arrives, return existing transaction state without executing twice.
        val existingTx = transactionMap[reqId]
        if (existingTx != null) {
            if (!existingTx.isCompleted && existingTx.phase1Frame != null) {
                // Operation is still in progress: re-emit existing ACCEPTED frame
                sendResponseFromEmbedded(existingTx.phase1Frame!!, sendTime, reqId, cmdStr, "ACCEPTED")
                return
            } else if (existingTx.isCompleted && existingTx.terminalFrame != null) {
                // Operation has completed: re-emit existing COMPLETED frame
                sendResponseFromEmbedded(existingTx.terminalFrame!!, sendTime, reqId, cmdStr, "COMPLETED")
                return
            }
        }

        // 4. Process Command
        when (cmdStr.uppercase()) {
            "HELLO" -> {
                val resp = JSONObject().apply {
                    put("version", 1)
                    put("type", "response")
                    put("request_id", reqId)
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
                transactionMap[reqId] = MockTransaction(reqId, "HELLO", "ACCEPTED", signedFrame, signedFrame, true)
                sendResponseFromEmbedded(signedFrame, sendTime, reqId, cmdStr, "ACCEPTED")
            }

            "CAPABILITIES" -> {
                val resp = JSONObject().apply {
                    put("version", 1)
                    put("type", "response")
                    put("request_id", reqId)
                    put("command", "CAPABILITIES")
                    put("status", "OK")
                    put("payload", JSONObject().apply {
                        put("pump_count", 6)
                        val pumps = JSONArray().apply {
                            put(1); put(2); put(3); put(4); put(5); put(6)
                        }
                        put("supported_pump_ids", pumps)
                        val cmds = JSONArray().apply {
                            put("HELLO"); put("CAPABILITIES"); put("STATUS")
                            put("GLASS_STATUS"); put("DISPENSE"); put("STOP"); put("RESET"); put("HEARTBEAT")
                        }
                        put("commands", cmds)
                    })
                }
                val signedFrame = CanonicalJson.createSignedFrame(resp)
                transactionMap[reqId] = MockTransaction(reqId, "CAPABILITIES", "OK", signedFrame, signedFrame, true)
                sendResponseFromEmbedded(signedFrame, sendTime, reqId, cmdStr, "OK")
            }

            "STATUS" -> {
                val resp = JSONObject().apply {
                    put("version", 1)
                    put("type", "response")
                    put("request_id", reqId)
                    put("command", "STATUS")
                    put("status", "OK")
                    put("payload", JSONObject().apply {
                        put("state", machineState.name)
                    })
                }
                val signedFrame = CanonicalJson.createSignedFrame(resp)
                transactionMap[reqId] = MockTransaction(reqId, "STATUS", "OK", signedFrame, signedFrame, true)
                sendResponseFromEmbedded(signedFrame, sendTime, reqId, cmdStr, "OK")
            }

            "GLASS_STATUS" -> {
                val resp = JSONObject().apply {
                    put("version", 1)
                    put("type", "response")
                    put("request_id", reqId)
                    put("command", "GLASS_STATUS")
                    put("status", "OK")
                    put("payload", JSONObject().apply {
                        put("glass_present", true)
                    })
                }
                val signedFrame = CanonicalJson.createSignedFrame(resp)
                transactionMap[reqId] = MockTransaction(reqId, "GLASS_STATUS", "OK", signedFrame, signedFrame, true)
                sendResponseFromEmbedded(signedFrame, sendTime, reqId, cmdStr, "OK")
            }

            "HEARTBEAT" -> {
                val resp = JSONObject().apply {
                    put("version", 1)
                    put("type", "response")
                    put("request_id", reqId)
                    put("command", "HEARTBEAT")
                    put("status", "OK")
                    put("payload", JSONObject().apply {
                        put("state", machineState.name)
                        put("power_mode", "direct")
                    })
                }
                val signedFrame = CanonicalJson.createSignedFrame(resp)
                transactionMap[reqId] = MockTransaction(reqId, "HEARTBEAT", "OK", signedFrame, signedFrame, true)
                sendResponseFromEmbedded(signedFrame, sendTime, reqId, cmdStr, "OK")
            }

            "STOP" -> {
                activeDispenseJob?.cancel()
                activeDispenseJob = null
                machineState = MachineOperationalState.STOPPED
                val resp = JSONObject().apply {
                    put("version", 1)
                    put("type", "response")
                    put("request_id", reqId)
                    put("command", "STOP")
                    put("status", "COMPLETED")
                    put("payload", JSONObject())
                }
                val signedFrame = CanonicalJson.createSignedFrame(resp)
                transactionMap[reqId] = MockTransaction(reqId, "STOP", "COMPLETED", signedFrame, signedFrame, true)
                sendResponseFromEmbedded(signedFrame, sendTime, reqId, cmdStr, "COMPLETED")
            }

            "RESET" -> {
                activeDispenseJob?.cancel()
                activeDispenseJob = null
                machineState = MachineOperationalState.IDLE
                transactionMap.clear()
                schedulerEvents.clear()
                val resp = JSONObject().apply {
                    put("version", 1)
                    put("type", "response")
                    put("request_id", reqId)
                    put("command", "RESET")
                    put("status", "COMPLETED")
                    put("payload", JSONObject())
                }
                val signedFrame = CanonicalJson.createSignedFrame(resp)
                sendResponseFromEmbedded(signedFrame, sendTime, reqId, cmdStr, "COMPLETED")
            }

            "DISPENSE" -> {
                handleDispenseCommand(parsedJson, reqId, sendTime)
            }

            else -> {
                val errFrame = buildErrorResponse(reqId, cmdStr, ProtocolErrorCode.E001, "Invalid command: $cmdStr")
                sendResponseFromEmbedded(errFrame, sendTime, reqId, cmdStr, "ERROR")
            }
        }
    }

    private suspend fun handleDispenseCommand(requestJson: JSONObject, reqId: String, sendTime: Long) {
        // Section 27: Check machine state
        if (machineState == MachineOperationalState.DISPENSING) {
            val errFrame = buildErrorResponse(reqId, "DISPENSE", ProtocolErrorCode.E005, "Machine busy: operation in progress")
            sendResponseFromEmbedded(errFrame, sendTime, reqId, "DISPENSE", "ERROR")
            return
        }

        val payload = requestJson.optJSONObject("payload")
        val pumpsArray = payload?.optJSONArray("pumps")
        if (pumpsArray == null || pumpsArray.length() == 0) {
            val errFrame = buildErrorResponse(reqId, "DISPENSE", ProtocolErrorCode.E004, "Invalid parameter: pumps array empty")
            sendResponseFromEmbedded(errFrame, sendTime, reqId, "DISPENSE", "ERROR")
            return
        }

        // Parse and validate pumps
        val pumpList = mutableListOf<Pair<Int, Long>>()
        val seenPumps = mutableSetOf<Int>()
        for (i in 0 until pumpsArray.length()) {
            val pObj = pumpsArray.getJSONObject(i)
            val pid = pObj.optInt("pump_id", -1)
            val dur = pObj.optLong("duration_ms", -1)
            if (pid <= 0 || dur <= 0 || seenPumps.contains(pid)) {
                val errFrame = buildErrorResponse(reqId, "DISPENSE", ProtocolErrorCode.E004, "Invalid pump parameters or duplicate pump_id: $pid")
                sendResponseFromEmbedded(errFrame, sendTime, reqId, "DISPENSE", "ERROR")
                return
            }
            seenPumps.add(pid)
            pumpList.add(Pair(pid, dur))
        }

        // Track simulated physical execution count (must be exactly 1 even if duplicates arrive)
        val execCounter = physicalExecutionCountMap.computeIfAbsent(reqId) { java.util.concurrent.atomic.AtomicInteger(0) }
        execCounter.incrementAndGet()

        // Section 17: Embedded commits and immediately returns ACCEPTED + CRC32
        val acceptedResp = JSONObject().apply {
            put("version", 1)
            put("type", "response")
            put("request_id", reqId)
            put("command", "DISPENSE")
            put("status", "ACCEPTED")
            put("payload", JSONObject())
        }
        val acceptedFrame = CanonicalJson.createSignedFrame(acceptedResp)
        val txRecord = MockTransaction(
            requestId = reqId,
            command = "DISPENSE",
            status = "ACCEPTED",
            phase1Frame = acceptedFrame,
            terminalFrame = null,
            isCompleted = false
        )
        transactionMap[reqId] = txRecord

        sendResponseFromEmbedded(acceptedFrame, sendTime, reqId, "DISPENSE", "ACCEPTED")

        // Section 18 & Section 39: Discrete concurrent pump execution simulation
        machineState = MachineOperationalState.DISPENSING
        _connectionState.value = ConnectionState.BUSY

        activeDispenseJob = scope.launch(dispatcher) {
            try {
                // Execute pumps with discrete max 3 concurrent pumps algorithm
                executeDiscreteMax3Scheduler(pumpList)

                // On completion: Send COMPLETED + CRC32
                machineState = MachineOperationalState.IDLE
                _connectionState.value = ConnectionState.READY

                val completedResp = JSONObject().apply {
                    put("version", 1)
                    put("type", "response")
                    put("request_id", reqId)
                    put("command", "DISPENSE")
                    put("status", "COMPLETED")
                    put("payload", JSONObject().apply {
                        put("result", "SUCCESS")
                    })
                }
                val completedFrame = CanonicalJson.createSignedFrame(completedResp)
                txRecord.status = "COMPLETED"
                txRecord.terminalFrame = completedFrame
                txRecord.isCompleted = true

                sendResponseFromEmbedded(completedFrame, sendTime, reqId, "DISPENSE", "COMPLETED")
            } catch (_: kotlinx.coroutines.CancellationException) {
                // Cancelled by STOP
            } catch (e: Exception) {
                machineState = MachineOperationalState.ERROR
                val errFrame = buildErrorResponse(reqId, "DISPENSE", ProtocolErrorCode.E006, "Pump execution failed: ${e.message}")
                txRecord.status = "ERROR"
                txRecord.terminalFrame = errFrame
                txRecord.isCompleted = true
                sendResponseFromEmbedded(errFrame, sendTime, reqId, "DISPENSE", "ERROR")
            }
        }
    }

    /**
     * Implements Section 39 Normative Rule:
     * - Maximum 3 active pumps running concurrently.
     * - Highest-duration pending pumps are started first.
     * - Tie-breaker: duration_ms descending, pump_id ascending.
     * - When any active pump completes, the next highest-duration pending pump starts immediately.
     * - Discrete event timeline is recorded in schedulerEvents.
     */
    suspend fun executeDiscreteMax3Scheduler(
        pumps: List<Pair<Int, Long>>,
        onEvent: ((PumpScheduleEvent) -> Unit)? = null
    ) {
        val pendingQueue = pumps.sortedWith(
            compareByDescending<Pair<Int, Long>> { it.second }.thenBy { it.first }
        ).toMutableList()

        val activeList = mutableListOf<ActivePump>()
        var virtualTimeMs = 0L

        // Fill initial 3 slots
        while (activeList.size < 3 && pendingQueue.isNotEmpty()) {
            val next = pendingQueue.removeAt(0)
            activeList.add(ActivePump(next.first, next.second, next.second))
            val event = PumpScheduleEvent("STARTED", next.first, next.second, virtualTimeMs)
            schedulerEvents.add(event)
            onEvent?.invoke(event)
        }

        while (activeList.isNotEmpty()) {
            val minRemaining = activeList.minOf { it.remainingDurationMs }
            val delayDuration = if (timeScaleFactor != 1.0) (minRemaining * timeScaleFactor).toLong() else minRemaining
            delay(delayDuration)
            virtualTimeMs += minRemaining

            activeList.forEach { it.remainingDurationMs -= minRemaining }

            val completed = activeList.filter { it.remainingDurationMs <= 0L }.sortedBy { it.pumpId }
            for (p in completed) {
                val event = PumpScheduleEvent("COMPLETED", p.pumpId, p.initialDurationMs, virtualTimeMs)
                schedulerEvents.add(event)
                onEvent?.invoke(event)
                activeList.remove(p)
            }

            while (activeList.size < 3 && pendingQueue.isNotEmpty()) {
                val next = pendingQueue.removeAt(0)
                activeList.add(ActivePump(next.first, next.second, next.second))
                val event = PumpScheduleEvent("STARTED", next.first, next.second, virtualTimeMs)
                schedulerEvents.add(event)
                onEvent?.invoke(event)
            }
        }
    }

    private fun buildErrorResponse(
        reqId: String,
        cmd: String,
        code: ProtocolErrorCode,
        message: String,
        failedPumps: List<Int> = emptyList()
    ): String {
        val json = JSONObject().apply {
            put("version", 1)
            put("type", "response")
            put("request_id", reqId)
            put("command", cmd)
            put("status", "ERROR")
            put("error", JSONObject().apply {
                put("code", code.code)
                put("message", message)
                if (failedPumps.isNotEmpty()) {
                    put("details", JSONObject().apply {
                        val arr = JSONArray()
                        failedPumps.forEach { arr.put(it) }
                        put("failed_pumps", arr)
                    })
                }
            })
        }
        return CanonicalJson.createSignedFrame(json)
    }

    private suspend fun sendResponseFromEmbedded(
        frame: String,
        sendTime: Long,
        reqId: String,
        cmd: String,
        status: String
    ) {
        val latency = System.currentTimeMillis() - sendTime
        val rxBytes = frame.toByteArray(Charsets.UTF_8)

        // Log RX
        emitLog(
            direction = TrafficDirection.RX,
            content = frame,
            bytes = rxBytes.size,
            latencyMs = latency,
            requestId = reqId,
            command = cmd,
            status = status
        )

        // Emit to received frames flow
        _receivedFramesFlow.emit(frame)
    }

    private suspend fun emitLog(
        direction: TrafficDirection,
        content: String,
        bytes: Int,
        latencyMs: Long? = null,
        requestId: String? = null,
        command: String? = null,
        status: String? = null
    ) {
        val entry = TrafficLogEntry(
            timestamp = dateFormat.format(Date()),
            direction = direction,
            bytesCount = bytes,
            asciiContent = content,
            hexContent = HexUtil.toHexString(content),
            latencyMs = latencyMs,
            requestId = requestId,
            command = command,
            status = status
        )
        _rawTrafficFlow.emit(entry)
    }
}
