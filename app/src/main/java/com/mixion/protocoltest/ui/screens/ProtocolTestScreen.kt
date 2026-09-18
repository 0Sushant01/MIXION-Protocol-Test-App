package com.mixion.protocoltest.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FactCheck
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mixion.protocoltest.core.protocol.ConnectionState
import com.mixion.protocoltest.core.protocol.ProtocolCommand
import com.mixion.protocoltest.core.protocol.ProtocolRegistry
import com.mixion.protocoltest.core.protocol.TestHistoryItem
import com.mixion.protocoltest.core.protocol.TrafficDirection
import com.mixion.protocoltest.core.protocol.TrafficLogEntry
import com.mixion.protocoltest.domain.test.TestExecutionStatus
import com.mixion.protocoltest.ui.ProtocolTestViewModel
import com.mixion.protocoltest.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProtocolTestScreen(viewModel: ProtocolTestViewModel) {
    val context = LocalContext.current
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager

    val isMockMode by viewModel.isMockMode.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val activeTest by viewModel.activeTest.collectAsState()
    val trafficLogs by viewModel.trafficLogs.collectAsState()
    val testHistory by viewModel.testHistory.collectAsState()

    val discoveredDevices by viewModel.discoveredDevices.collectAsState()
    val selectedDevice by viewModel.selectedDevice.collectAsState()
    val baudRate by viewModel.baudRate.collectAsState()

    val selectedCommand by viewModel.selectedCommand.collectAsState()
    val customRequestId by viewModel.customRequestId.collectAsState()
    val dispenseOrderId by viewModel.dispenseOrderId.collectAsState()
    val dispensePumps by viewModel.dispensePumps.collectAsState()
    val helloDeviceName by viewModel.helloDeviceName.collectAsState()
    val customCommandName by viewModel.customCommandName.collectAsState()
    val customPayloadJson by viewModel.customPayloadJson.collectAsState()

    val showDispenseConfirmation by viewModel.showDispenseConfirmation.collectAsState()
    val selectedHistoryDetails by viewModel.selectedHistoryDetails.collectAsState()
    val discoveredCapabilities by viewModel.discoveredCapabilities.collectAsState()

    var showHistorySheet by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "MIXION",
                            fontWeight = FontWeight.Black,
                            letterSpacing = 1.sp,
                            color = PrimaryBlue
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Protocol Tester V1.0",
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp,
                            color = TextPrimary
                        )
                    }
                },
                actions = {
                    // Connection Status Chip
                    ConnectionStatusChip(state = connectionState)
                    Spacer(modifier = Modifier.width(8.dp))
                    IconButton(onClick = { showHistorySheet = true }) {
                        Icon(
                            imageVector = Icons.Default.History,
                            contentDescription = "Test History",
                            tint = PrimaryBlue
                        )
                    }
                    IconButton(onClick = { viewModel.clearCurrentTest() }) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Reset Test",
                            tint = LightSlate
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = CardBg)
            )
        },
        containerColor = NeutralBg
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Section 1: Connection & Hardware Mode
            ConnectionSection(
                isMockMode = isMockMode,
                connectionState = connectionState,
                discoveredDevices = discoveredDevices,
                selectedDevice = selectedDevice,
                baudRate = baudRate,
                onToggleMock = { viewModel.setMockMode(it) },
                onSelectDevice = { viewModel.selectUsbDevice(it) },
                onSelectBaud = { viewModel.setBaudRate(it) },
                onRefreshDevices = { viewModel.refreshUsbDevices() },
                onConnect = { viewModel.connectUsb() },
                onDisconnect = { viewModel.disconnect() }
            )

            // Section 2: Protocol Selection & Parameter Configuration
            ProtocolSelectionSection(
                selectedCommand = selectedCommand,
                customRequestId = customRequestId,
                dispenseOrderId = dispenseOrderId,
                dispensePumps = dispensePumps,
                helloDeviceName = helloDeviceName,
                customCommandName = customCommandName,
                customPayloadJson = customPayloadJson,
                onSelectCommand = { viewModel.setSelectedCommand(it) },
                onRequestIdChange = { viewModel.setCustomRequestId(it) },
                onOrderIdChange = { viewModel.setDispenseOrderId(it) },
                onAddPump = { id, ms -> viewModel.addDispensePump(id, ms) },
                onRemovePump = { viewModel.removeDispensePump(it) },
                onHelloDeviceChange = { viewModel.setHelloDeviceName(it) },
                onCustomCommandChange = { viewModel.setCustomCommandName(it) },
                onCustomPayloadChange = { viewModel.setCustomPayloadJson(it) },
                requestPreview = viewModel.getRequestPreview(),
                activeTest = activeTest,
                discoveredCapabilities = discoveredCapabilities,
                onSend = { viewModel.sendRequest() }
            )

            // Section 3: Response & Validation Result
            ValidationResultSection(
                activeTest = activeTest,
                onRetry = { viewModel.retryLastRequest() }
            )

            // Section 4: Live Raw Traffic Monitor
            LiveTrafficSection(
                trafficLogs = trafficLogs,
                onClear = { viewModel.clearLogs() },
                onCopy = {
                    val text = trafficLogs.joinToString("\n") {
                        "[${it.timestamp}] ${it.direction} (${it.bytesCount}B)${it.latencyMs?.let { l -> " ${l}ms" } ?: ""}\n${it.asciiContent.trim()}"
                    }
                    val clip = ClipData.newPlainText("Mixion Traffic", text)
                    clipboard?.setPrimaryClip(clip)
                    Toast.makeText(context, "Traffic logs copied to clipboard", Toast.LENGTH_SHORT).show()
                }
            )
        }
    }

    // Safety Confirmation Dialog for DISPENSE
    if (showDispenseConfirmation) {
        AlertDialog(
            onDismissRequest = { viewModel.showDispenseConfirmation(false) },
            icon = { Icon(Icons.Default.Warning, contentDescription = null, tint = WarnAmber) },
            title = { Text("Operate Connected Hardware?", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text(
                        "DISPENSE command will physically operate the connected MIXION beverage machine pumps and LEDs.",
                        fontSize = 14.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Pumps: ${dispensePumps.joinToString(", ") { "Pump ${it.pumpId} (${it.durationMs}ms)" }}",
                        fontWeight = FontWeight.Medium,
                        fontSize = 13.sp,
                        color = PrimaryBlue
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.confirmAndSendDispense() },
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue)
                ) {
                    Text("Confirm & Send")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.showDispenseConfirmation(false) }) {
                    Text("Cancel")
                }
            }
        )
    }

    // History Bottom Sheet
    if (showHistorySheet) {
        ModalBottomSheet(
            onDismissRequest = { showHistorySheet = false },
            containerColor = CardBg
        ) {
            TestHistorySheetContent(
                history = testHistory,
                onClear = { viewModel.clearHistory() },
                onSelectItem = { viewModel.selectHistoryItem(it) }
            )
        }
    }

    // History Details Dialog
    selectedHistoryDetails?.let { item ->
        AlertDialog(
            onDismissRequest = { viewModel.selectHistoryItem(null) },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${item.command} Test Details", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Spacer(modifier = Modifier.weight(1f))
                    StatusBadge(isPass = item.isPass)
                }
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Timestamp: ${item.timestamp}", fontSize = 12.sp, color = TextSecondary)
                    Text("Request ID: ${item.requestId}", fontSize = 12.sp, color = TextSecondary)
                    Text("Latency: ${item.latencyMs} ms", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    Text("Request (TX):", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Text(
                        item.requestJson,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        modifier = Modifier
                            .background(CodeBg, RoundedCornerShape(4.dp))
                            .padding(8.dp)
                            .fillMaxWidth()
                    )

                    Text("Response (RX):", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Text(
                        item.responseJson.ifBlank { "No response received" },
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        modifier = Modifier
                            .background(CodeBg, RoundedCornerShape(4.dp))
                            .padding(8.dp)
                            .fillMaxWidth()
                    )

                    if (item.failureReasons.isNotEmpty()) {
                        Text("Failure Reasons:", fontWeight = FontWeight.Bold, color = FailRed, fontSize = 13.sp)
                        item.failureReasons.forEach { r ->
                            Text("• $r", color = FailRed, fontSize = 12.sp)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.selectHistoryItem(null) }) {
                    Text("Close")
                }
            }
        )
    }
}

