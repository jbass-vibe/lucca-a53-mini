# Lucca A53 Mini (Bluetooth LE Controller)

An Android application built to manage and interface with the "S1 Timer," a Bluetooth Low Energy (BLE) boiler and relay scheduling device. This app allows users to seamlessly connect to their machine, synchronize real-time clocks, and configure multi-day operating schedules.

## Features

* **BLE Discovery & Connection:** Scans for proprietary S1 custom GATT services (`ACAB0001`).
* **Robust Connection Management:** Advanced BLE connection handling, including auto-retry logic for connection failures, expected host-initiated teardowns, and human-readable error resolution prompts.
* **Schedule Management:** Configure up to seven daily schedule slots (Monday–Sunday), each supporting up to three ON/OFF time-pairs.
* **Real-Time Clock (RTC) Sync:** Synchronize the Android device's current time to the S1 hardware.

## Architecture & Key Components

* **`BleManager.java`:** The core Bluetooth LE engine. Manages device connections, GATT service discovery, characteristic reads/writes, and interprets protocol-specific disconnect reasons.
* **`ScanActivity.java`:** UI for discovering nearby S1 BLE devices.
* **`ScheduleActivity.java`:** The main scheduling interface where users can adjust time pairs and sync data to the hardware.
* **`S1Schedule.java` / `IS1Device.java`:** Data models and interfaces representing the hardware's 7-day schedule payload and device capabilities.

## S1 Timer BLE Protocol Specification (v1.1)

The S1 Timer operates as a Bluetooth Low Energy (BLE) boiler and relay scheduler. The Android application connects as the Central role and communicates with the S1 device (Peripheral) using a proprietary GATT service.

### Transport Layer & Connection
* The transport uses the Bluetooth Low Energy (BLE) / Bluetooth 4.x+ protocol.
* The MTU is negotiated up to the standard max LE MTU (517 bytes requested).
* The initial connection interval is 18.75 ms, which updates to 198.75 ms during idle periods to reduce power consumption.

### GATT Profile & Characteristics
All device-specific operations occur within the S1 Custom Service, which uses the base UUID `ACAB0001-67F5-479E-8711-B3B99198CE6C`.

* **Sync Control (`ACAB0002...`, Handle 0x0010):** A 1-byte read/write characteristic. Writing `0x00` disables sync or resets the session, while `0x01` enables sync so the device accepts schedule writes. The app reads back the value after writing to confirm it was accepted before proceeding.
* **Weekly Schedule (`ACAB0003...`, Handle 0x0013):** An 84-byte read/write payload holding the 7-day timer schedule.
    * The payload is divided into 12 bytes per day, representing Monday to Sunday.
    * Each day contains three 4-byte time slots.
    * A single slot encodes: ON minutes (byte 0), ON hours (byte 1), OFF minutes (byte 2), and a bit-packed field for OFF hours and the enable flag (byte 3, where the high bit 0x80 indicates the slot is enabled).
* **RTC Set (`ACAB0004...`, Handle 0x0016):** A 7-byte read/write characteristic used to set the device clock. The format includes Day, Month, Year offset (device-specific epoch), Sub-field, Hours, Minutes, and Seconds.
* **RTC Read (`ACAB0005...`, Handle 0x0019):** A 7-byte read-only characteristic that returns the current device time using the same format as RTC Set.

### Synchronisation Flow
A complete synchronization session follows a strict sequence:
1. The app discovers custom services and characteristics.
2. The app writes `0x00` to Sync Control to reset, then writes `0x01` to enable synchronization.
3. The app reads the current Weekly Schedule, then writes the updated 84-byte schedule.
4. The app reads the device RTC, writes the new RTC payload to synchronize the clock, and polls the RTC again to confirm.
5. The app initiates an HCI disconnect to tear down the LE link.

### Disconnect Procedures
The specification outlines expected behaviors for different disconnect initiators:
* **Host-Initiated:** When the application terminates the connection, it sends reason code `0x13` (Remote User Terminated), resulting in a normal Disconnection Complete event with reason `0x16` (Connection Terminated by Local Host).
* **Device-Initiated:** If the device unexpectedly terminates the connection, the application must handle the specific reason codes.
    * **Code `0x3E` (Connection Failed to be Established):** This is the most common transient error; the application should automatically retry the connection up to 3 times before displaying an error.
    * **Code `0x08` (Connection Timeout):** This indicates the device moved out of range or was powered off.

## Requirements

* **IDE:** Android Studio
* **Build System:** Gradle
* **Target SDK:** API 35 (Android 15)
* **Permissions:** Requires `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`, and `ACCESS_FINE_LOCATION` to function.

## Getting Started

1. Clone the repository.
2. Open the project directory in Android Studio.
3. Sync project with Gradle files.
4. Build and run on a physical Android device (Note: BLE scanning and connections generally do not work on the standard Android Emulator).
