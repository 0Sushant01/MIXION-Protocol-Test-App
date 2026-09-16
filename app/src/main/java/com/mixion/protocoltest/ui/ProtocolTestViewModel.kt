package com.mixion.protocoltest.ui

import android.app.Application
import android.hardware.usb.UsbDevice
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mixion.protocoltest.core.protocol.CanonicalJson
import com.mixion.protocoltest.core.protocol.ConnectionState
import com.mixion.protocoltest.core.protocol.ProtocolCommand
import com.mixion.protocoltest.core.protocol.ProtocolRegistry
import com.mixion.protocoltest.core.protocol.PumpOperation
import com.mixion.protocoltest.core.protocol.TestHistoryItem
import com.mixion.protocoltest.core.protocol.TrafficLogEntry
import com.mixion.protocoltest.core.transport.DiscoveredUsbDevice
import com.mixion.protocoltest.core.transport.HexUtil
import com.mixion.protocoltest.core.transport.MockEmbeddedTransport
import com.mixion.protocoltest.core.transport.UsbSerialTransport
import com.mixion.protocoltest.domain.test.ActiveTestState
import com.mixion.protocoltest.domain.test.ProtocolTestEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

class ProtocolTestViewModel(application: Application) : AndroidViewModel(application) {

    val usbTransport = UsbSerialTransport(application.applicationContext, viewModelScope)
    val mockTransport = MockEmbeddedTransport(viewModelScope)
    val engine = ProtocolTestEngine(viewModelScope, usbTransport, mockTransport)

    val isMockMode: StateFlow<Boolean> = engine.isMockMode
    val connectionState: StateFlow<ConnectionState> = engine.connectionState
    val activeTest: StateFlow<ActiveTestState> = engine.activeTest
    val testHistory: StateFlow<List<TestHistoryItem>> = engine.testHistory
    val trafficLogs: StateFlow<List<TrafficLogEntry>> = engine.trafficLogs

    // USB Device Management
    private val _discoveredDevices = MutableStateFlow<List<DiscoveredUsbDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<DiscoveredUsbDevice>> = _discoveredDevices.asStateFlow()

    private val _selectedDevice = MutableStateFlow<DiscoveredUsbDevice?>(null)
    val selectedDevice: StateFlow<DiscoveredUsbDevice?> = _selectedDevice.asStateFlow()

    private val _baudRate = MutableStateFlow(115200)
    val baudRate: StateFlow<Int> = _baudRate.asStateFlow()

    // Command & Request Builder State
    private val _selectedCommand = MutableStateFlow(ProtocolCommand.HELLO)
    val selectedCommand: StateFlow<ProtocolCommand> = _selectedCommand.asStateFlow()

    private val _customRequestId = MutableStateFlow("")
    val customRequestId: StateFlow<String> = _customRequestId.asStateFlow()

    // Dispense Parameters
    private val _dispenseOrderId = MutableStateFlow("ORD-10452")
    val dispenseOrderId: StateFlow<String> = _dispenseOrderId.asStateFlow()

    private val _dispensePumps = MutableStateFlow(
        listOf(
            PumpOperation(pumpId = 1, durationMs = 2000),
            PumpOperation(pumpId = 3, durationMs = 1500),
            PumpOperation(pumpId = 5, durationMs = 3000)
        )
    )
    val dispensePumps: StateFlow<List<PumpOperation>> = _dispensePumps.asStateFlow()

    // Hello Parameters
    private val _helloDeviceName = MutableStateFlow("MIXION-BACKEND")
    val helloDeviceName: StateFlow<String> = _helloDeviceName.asStateFlow()

    // Custom Raw Parameters
    private val _customCommandName = MutableStateFlow("CUSTOM_CMD")
    val customCommandName: StateFlow<String> = _customCommandName.asStateFlow()

    private val _customPayloadJson = MutableStateFlow("{\n  \"example_key\": \"example_value\"\n}")
    val customPayloadJson: StateFlow<String> = _customPayloadJson.asStateFlow()

    // UI Dialogs
    private val _showDispenseConfirmation = MutableStateFlow(false)
    val showDispenseConfirmation: StateFlow<Boolean> = _showDispenseConfirmation.asStateFlow()

    private val _selectedHistoryDetails = MutableStateFlow<TestHistoryItem?>(null)
    val selectedHistoryDetails: StateFlow<TestHistoryItem?> = _selectedHistoryDetails.asStateFlow()

    init {
        // Initial setup
        viewModelScope.launch {
            if (isMockMode.value) {
                mockTransport.connect()
            } else {
                refreshUsbDevices()
            }
        }
    }

    fun setMockMode(enabled: Boolean) {
        engine.setMockMode(enabled)
        if (!enabled) {
            refreshUsbDevices()
        }
    }

    fun refreshUsbDevices() {
        val list = usbTransport.scanDevices()
        _discoveredDevices.value = list
        if (_selectedDevice.value == null || !list.any { it.deviceName == _selectedDevice.value?.deviceName }) {
            _selectedDevice.value = list.firstOrNull { it.isCandidate } ?: list.firstOrNull()
        }
    }

    fun selectUsbDevice(device: DiscoveredUsbDevice) {
        _selectedDevice.value = device
        usbTransport.setSelectedDevice(device.device)
    }

