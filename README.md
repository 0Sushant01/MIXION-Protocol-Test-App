# MIXION Protocol Tester (V1.0)

A dedicated, lightweight Android development, testing, debugging, and validation tool for the **MIXION Embedded Communication Protocol V1.0**.

---

## 1. Purpose

The application serves one primary purpose:
> **Send protocol requests to the MIXION embedded controller through USB (or local Mock Simulator), receive the exact response, display the raw byte communication, and automatically validate whether the response matches the approved MIXION Protocol V1.0 specification.**

* **No cloud services**: 100% offline.
* **No business features**: No recipes, drink inventory, payments, or cloud synchronization.
* **Pure engineering protocol tool**: Wire-level contract fidelity, deterministic NDJSON framing, canonical JSON, CRC-32/ISO-HDLC integrity validation.

---

## 2. System Architecture

```text
┌────────────────────────────────────────────────────────────────────────┐
│                   Jetpack Compose UI (Material 3)                      │
│  Connection Panel │ Protocol Selector & Builder │ Validation │ Traffic │
└──────────────────────────────────┬─────────────────────────────────────┘
                                   │ StateFlow / Events
┌──────────────────────────────────▼─────────────────────────────────────┐
│                       ProtocolTestViewModel                            │
└──────────────────────────────────┬─────────────────────────────────────┘
                                   │
┌──────────────────────────────────▼─────────────────────────────────────┐
│                       ProtocolTestEngine                               │
│  - Session & State Machine (OFFLINE -> CONNECTED -> READY -> BUSY)     │
│  - Request ID Generator (e.g. REQ-XXXXX, HS-XXXXX, HB-XXXXX)           │
│  - Timeout & Multi-Response Handler (ACCEPTED -> COMPLETED)            │
│  - History & Communication Logger                                      │
└──────────────┬──────────────────────────────────────────┬──────────────┘
               │                                          │
┌──────────────▼─────────────┐             ┌──────────────▼──────────────┐
│       Protocol Layer       │             │       Transport Layer       │
│ - Canonical JSON Serializer│             │ - UsbSerialTransport        │
│ - CRC-32/ISO-HDLC Engine   │             │   (Android UsbManager /     │
│ - Protocol Registry        │             │    CDC-ACM & Serial Driver) │
│ - Protocol Validator       │             │ - MockEmbeddedTransport     │
│   (Frame, CRC, Schema)     │             │   (Embedded Brain Simulator)│
└────────────────────────────┘             └─────────────────────────────┘
```

---

## 3. Protocol V1.0 Specification Implementation

### Transport & Framing
* **Physical Transport**: USB Serial (Android USB Host APIs + CDC-ACM / FTDI / CP210x / CH340 / PL2303 / RP2040 drivers).
* **Wire Format**: JSON framed as NDJSON (one compact JSON object followed by LF `\n`).
* **Baud Rate**: Configurable (Default: 115200 baud, 8N1).

### Integrity (CRC-32/ISO-HDLC)
* **Algorithm**: CRC-32/ISO-HDLC (poly: `0x04C11DB7`, init: `0xFFFFFFFF`, refIn: `true`, refOut: `true`, xorOut: `0xFFFFFFFF`).
* **Canonical JSON Input**: Compact JSON without `crc32`, object keys sorted lexicographically ascending recursively, UTF-8 encoded without BOM.
* **Output**: Exactly 8 uppercase hexadecimal characters placed in the `crc32` field in lexicographically sorted key position.
* **Reference Test Vector**:
  ```json
  // CRC Input:
  {"command":"HEARTBEAT","payload":{},"request_id":"HB-10001","type":"request","version":1}
  // Calculated CRC: A4781FA2
  // Final Wire Frame:
  {"command":"HEARTBEAT","crc32":"A4781FA2","payload":{},"request_id":"HB-10001","type":"request","version":1}\n
  ```

### Supported Commands (Section 30)
1. **`HELLO`**: Handshake and device identification.
2. **`CAPABILITIES`**: Capability discovery (pump count, supported pump IDs, commands).
3. **`STATUS`**: Queries internal machine operational state (`STARTING`, `IDLE`, `DISPENSING`, `ERROR`, `STOPPED`).
4. **`GLASS_STATUS`**: Queries optical glass sensor (`glass_present: true/false`).
5. **`DISPENSE`**: Main pour command with safety confirmation. Multi-phase response: `ACCEPTED` committed first, then `COMPLETED` (`result: "SUCCESS"`) or `ERROR` (`E006` with `failed_pumps`).
6. **`STOP`**: Emergency halt.
7. **`RESET`**: Clears fault conditions and returns machine to `IDLE`.
8. **`HEARTBEAT`**: Liveness check reporting `state` and `power_mode` (`"direct"` or `"backup"`).
9. **`CUSTOM`**: Freeform JSON payload editor to test error scenarios (`E001`, `E002`, `E013`, etc.).

---

## 4. Requirements & Build Instructions

### Requirements
* Android SDK 36 / 37 (installed in `/home/s/Android/Sdk`)
* JDK 17
* Gradle 9.6 (included via `gradlew`)
* Minimum Android Version: Android 7.0 (API 24)
* Target Android Version: Android 15 / 16 (API 36 / 37)

### Build Debug APK
```bash
./gradlew assembleDebug
```
The resulting APK is generated at:
`app/build/outputs/apk/debug/app-debug.apk`

### Run Automated Unit Tests
```bash
./gradlew testDebugUnitTest
```
Runs 20 automated tests validating CRC32 test vectors, canonical serialization, response validation, and Mock controller loopback.

---

## 5. Mock Controller Mode

To test the application on devices or emulators without physical hardware:
1. Launch the app.
2. The top bar defaults to **Mock Controller** mode.
3. The Mock Controller simulates an ESP32/RP2040 Embedded Brain:
   - Verifies incoming CRC32 (returns `E013` if tampered).
   - Validates version and command syntax.
   - Responds to `HELLO`, `CAPABILITIES`, `STATUS`, `GLASS_STATUS`, `HEARTBEAT`.
   - Simulates `DISPENSE` execution according to the **Section 39 Note** (maximum 3 concurrent pumps running simultaneously, starting with the longest durations).

---

## 6. Physical USB Setup

When connecting to the actual MIXION machine:
1. Connect Android device to the embedded controller using an OTG / USB-C to USB-A cable.
2. Toggle the mode switch from **Mock Controller** to **USB Hardware**.
3. The detected USB devices dropdown will list all connected peripherals with Vendor ID, Product ID, and Manufacturer.
4. Tap **Connect**. Grant the standard Android USB permission prompt.
5. Once connected, status indicates **READY**.
6. Select any protocol command and tap **SEND REQUEST**.

---

## 7. Troubleshooting

* **Permission Denied on USB**: Ensure the USB OTG adapter is functional and tap **Connect** to trigger the permission prompt.
* **CRC Mismatch (E013)**: The controller returned an error because the transmitted bytes or received response was corrupted or had non-canonical JSON ordering. Check the Raw Communication Traffic Monitor.
* **Timeout**: Check that baud rate matches controller firmware configuration (default is 115200).