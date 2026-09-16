package com.mixion.protocoltest.core.transport

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.mixion.protocoltest.core.protocol.ConnectionState
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class DiscoveredUsbDevice(
    val device: UsbDevice,
    val deviceName: String,
    val vendorId: Int,
    val productId: Int,
    val manufacturerName: String?,
    val productName: String?,
    val serialNumber: String?,
    val isCandidate: Boolean,
    val hasPermission: Boolean
)

class UsbSerialTransport(
    private val context: Context,
    private val scope: CoroutineScope
) : TransportInterface {

    companion object {
        private const val TAG = "UsbSerialTransport"
        const val ACTION_USB_PERMISSION = "com.mixion.protocoltest.USB_PERMISSION"

        val KNOWN_CHIPSETS_VID = setOf(
            0x303A, // Espressif (ESP32-S2/S3/C3)
            0x10C4, // Silicon Labs CP210x
            0x1A86, // WCH CH340 / CH341
            0x0403, // FTDI
            0x067B, // Prolific PL2303
            0x2E8A, // Raspberry Pi Pico RP2040
            0x2341  // Arduino
        )
    }

    private val usbManager: UsbManager? = context.getSystemService(Context.USB_SERVICE) as? UsbManager

    private val _connectionState = MutableStateFlow(ConnectionState.OFFLINE)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _rawTrafficFlow = MutableSharedFlow<TrafficLogEntry>(replay = 50)
    override val rawTrafficFlow: SharedFlow<TrafficLogEntry> = _rawTrafficFlow.asSharedFlow()

    private val _receivedFramesFlow = MutableSharedFlow<String>(replay = 10)
    override val receivedFramesFlow: SharedFlow<String> = _receivedFramesFlow.asSharedFlow()

    private var serialPort: UsbSerialPort? = null
    private var selectedDevice: UsbDevice? = null
    private var readJob: Job? = null
    private var baudRate = 115200
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private var permissionCallback: ((Boolean) -> Unit)? = null

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            when (action) {
                ACTION_USB_PERMISSION -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    Log.i(TAG, "USB Permission result: granted=$granted")
                    permissionCallback?.invoke(granted)
                    permissionCallback = null
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    if (device != null && selectedDevice?.deviceName == device.deviceName) {
                        Log.w(TAG, "Active USB device detached: ${device.deviceName}")
                        scope.launch { disconnect() }
                    }
                }
            }
        }
    }

    init {
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(usbReceiver, filter)
        }
    }

    fun setBaudRate(rate: Int) {
        this.baudRate = rate
    }

    fun setSelectedDevice(device: UsbDevice) {
        this.selectedDevice = device
    }

    fun scanDevices(): List<DiscoveredUsbDevice> {
        val manager = usbManager ?: return emptyList()
        val deviceList = manager.deviceList ?: return emptyList()

        return deviceList.values.map { dev ->
            val hasPerm = manager.hasPermission(dev)
            val isKnownVid = KNOWN_CHIPSETS_VID.contains(dev.vendorId)
            val mfg = try { dev.manufacturerName } catch (_: Exception) { null }
            val prod = try { dev.productName } catch (_: Exception) { null }
            val sn = try { if (hasPerm) dev.serialNumber else null } catch (_: Exception) { null }

            val isCandidate = isKnownVid ||
                    (prod?.contains("MIXION", ignoreCase = true) == true) ||
                    (mfg?.contains("MIXION", ignoreCase = true) == true)

            DiscoveredUsbDevice(
                device = dev,
                deviceName = dev.deviceName,
                vendorId = dev.vendorId,
                productId = dev.productId,
                manufacturerName = mfg,
                productName = prod,
                serialNumber = sn,
                isCandidate = isCandidate,
                hasPermission = hasPerm
            )
        }.sortedWith(compareByDescending<DiscoveredUsbDevice> { it.isCandidate }.thenBy { it.deviceName })
    }

    fun requestPermission(device: UsbDevice, onResult: (Boolean) -> Unit) {
        val manager = usbManager ?: run {
            onResult(false)
            return
        }
        if (manager.hasPermission(device)) {
            onResult(true)
            return
        }
        permissionCallback = onResult
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val intent = Intent(ACTION_USB_PERMISSION).setPackage(context.packageName)
        val pendingIntent = PendingIntent.getBroadcast(context, 0, intent, flags)
        manager.requestPermission(device, pendingIntent)
    }

    override suspend fun connect(): Result<Unit> = withContext(Dispatchers.IO) {
        val manager = usbManager ?: return@withContext Result.failure(IllegalStateException("USB Manager not available"))
        val device = selectedDevice ?: run {
            val list = scanDevices()
            val candidate = list.firstOrNull { it.isCandidate } ?: list.firstOrNull()
            candidate?.device ?: return@withContext Result.failure(IllegalStateException("No USB device selected"))
        }

        if (!manager.hasPermission(device)) {
            return@withContext Result.failure(SecurityException("USB permission not granted for device ${device.deviceName}"))
        }

        _connectionState.value = ConnectionState.CONNECTING

        try {
            val availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(manager)
            val driver = availableDrivers.find { it.device.deviceName == device.deviceName }
                ?: return@withContext Result.failure(IllegalStateException("No USB Serial driver found for device"))

            val connection = manager.openDevice(device)
                ?: return@withContext Result.failure(IllegalStateException("Failed to open USB device connection"))

            val port = driver.ports.firstOrNull()
                ?: return@withContext Result.failure(IllegalStateException("No serial ports available on device"))

            port.open(connection)
            port.setParameters(baudRate, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            try {
                port.dtr = true
                port.rts = true
            } catch (e: Exception) {
                Log.w(TAG, "Could not set DTR/RTS: ${e.message}")
            }

            this@UsbSerialTransport.serialPort = port
            _connectionState.value = ConnectionState.READY

            emitLog(
                direction = TrafficDirection.INFO,
                content = "Connected to USB device ${device.deviceName} (VID:0x${Integer.toHexString(device.vendorId)}, PID:0x${Integer.toHexString(device.productId)}) @ $baudRate baud",
                bytes = 0
            )

            startReadingLoop(port)
            Result.success(Unit)
        } catch (e: Exception) {
            _connectionState.value = ConnectionState.OFFLINE
            emitLog(
                direction = TrafficDirection.ERROR,
                content = "Connection error: ${e.message}",
                bytes = 0
            )
            Result.failure(e)
        }
    }

    private fun startReadingLoop(port: UsbSerialPort) {
        readJob?.cancel()
        readJob = scope.launch(Dispatchers.IO) {
            val buffer = ByteArray(4096)
            val stringBuilder = StringBuilder()

            while (isActive && _connectionState.value != ConnectionState.OFFLINE) {
                try {
                    val len = port.read(buffer, 200)
                    if (len > 0) {
                        val chunk = String(buffer, 0, len, Charsets.UTF_8)
                        stringBuilder.append(chunk)

                        // Split on LF delimiter (\n)
                        var newlineIndex = stringBuilder.indexOf("\n")
                        while (newlineIndex >= 0) {
                            val frameWithLf = stringBuilder.substring(0, newlineIndex + 1)
                            stringBuilder.delete(0, newlineIndex + 1)

                            val frameBytes = frameWithLf.toByteArray(Charsets.UTF_8)
                            emitLog(
                                direction = TrafficDirection.RX,
                                content = frameWithLf,
                                bytes = frameBytes.size
                            )
                            _receivedFramesFlow.emit(frameWithLf)

                            newlineIndex = stringBuilder.indexOf("\n")
                        }
                    }
                } catch (e: Exception) {
                    if (isActive) {
                        Log.e(TAG, "Read error: ${e.message}")
                        delay(100)
                    }
                }
            }
        }
    }

    override suspend fun disconnect() = withContext(Dispatchers.IO) {
        readJob?.cancel()
        readJob = null
        try {
            serialPort?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing serial port: ${e.message}")
        }
        serialPort = null
        _connectionState.value = ConnectionState.OFFLINE
        emitLog(
            direction = TrafficDirection.INFO,
            content = "USB connection closed",
            bytes = 0
        )
    }

    override fun isConnected(): Boolean {
        return serialPort?.isOpen == true && _connectionState.value != ConnectionState.OFFLINE
    }

    override suspend fun sendFrame(
        ndjsonFrame: String,
        requestId: String?,
        command: String?
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val port = serialPort
        if (port == null || !port.isOpen) {
            return@withContext Result.failure(IllegalStateException("Serial port not connected"))
        }

        try {
            val bytes = ndjsonFrame.toByteArray(Charsets.UTF_8)
            port.write(bytes, 2000)

            emitLog(
                direction = TrafficDirection.TX,
                content = ndjsonFrame,
                bytes = bytes.size,
                requestId = requestId,
                command = command
            )
            Result.success(Unit)
        } catch (e: Exception) {
            emitLog(
                direction = TrafficDirection.ERROR,
                content = "Write failed: ${e.message}",
                bytes = 0
            )
            Result.failure(e)
        }
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

    fun cleanup() {
        try {
            context.unregisterReceiver(usbReceiver)
        } catch (_: Exception) {}
    }
}