    fun setBaudRate(rate: Int) {
        _baudRate.value = rate
        usbTransport.setBaudRate(rate)
    }

    fun connectUsb() {
        val dev = _selectedDevice.value ?: return
        if (!dev.hasPermission) {
            usbTransport.requestPermission(dev.device) { granted ->
                if (granted) {
                    refreshUsbDevices()
                    viewModelScope.launch { usbTransport.connect() }
                }
            }
        } else {
            viewModelScope.launch { usbTransport.connect() }
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            engine.activeTransport.disconnect()
        }
    }

    fun setSelectedCommand(command: ProtocolCommand) {
        _selectedCommand.value = command
    }

    fun setCustomRequestId(id: String) {
        _customRequestId.value = id
    }

    fun setDispenseOrderId(orderId: String) {
        _dispenseOrderId.value = orderId
    }

    fun addDispensePump(pumpId: Int, durationMs: Long) {
        val current = _dispensePumps.value.toMutableList()
        if (!current.any { it.pumpId == pumpId }) {
            current.add(PumpOperation(pumpId, durationMs))
            _dispensePumps.value = current
        }
    }

    fun removeDispensePump(index: Int) {
        val current = _dispensePumps.value.toMutableList()
        if (index in current.indices) {
            current.removeAt(index)
            _dispensePumps.value = current
        }
    }

    fun setHelloDeviceName(name: String) {
        _helloDeviceName.value = name
    }

    fun setCustomCommandName(name: String) {
        _customCommandName.value = name
    }

    fun setCustomPayloadJson(json: String) {
        _customPayloadJson.value = json
    }

    fun showDispenseConfirmation(show: Boolean) {
        _showDispenseConfirmation.value = show
    }

    fun selectHistoryItem(item: TestHistoryItem?) {
        _selectedHistoryDetails.value = item
    }

    fun clearLogs() {
        engine.clearTrafficLogs()
    }

    fun clearHistory() {
        engine.clearHistory()
    }

    fun clearCurrentTest() {
        engine.clearCurrentTest()
    }

    /**
     * Computes the payload for the currently selected command.
     */
    fun buildCurrentPayload(): JSONObject {
        return when (_selectedCommand.value) {
            ProtocolCommand.HELLO -> {
                JSONObject().apply {
                    put("device", _helloDeviceName.value.ifBlank { "MIXION-BACKEND" })
                    put("protocol_version", 1)
                }
            }
            ProtocolCommand.DISPENSE -> {
                JSONObject().apply {
                    put("order_id", _dispenseOrderId.value.ifBlank { "ORD-10452" })
                    val arr = JSONArray()
                    for (pump in _dispensePumps.value) {
                        arr.put(pump.toJsonObject())
                    }
                    put("pumps", arr)
                }
            }
            ProtocolCommand.CUSTOM -> {
                try {
                    JSONObject(_customPayloadJson.value.trim())
                } catch (_: Exception) {
                    JSONObject()
                }
            }
            else -> JSONObject()
        }
    }

    /**
     * Preview of current canonical JSON request without sending.
     */
    fun getRequestPreview(): RequestPreviewData {
        val cmd = _selectedCommand.value
        val reqId = _customRequestId.value.ifBlank { ProtocolRegistry.nextRequestId() }
        val payload = buildCurrentPayload()
        val customName = if (cmd == ProtocolCommand.CUSTOM) _customCommandName.value else null

        val reqObj = ProtocolRegistry.buildRequest(
            command = cmd,
            requestId = reqId,
            customPayload = payload,
            customCommandName = customName
        )

        val signedFrame = CanonicalJson.createSignedFrame(reqObj)
        val canonicalWithoutCrc = CanonicalJson.canonicalize(reqObj, excludeCrc = true)
        val crc = reqObj.optString("crc32")
        val rawHex = HexUtil.toHexString(signedFrame)

        return RequestPreviewData(
            requestId = reqId,
            commandName = customName ?: cmd.commandName,
            canonicalWithoutCrc = canonicalWithoutCrc,
            crc32 = crc,
            finalNdjsonFrame = signedFrame,
            rawTxHex = rawHex
        )
    }

    fun sendRequest() {
        val cmd = _selectedCommand.value
        if (cmd == ProtocolCommand.DISPENSE && !_showDispenseConfirmation.value) {
            _showDispenseConfirmation.value = true
            return
        }

        val preview = getRequestPreview()
        viewModelScope.launch {
            engine.executeTest(
                command = cmd,
                customPayload = buildCurrentPayload(),
                customRequestId = preview.requestId,
                customCommandName = if (cmd == ProtocolCommand.CUSTOM) _customCommandName.value else null
            )
        }
    }

    fun confirmAndSendDispense() {
        _showDispenseConfirmation.value = false
        val preview = getRequestPreview()
        viewModelScope.launch {
            engine.executeTest(
                command = ProtocolCommand.DISPENSE,
                customPayload = buildCurrentPayload(),
                customRequestId = preview.requestId
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        usbTransport.cleanup()
    }
}

data class RequestPreviewData(
    val requestId: String,
    val commandName: String,
    val canonicalWithoutCrc: String,
    val crc32: String,
    val finalNdjsonFrame: String,
    val rawTxHex: String
)