@Composable
fun ConnectionStatusChip(state: ConnectionState) {
    val (bgColor, textColor, text) = when (state) {
        ConnectionState.READY, ConnectionState.ONLINE -> Triple(PassGreenBg, PassGreen, state.displayName)
        ConnectionState.BUSY, ConnectionState.HANDSHAKING, ConnectionState.CONNECTING -> Triple(Color(0xFFFEF3C7), WarnAmber, state.displayName)
        ConnectionState.USB_CONNECTED -> Triple(PrimaryBlueContainer, PrimaryBlue, state.displayName)
        ConnectionState.OFFLINE -> Triple(Color(0xFFF1F5F9), TextSecondary, state.displayName)
    }

    Surface(
        color = bgColor,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, textColor.copy(alpha = 0.3f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(textColor)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = text,
                color = textColor,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

val PrimaryBlueContainer = Color(0xFFDBEAFE)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionSection(
    isMockMode: Boolean,
    connectionState: ConnectionState,
    discoveredDevices: List<com.mixion.protocoltest.core.transport.DiscoveredUsbDevice>,
    selectedDevice: com.mixion.protocoltest.core.transport.DiscoveredUsbDevice?,
    baudRate: Int,
    onToggleMock: (Boolean) -> Unit,
    onSelectDevice: (com.mixion.protocoltest.core.transport.DiscoveredUsbDevice) -> Unit,
    onSelectBaud: (Int) -> Unit,
    onRefreshDevices: () -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CardBg),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // Header: Title with Icon and Connection State Badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Usb, contentDescription = null, tint = PrimaryBlue)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Connection & Transport", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Spacer(modifier = Modifier.weight(1f))
                ConnectionStatusChip(state = connectionState)
            }

            // Transport Mode Selector: 2 Segmented Tabs that will NEVER clip or collide
            Surface(
                color = CodeBg,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // Mock Simulator Tab
                    Surface(
                        color = if (isMockMode) CardBg else Color.Transparent,
                        shape = RoundedCornerShape(6.dp),
                        border = if (isMockMode) BorderStroke(1.dp, PrimaryBlue.copy(alpha = 0.4f)) else null,
                        shadowElevation = if (isMockMode) 1.dp else 0.dp,
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onToggleMock(true) }
                    ) {
                        Row(
                            modifier = Modifier.padding(vertical = 8.dp, horizontal = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                Icons.Default.Memory,
                                contentDescription = null,
                                tint = if (isMockMode) PrimaryBlue else TextSecondary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                "Mock ESP32",
                                fontWeight = if (isMockMode) FontWeight.Bold else FontWeight.Medium,
                                fontSize = 13.sp,
                                color = if (isMockMode) PrimaryBlue else TextSecondary
                            )
                        }
                    }

                    // Physical USB Tab
                    Surface(
                        color = if (!isMockMode) CardBg else Color.Transparent,
                        shape = RoundedCornerShape(6.dp),
                        border = if (!isMockMode) BorderStroke(1.dp, PrimaryBlue.copy(alpha = 0.4f)) else null,
                        shadowElevation = if (!isMockMode) 1.dp else 0.dp,
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onToggleMock(false) }
                    ) {
                        Row(
                            modifier = Modifier.padding(vertical = 8.dp, horizontal = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                Icons.Default.Cable,
                                contentDescription = null,
                                tint = if (!isMockMode) PrimaryBlue else TextSecondary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                "Physical USB",
                                fontWeight = if (!isMockMode) FontWeight.Bold else FontWeight.Medium,
                                fontSize = 13.sp,
                                color = if (!isMockMode) PrimaryBlue else TextSecondary
                            )
                        }
                    }
                }
            }

            if (isMockMode) {
                // Mock Mode Info
                Surface(
                    color = Color(0xFFEFF6FF),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = PrimaryBlue, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                "Mock Controller Active (ESP32 V1.0)",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = PrimaryBlue
                            )
                            Text(
                                "Simulates MIXION Embedded Brain V1.0 with Section 39 max-3-pump concurrent execution & CRC32 ISO-HDLC integrity.",
                                fontSize = 12.sp,
                                color = TextSecondary
                            )
                        }
                    }
                }
            } else {
                // Physical USB Mode
                var expandedDevDropdown by remember { mutableStateOf(false) }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ExposedDropdownMenuBox(
                        expanded = expandedDevDropdown,
                        onExpandedChange = { expandedDevDropdown = !expandedDevDropdown },
                        modifier = Modifier.weight(1f)
                    ) {
                        OutlinedTextField(
                            value = selectedDevice?.let { "${it.productName ?: "USB Device"} (VID:0x${Integer.toHexString(it.vendorId)})" } ?: "No USB device selected",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("USB Device") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedDevDropdown) },
                            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
                            singleLine = true,
                            textStyle = LocalTextStyle.current.copy(fontSize = 13.sp)
                        )
                        ExposedDropdownMenu(
                            expanded = expandedDevDropdown,
                            onDismissRequest = { expandedDevDropdown = false }
                        ) {
                            if (discoveredDevices.isEmpty()) {
                                DropdownMenuItem(
                                    text = { Text("No USB devices detected") },
                                    onClick = { expandedDevDropdown = false }
                                )
                            } else {
                                discoveredDevices.forEach { dev ->
                                    DropdownMenuItem(
                                        text = {
                                            Column {
                                                Text(
                                                    "${dev.productName ?: "USB Device"} (VID:0x${Integer.toHexString(dev.vendorId)}, PID:0x${Integer.toHexString(dev.productId)})",
                                                    fontWeight = if (dev.isCandidate) FontWeight.Bold else FontWeight.Normal
                                                )
                                                if (dev.isCandidate) {
                                                    Text("★ Recommended MIXION Controller", color = PrimaryBlue, fontSize = 11.sp)
                                                }
                                            }
                                        },
                                        onClick = {
                                            onSelectDevice(dev)
                                            expandedDevDropdown = false
                                        }
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.width(8.dp))
                    IconButton(onClick = onRefreshDevices) {
                        Icon(Icons.Default.Refresh, contentDescription = "Scan USB Devices")
                    }
                }

                // Connect / Disconnect Buttons
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    val isConnected = connectionState != ConnectionState.OFFLINE
                    Button(
                        onClick = onConnect,
                        enabled = !isConnected && selectedDevice != null,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue)
                    ) {
                        Icon(Icons.Default.Power, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Connect")
                    }

                    OutlinedButton(
                        onClick = onDisconnect,
                        enabled = isConnected,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.PowerOff, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Disconnect")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProtocolSelectionSection(
    selectedCommand: ProtocolCommand,
    customRequestId: String,
    dispenseOrderId: String,
    dispensePumps: List<com.mixion.protocoltest.core.protocol.PumpOperation>,
    helloDeviceName: String,
    customCommandName: String,
    customPayloadJson: String,
    onSelectCommand: (ProtocolCommand) -> Unit,
    onRequestIdChange: (String) -> Unit,
    onOrderIdChange: (String) -> Unit,
    onAddPump: (Int, Long) -> Unit,
    onRemovePump: (Int) -> Unit,
    onHelloDeviceChange: (String) -> Unit,
    onCustomCommandChange: (String) -> Unit,
    onCustomPayloadChange: (String) -> Unit,
    requestPreview: com.mixion.protocoltest.ui.RequestPreviewData,
    activeTest: com.mixion.protocoltest.domain.test.ActiveTestState,
    discoveredCapabilities: com.mixion.protocoltest.core.protocol.DiscoveredCapabilities? = null,
    onSend: () -> Unit
) {
    var expandedCmdDropdown by remember { mutableStateOf(false) }
    var showPreviewDetails by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CardBg),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, tint = PrimaryBlue)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Protocol Request Builder", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }

            // Embedded Capabilities Status Banner
            Surface(
                color = if (discoveredCapabilities != null) PassGreenBg else Color(0xFFF1F5F9),
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, if (discoveredCapabilities != null) PassGreenBorder else Color(0xFFCBD5E1)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (discoveredCapabilities != null) Icons.Default.CheckCircle else Icons.Default.Info,
                        contentDescription = null,
                        tint = if (discoveredCapabilities != null) PassGreen else TextSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            if (discoveredCapabilities != null)
                                "Discovered Capabilities: ${discoveredCapabilities.pumpCount} Pumps (${discoveredCapabilities.supportedPumpIds})"
                            else
                                "Capabilities: Undiscovered (Run HELLO / CAPABILITIES)",
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            color = if (discoveredCapabilities != null) PassGreen else TextSecondary
                        )
                        if (discoveredCapabilities != null) {
                            Text(
                                "Supported Commands: ${discoveredCapabilities.commands.joinToString(", ")}",
                                fontSize = 11.sp,
                                color = TextSecondary
                            )
                        } else {
                            Text(
                                "DISPENSE pump IDs will be capability-gated once discovered.",
                                fontSize = 11.sp,
                                color = TextSecondary
                            )
                        }
                    }
                }
            }

            // Command Dropdown
            ExposedDropdownMenuBox(
                expanded = expandedCmdDropdown,
                onExpandedChange = { expandedCmdDropdown = !expandedCmdDropdown },
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedTextField(
                    value = if (selectedCommand == ProtocolCommand.CUSTOM) "CUSTOM [Diagnostic Mode]" else selectedCommand.displayName,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Select Protocol Command") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedCmdDropdown) },
                    modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth()
                )
                ExposedDropdownMenu(
                    expanded = expandedCmdDropdown,
                    onDismissRequest = { expandedCmdDropdown = false }
                ) {
                    ProtocolCommand.entries.forEach { cmd ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    if (cmd.isTesterDiagnosticOnly) {
                                        Text("${cmd.displayName} [Diagnostic Mode]", fontWeight = FontWeight.SemiBold, color = WarnAmber)
                                        Text(
                                            "Tester diagnostic tool — NOT a normative V1.0 protocol command.",
                                            fontSize = 11.sp,
                                            color = WarnAmber
                                        )
                                    } else {
                                        Text(cmd.displayName, fontWeight = FontWeight.SemiBold)
                                        Text(
                                            ProtocolRegistry.getDefinition(cmd).description,
                                            fontSize = 11.sp,
                                            color = TextSecondary
                                        )
                                    }
                                }
                            },
                            onClick = {
                                onSelectCommand(cmd)
                                expandedCmdDropdown = false
                            }
                        )
                    }
                }
            }

            // Description info
            Text(
                ProtocolRegistry.getDefinition(selectedCommand).description,
                fontSize = 12.sp,
                color = TextSecondary
            )

            // Optional Request ID override
            OutlinedTextField(
                value = customRequestId,
                onValueChange = onRequestIdChange,
                label = { Text("Request ID (leave blank for auto)") },
                placeholder = { Text(requestPreview.requestId) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp)
            )

            // Dynamic parameter form based on command
            when (selectedCommand) {
                ProtocolCommand.HELLO -> {
                    OutlinedTextField(
                        value = helloDeviceName,
                        onValueChange = onHelloDeviceChange,
                        label = { Text("Client Device Name") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
                ProtocolCommand.DISPENSE -> {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = dispenseOrderId,
                            onValueChange = onOrderIdChange,
                            label = { Text("Order ID") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )

                        Text("Pumps to Dispense (Max 3 concurrent execution):", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)

                        dispensePumps.forEachIndexed { index, pump ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Surface(
                                    color = CodeBg,
                                    shape = RoundedCornerShape(6.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("Pump ${pump.pumpId}", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Text("${pump.durationMs} ms", fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                                    }
                                }
                                IconButton(
                                    onClick = { onRemovePump(index) },
                                    enabled = dispensePumps.size > 1
                                ) {
                                    Icon(Icons.Default.Delete, contentDescription = "Remove Pump", tint = FailRed)
                                }
                            }
                        }

                        // Add pump row
                        var newPumpIdText by remember { mutableStateOf("") }
                        var newDurationText by remember { mutableStateOf("") }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = newPumpIdText,
                                onValueChange = { if (it.all { c -> c.isDigit() }) newPumpIdText = it },
                                label = { Text("Pump #") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = newDurationText,
                                onValueChange = { if (it.all { c -> c.isDigit() }) newDurationText = it },
                                label = { Text("Duration (ms)") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(1.5f),
                                singleLine = true
                            )
                            Button(
                                onClick = {
                                    val pid = newPumpIdText.toIntOrNull()
                                    val dur = newDurationText.toLongOrNull()
                                    if (pid != null && dur != null && pid > 0 && dur > 0) {
                                        onAddPump(pid, dur)
                                        newPumpIdText = ""
                                        newDurationText = ""
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue)
                            ) {
                                Text("Add")
                            }
                        }
                    }
                }
                ProtocolCommand.CUSTOM -> {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = customCommandName,
                            onValueChange = onCustomCommandChange,
                            label = { Text("Command Name") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = customPayloadJson,
                            onValueChange = onCustomPayloadChange,
                            label = { Text("Custom Payload (JSON)") },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 3,
                            maxLines = 6,
                            textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                        )
                    }
                }
                else -> {
                    // No extra parameters needed for CAPABILITIES, STATUS, GLASS_STATUS, STOP, RESET, HEARTBEAT
                    Text("No parameters required for ${selectedCommand.commandName}.", fontSize = 12.sp, color = TextSecondary)
                }
            }

            // Exact Wire Request Preview & CRC32 Verification
            val clipManager = LocalContext.current.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val previewContext = LocalContext.current

            Surface(
                color = CodeBg,
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, PrimaryBlue.copy(alpha = 0.25f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Terminal, contentDescription = null, tint = PrimaryBlue, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Exact Wire Command to Send (NDJSON)", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Spacer(modifier = Modifier.weight(1f))
                        Surface(
                            color = if (requestPreview.validationReport.isPass) PassGreenBg else FailRedBg,
                            shape = RoundedCornerShape(4.dp),
                            border = BorderStroke(1.dp, if (requestPreview.validationReport.isPass) PassGreenBorder else FailRedBorder)
                        ) {
                            Text(
                                if (requestPreview.validationReport.isPass) "VALID MIXION V1.0" else "INVALID SCHEMA",
                                color = if (requestPreview.validationReport.isPass) PassGreen else FailRed,
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.sp,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        IconButton(
                            onClick = {
                                clipManager?.setPrimaryClip(ClipData.newPlainText("Wire Request", requestPreview.finalNdjsonFrame))
                                Toast.makeText(previewContext, "Exact command copied to clipboard", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "Copy Command", modifier = Modifier.size(16.dp), tint = PrimaryBlue)
                        }
                    }

                    // Monospace Exact Frame Display (Dark Terminal Background)
                    Surface(
                        color = Color(0xFF0F172A),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = requestPreview.finalNdjsonFrame.trimEnd(),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            color = Color(0xFFF8FAFC),
                            modifier = Modifier.padding(10.dp)
                        )
                    }

                    // Checksum & Integrity Details
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(CardBg, RoundedCornerShape(6.dp))
                            .border(1.dp, Color(0xFFE2E8F0), RoundedCornerShape(6.dp))
                            .padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Algorithm: ", fontSize = 10.sp, color = TextSecondary)
                            Text("CRC-32/ISO-HDLC", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                            Spacer(modifier = Modifier.width(10.dp))
                            Text("Checksum: ", fontSize = 10.sp, color = TextSecondary)
                            Text(requestPreview.crc32, fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = PrimaryBlue)
                            Spacer(modifier = Modifier.weight(1f))
                            Text("Delimiter: LF (\\n)", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = TextSecondary)
                        }

                        Text("Canonical JSON (CRC Input without crc32):", fontSize = 10.sp, fontWeight = FontWeight.Medium, color = TextSecondary)
                        Text(
                            requestPreview.canonicalWithoutCrc,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            color = DarkSlate
                        )

                        if (!requestPreview.validationReport.isPass) {
                            Text(
                                "Format issues: ${requestPreview.validationReport.failureReasons.joinToString(", ")}",
                                fontSize = 11.sp,
                                color = FailRed,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }

            // Send Request Button
            val isBusy = activeTest.status == TestExecutionStatus.SENDING ||
                    activeTest.status == TestExecutionStatus.WAITING_RESPONSE ||
                    activeTest.status == TestExecutionStatus.DISPENSE_ACCEPTED_EXECUTING

            Button(
                onClick = onSend,
                enabled = !isBusy,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (selectedCommand.isPotentiallyDangerous) WarnAmber else PrimaryBlue
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                if (isBusy) {
                    CircularProgressIndicator(
                        color = Color.White,
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        when (activeTest.status) {
                            TestExecutionStatus.SENDING -> "Transmitting..."
                            TestExecutionStatus.WAITING_RESPONSE -> "Awaiting Response..."
                            TestExecutionStatus.DISPENSE_ACCEPTED_EXECUTING -> "ACCEPTED! Dispensing..."
                            else -> "Working..."
                        },
                        fontWeight = FontWeight.Bold
                    )
                } else {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        "SEND ${selectedCommand.commandName} REQUEST",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }
            }
        }
    }
}

@Composable
fun ValidationResultSection(
    activeTest: com.mixion.protocoltest.domain.test.ActiveTestState,
    onRetry: () -> Unit = {}
) {
    val context = LocalContext.current
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CardBg),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.AutoMirrored.Filled.FactCheck, contentDescription = null, tint = PrimaryBlue)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Response & Protocol Validation", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Spacer(modifier = Modifier.weight(1f))
                if (activeTest.latencyMs > 0) {
                    Text("${activeTest.latencyMs} ms", fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = TextSecondary)
                }
            }

            // Retry Attempt Notification Badge
            if (activeTest.isRetryAttempt) {
                Surface(
                    color = Color(0xFFFEF3C7),
                    shape = RoundedCornerShape(6.dp),
                    border = BorderStroke(1.dp, WarnAmber)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, tint = WarnAmber, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            "RETRY ATTEMPT: Preserving original request_id [${activeTest.requestId}] per V1.0 idempotency specification.",
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            color = Color(0xFF78350F)
                        )
                    }
                }
            }

            // Big PASS / FAIL / TIMEOUT Banner
            when (activeTest.status) {
                TestExecutionStatus.IDLE -> {
                    Text(
                        "No active test. Select a protocol command and click SEND REQUEST.",
                        fontSize = 13.sp,
                        color = TextSecondary
                    )
                }
                TestExecutionStatus.SENDING, TestExecutionStatus.WAITING_RESPONSE, TestExecutionStatus.DISPENSE_ACCEPTED_EXECUTING -> {
                    Surface(
                        color = Color(0xFFFEF3C7),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = WarnAmber)
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                if (activeTest.status == TestExecutionStatus.DISPENSE_ACCEPTED_EXECUTING)
                                    "ACCEPTED received. Waiting for COMPLETED terminal frame..."
                                else "Transmitted request. Awaiting controller response...",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = Color(0xFF78350F)
                            )
                        }
                    }
                }
                TestExecutionStatus.PASS -> {
                    Surface(
                        color = PassGreenBg,
                        border = BorderStroke(1.5.dp, PassGreenBorder),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = PassGreen, modifier = Modifier.size(28.dp))
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text("PASS — Protocol Validated", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = PassGreen)
                                Text("Response conforms 100% to MIXION Protocol V1.0 wire contract", fontSize = 12.sp, color = Color(0xFF065F46))
                            }
                        }
                    }
                }
                TestExecutionStatus.FAIL -> {
                    Surface(
                        color = FailRedBg,
                        border = BorderStroke(1.5.dp, FailRedBorder),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Cancel, contentDescription = null, tint = FailRed, modifier = Modifier.size(28.dp))
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text("FAIL — Validation Error", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = FailRed)
                                Text(activeTest.errorMessage ?: "Validation checks failed", fontSize = 12.sp, color = Color(0xFF991B1B))
                            }
                        }
                    }
                }
                TestExecutionStatus.TIMEOUT -> {
                    Surface(
                        color = Color(0xFFFEF2F2),
                        border = BorderStroke(1.5.dp, Color(0xFFF87171)),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.TimerOff, contentDescription = null, tint = FailRed, modifier = Modifier.size(28.dp))
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text("TIMEOUT", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = FailRed)
                                Text(activeTest.errorMessage ?: "No response from controller within timeout", fontSize = 12.sp, color = Color(0xFF991B1B))
                            }
                        }
                    }
                }
                TestExecutionStatus.ERROR -> {
                    Surface(
                        color = FailRedBg,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Error, contentDescription = null, tint = FailRed)
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(activeTest.errorMessage ?: "Communication error", fontSize = 13.sp, color = FailRed)
                        }
                    }
                }
            }

            // Retry UI Button or Non-Retriable Notice
            if (activeTest.isRetriable) {
                Surface(
                    color = Color(0xFFFFFBEB),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, WarnAmber.copy(alpha = 0.6f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Refresh, contentDescription = null, tint = WarnAmber, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Retryable Condition Detected", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color(0xFF92400E))
                        }
                        Text(
                            "Per Protocol V1.0 specification, a retry reuses the original request_id (${activeTest.requestId}) to ensure idempotency and prevent duplicate execution.",
                            fontSize = 11.sp,
                            color = Color(0xFF78350F)
                        )
                        Button(
                            onClick = onRetry,
                            colors = ButtonDefaults.buttonColors(containerColor = WarnAmber),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("RETRY OPERATION (Reuses ${activeTest.requestId})", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            } else if (activeTest.status == TestExecutionStatus.FAIL || activeTest.status == TestExecutionStatus.ERROR) {
                Text(
                    "Non-retriable: Protocol schema validation failure or capability rejection. Per Protocol V1.0, invalid requests must NOT be retried on the wire.",
                    fontSize = 11.sp,
                    color = FailRed,
                    fontWeight = FontWeight.Medium
                )
            }

            // EXACT RECEIVED WIRE RESPONSE(S) - OPEN BY DEFAULT!
            if (activeTest.command == ProtocolCommand.DISPENSE) {
                // Phase 1 Response Frame
                if (!activeTest.dispensePhase1RxFrame.isNullOrBlank()) {
                    Surface(
                        color = CodeBg,
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, PrimaryBlue.copy(alpha = 0.2f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Phase 1 Wire Response: ACCEPTED", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = PrimaryBlue)
                                Spacer(modifier = Modifier.weight(1f))
                                IconButton(
                                    onClick = {
                                        clipboard?.setPrimaryClip(ClipData.newPlainText("Phase 1 Frame", activeTest.dispensePhase1RxFrame))
                                        Toast.makeText(context, "Phase 1 frame copied", Toast.LENGTH_SHORT).show()
                                    },
                                    modifier = Modifier.size(22.dp)
                                ) {
                                    Icon(Icons.Default.ContentCopy, contentDescription = "Copy", modifier = Modifier.size(14.dp), tint = PrimaryBlue)
                                }
                            }
                            Surface(
                                color = Color(0xFF0F172A),
                                shape = RoundedCornerShape(4.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = activeTest.dispensePhase1RxFrame.trimEnd(),
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    color = Color(0xFFF8FAFC),
                                    modifier = Modifier.padding(8.dp)
                                )
                            }
                        }
                    }
                }

                // Phase 2 Response Frame
                if (!activeTest.dispensePhase2RxFrame.isNullOrBlank()) {
                    Surface(
                        color = CodeBg,
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, PassGreen.copy(alpha = 0.2f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            val isCompleted = activeTest.dispensePhase2RxFrame.contains("COMPLETED")
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    if (isCompleted) "Phase 2 Wire Response: COMPLETED" else "Phase 2 Wire Response: ERROR",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    color = if (isCompleted) PassGreen else FailRed
                                )
                                Spacer(modifier = Modifier.weight(1f))
                                IconButton(
                                    onClick = {
                                        clipboard?.setPrimaryClip(ClipData.newPlainText("Phase 2 Frame", activeTest.dispensePhase2RxFrame))
                                        Toast.makeText(context, "Phase 2 frame copied", Toast.LENGTH_SHORT).show()
                                    },
                                    modifier = Modifier.size(22.dp)
                                ) {
                                    Icon(Icons.Default.ContentCopy, contentDescription = "Copy", modifier = Modifier.size(14.dp), tint = PrimaryBlue)
                                }
                            }
                            Surface(
                                color = Color(0xFF0F172A),
                                shape = RoundedCornerShape(4.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = activeTest.dispensePhase2RxFrame.trimEnd(),
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    color = Color(0xFFF8FAFC),
                                    modifier = Modifier.padding(8.dp)
                                )
                            }
                        }
                    }
                }
            } else if (activeTest.rawRxString.isNotBlank()) {
                // Standard command response frame
                Surface(
                    color = CodeBg,
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, RxTeal.copy(alpha = 0.2f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Terminal, contentDescription = null, tint = RxTeal, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Exact Wire Response Received (RX)", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            Spacer(modifier = Modifier.weight(1f))
                            IconButton(
                                onClick = {
                                    clipboard?.setPrimaryClip(ClipData.newPlainText("Received Frame", activeTest.rawRxString))
                                    Toast.makeText(context, "Exact response frame copied", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.size(22.dp)
                            ) {
                                Icon(Icons.Default.ContentCopy, contentDescription = "Copy", modifier = Modifier.size(14.dp), tint = PrimaryBlue)
                            }
                        }
                        Surface(
                            color = Color(0xFF0F172A),
                            shape = RoundedCornerShape(4.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = activeTest.rawRxString.trimEnd(),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = Color(0xFFF8FAFC),
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                    }
                }
            }

            // CRC32 INTEGRITY & RESPONSE CHECKSUM VERIFICATION CARD
            activeTest.validationReport?.crcResult?.let { crc ->
                Surface(
                    color = if (crc.isValid) PassGreenBg else FailRedBg,
                    border = BorderStroke(1.dp, if (crc.isValid) PassGreenBorder else FailRedBorder),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (crc.isValid) Icons.Default.CheckCircle else Icons.Default.Cancel,
                                contentDescription = null,
                                tint = if (crc.isValid) PassGreen else FailRed,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                "Response CRC32 Verification",
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                color = if (crc.isValid) PassGreen else FailRed
                            )
                            Spacer(modifier = Modifier.weight(1f))
                            Surface(
                                color = if (crc.isValid) PassGreen else FailRed,
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    if (crc.isValid) "MATCH" else "MISMATCH",
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 10.sp,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Received Checksum in Frame:", fontSize = 10.sp, color = TextSecondary)
                                Text(
                                    crc.receivedCrc ?: "Missing",
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    color = if (crc.isValid) TextPrimary else FailRed
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Calculated CRC-32/ISO-HDLC:", fontSize = 10.sp, color = TextSecondary)
                                Text(
                                    crc.calculatedCrc ?: "N/A",
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    color = PassGreen
                                )
                            }
                        }

                        if (!crc.canonicalJson.isNullOrBlank()) {
                            Text("Canonical JSON Input for CRC Verification:", fontSize = 10.sp, fontWeight = FontWeight.Medium, color = TextSecondary)
                            Text(
                                crc.canonicalJson,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                color = DarkSlate,
                                maxLines = 4
                            )
                        }

                        if (!crc.isValid && !crc.errorMessage.isNullOrBlank()) {
                            Text(
                                "Error: ${crc.errorMessage}",
                                fontSize = 11.sp,
                                color = FailRed,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            // Checklist of validation items if report available
            activeTest.validationReport?.let { report ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Wire Contract Validation Checklist:", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    report.checks.forEach { check ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (check.isPassed) Icons.Default.Check else Icons.Default.Close,
                                contentDescription = null,
                                tint = if (check.isPassed) PassGreen else FailRed,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(check.name, fontWeight = FontWeight.Medium, fontSize = 12.sp)
                            Spacer(modifier = Modifier.weight(1f))
                            Text(
                                check.details,
                                fontSize = 11.sp,
                                color = if (check.isPassed) TextSecondary else FailRed,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun LiveTrafficSection(
    trafficLogs: List<TrafficLogEntry>,
    onClear: () -> Unit,
    onCopy: () -> Unit
) {
    val listState = rememberLazyListState()

    LaunchedEffect(trafficLogs.size) {
        if (trafficLogs.isNotEmpty()) {
            listState.animateScrollToItem(trafficLogs.size - 1)
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 200.dp, max = 340.dp),
        colors = CardDefaults.cardColors(containerColor = CardBg),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Terminal, contentDescription = null, tint = PrimaryBlue)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Live Traffic Monitor", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Spacer(modifier = Modifier.weight(1f))
                IconButton(onClick = onCopy) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "Copy Traffic", modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = onClear) {
                    Icon(Icons.Default.DeleteSweep, contentDescription = "Clear Traffic", modifier = Modifier.size(18.dp))
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            if (trafficLogs.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("No traffic yet. Send a request to see live packets.", fontSize = 12.sp, color = TextSecondary)
                }
            } else {
                LazyColumn(
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(trafficLogs) { entry ->
                        TrafficRow(entry)
                    }
                }
            }
        }
    }
}

@Composable
fun TrafficRow(entry: TrafficLogEntry) {
    val context = LocalContext.current
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager

    val (dirColor, dirBg) = when (entry.direction) {
        TrafficDirection.TX -> Pair(TxBlue, Color(0xFFEFF6FF))
        TrafficDirection.RX -> Pair(RxTeal, Color(0xFFF0FDFA))
        TrafficDirection.INFO -> Pair(LightSlate, Color(0xFFF1F5F9))
        TrafficDirection.WARN -> Pair(WarnAmber, Color(0xFFFFFBEB))
        TrafficDirection.ERROR -> Pair(FailRed, Color(0xFFFEF2F2))
    }

    var expanded by remember { mutableStateOf(false) }

    Surface(
        color = dirBg,
        shape = RoundedCornerShape(6.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    entry.timestamp,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = TextSecondary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Surface(
                    color = dirColor,
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        entry.direction.name,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                if (entry.bytesCount > 0) {
                    Text("${entry.bytesCount}B", fontSize = 11.sp, color = TextSecondary, fontFamily = FontFamily.Monospace)
                }
                entry.latencyMs?.let {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("${it}ms", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = dirColor)
                }
                Spacer(modifier = Modifier.weight(1f))

                // One-tap copy for packet
                IconButton(
                    onClick = {
                        clipboard?.setPrimaryClip(ClipData.newPlainText("Packet", entry.asciiContent))
                        Toast.makeText(context, "Packet copied", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.size(20.dp)
                ) {
                    Icon(
                        Icons.Default.ContentCopy,
                        contentDescription = "Copy Packet",
                        modifier = Modifier.size(13.dp),
                        tint = TextSecondary
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = TextSecondary
                )
            }

            Spacer(modifier = Modifier.height(4.dp))
            Text(
                entry.asciiContent.trim(),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = TextPrimary
            )

            if (expanded && entry.hexContent.isNotBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text("HEX:", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = TextSecondary)
                Text(
                    entry.hexContent,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = TextSecondary
                )
            }
        }
    }
}

@Composable
fun TestHistorySheetContent(
    history: List<TestHistoryItem>,
    onClear: () -> Unit,
    onSelectItem: (TestHistoryItem) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Local Test History", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(modifier = Modifier.weight(1f))
            TextButton(onClick = onClear) {
                Text("Clear All", color = FailRed)
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        if (history.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(150.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("No test history recorded yet.", color = TextSecondary)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(history) { item ->
                    HistoryItemRow(item, onClick = { onSelectItem(item) })
                }
            }
        }
    }
}

@Composable
fun HistoryItemRow(item: TestHistoryItem, onClick: () -> Unit) {
    Surface(
        color = CodeBg,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            StatusBadge(isPass = item.isPass)
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(item.command, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text("${item.timestamp} • ${item.requestId}", fontSize = 11.sp, color = TextSecondary)
            }
            Text("${item.latencyMs} ms", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
            Spacer(modifier = Modifier.width(8.dp))
            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = TextSecondary)
        }
    }
}

@Composable
fun StatusBadge(isPass: Boolean) {
    Surface(
        color = if (isPass) PassGreenBg else FailRedBg,
        border = BorderStroke(1.dp, if (isPass) PassGreenBorder else FailRedBorder),
        shape = RoundedCornerShape(6.dp)
    ) {
        Text(
            text = if (isPass) "PASS" else "FAIL",
            color = if (isPass) PassGreen else FailRed,
            fontWeight = FontWeight.Black,
            fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}
