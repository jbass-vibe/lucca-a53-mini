import Foundation
import CoreBluetooth
import Combine

// MARK: - UUIDs (exact match from Android BleManager.java)
extension CBUUID {
    static let s1Service      = CBUUID(string: "ACAB0001-67F5-479E-8711-B3B99198CE6C")
    static let s1SyncControl  = CBUUID(string: "ACAB0002-67F5-479E-8711-B3B99198CE6C")
    static let s1Schedule     = CBUUID(string: "ACAB0003-67F5-479E-8711-B3B99198CE6C")
    static let s1RtcSet       = CBUUID(string: "ACAB0004-67F5-479E-8711-B3B99198CE6C")
    static let s1RtcRead      = CBUUID(string: "ACAB0005-67F5-479E-8711-B3B99198CE6C")
    static let s1BrewTemp     = CBUUID(string: "ACAB0006-67F5-479E-8711-B3B99198CE6C")
    static let s1SteamTemp    = CBUUID(string: "ACAB0007-67F5-479E-8711-B3B99198CE6C")
}

// MARK: - Connection State
enum S1State: Equatable {
    case idle
    case scanning
    case deviceFound(name: String, address: String)
    case connecting
    case connected
    case servicesDiscovered
    case disconnected
    case timeout
    case error(String)
    case permissionDenied
    case btDisabled
}

// MARK: - Sync Type
enum SyncType {
    case none, schedule, clock, masterToggle
}

// MARK: - BLEManager
@MainActor
class BLEManager: NSObject, ObservableObject {

    // MARK: Published State
    @Published var state: S1State = .idle
    @Published var deviceName: String = ""
    @Published var deviceAddress: String = ""
    @Published var rtcString: String = ""
    @Published var rtcDriftWarning: Bool = false
    @Published var masterEnabled: Bool = false
    @Published var uiEntries: [UiEntry] = []
    @Published var syncStatus: String = ""
    @Published var isSyncing: Bool = false
    @Published var scheduleChanged: Bool = false
    @Published var brewTemp: Double? = nil
    @Published var steamTemp: Double? = nil
    @Published var supportsTemperature: Bool = false

    // MARK: Private
    private var centralManager: CBCentralManager!
    private var peripheral: CBPeripheral?

    private var charSyncControl: CBCharacteristic?
    private var charSchedule:    CBCharacteristic?
    private var charRtcSet:      CBCharacteristic?
    private var charRtcRead:     CBCharacteristic?
    private var charBrewTemp:    CBCharacteristic?
    private var charSteamTemp:   CBCharacteristic?

    private var opQueue: [() -> Void] = []
    private var opInProgress = false

    private var hardwareSchedule = S1Schedule()
    private var currentSyncType: SyncType = .none
    private var syncAttempts = 0
    private var pendingSchedule: S1Schedule? = nil
    private var pendingClockSyncTime: Date? = nil

    // BUG 1 FIX: track that a voluntary disconnect is in progress so
    // didDisconnectPeripheral doesn't misread wasConnected as false.
    private var voluntaryDisconnect = false

    private var scanTimer: Timer?
    private var opTimer: Timer?
    private var statusClearTimer: Timer?
    private static let SCAN_TIMEOUT: TimeInterval = 30
    private static let OP_TIMEOUT:   TimeInterval = 5

    override init() {
        super.init()
        centralManager = CBCentralManager(delegate: self, queue: .main)
    }

    // MARK: - Public API

    func startScan() {
        guard centralManager.state == .poweredOn else {
            // BUG 9 FIX: surface correct reason for non-poweredOn states
            switch centralManager.state {
            case .unauthorized: state = .permissionDenied
            default:            state = .btDisabled
            }
            return
        }
        state = .scanning
        let settings = [CBCentralManagerScanOptionAllowDuplicatesKey: false]
        centralManager.scanForPeripherals(withServices: nil, options: settings)
        scanTimer = Timer.scheduledTimer(withTimeInterval: BLEManager.SCAN_TIMEOUT, repeats: false) { [weak self] _ in
            guard let selfRef = self else { return }
            Task { @MainActor in selfRef.onScanTimeout() }
        }
    }

    func stopScan() {
        scanTimer?.invalidate(); scanTimer = nil
        centralManager.stopScan()
        if case .scanning = state { state = .idle }
    }

    func connect(to periph: CBPeripheral) {
        stopScan()
        peripheral = periph
        peripheral?.delegate = self
        state = .connecting
        centralManager.connect(periph, options: nil)
    }

    func disconnect() {
        // BUG 1 FIX: set flag BEFORE cancelling so didDisconnectPeripheral
        // knows this was intentional and doesn't flip to .error.
        voluntaryDisconnect = true

        opQueue.removeAll()
        opInProgress = false
        opTimer?.invalidate(); opTimer = nil

        // BUG 4 FIX: reset sync state on disconnect so spinner never hangs.
        isSyncing = false
        currentSyncType = .none
        pendingSchedule = nil
        pendingClockSyncTime = nil
        syncAttempts = 0

        if let p = peripheral { centralManager.cancelPeripheralConnection(p) }
        // peripheral / characteristics are cleared in didDisconnectPeripheral
        state = .disconnected
    }

    // MARK: - Protocol Operations

    func readScheduleAndState() {
        enqueue { [weak self] in self?.readChar(\.charSchedule) }
        enqueue { [weak self] in self?.readChar(\.charRtcRead) }
        enqueue { [weak self] in self?.readChar(\.charSyncControl) }
    }

    func syncSchedule(_ schedule: S1Schedule) {
        enqueue { [weak self] in self?.writeChar(\.charSyncControl, data: Data([0x00])) }
        enqueue { [weak self] in self?.writeChar(\.charSyncControl, data: Data([0x01])) }
        enqueue { [weak self] in self?.writeChar(\.charSchedule,    data: Data(schedule.toBytes())) }
        // Read-back is triggered inside didWriteValueFor (not here) to avoid duplicate reads.
    }

    // BUG 2 FIX: syncRtc no longer enqueues the read itself — didWriteValueFor
    // does it after the write completes, so the read fires exactly once.
    func syncRtc() {
        let payload = BLEManager.buildRtcPayload()
        enqueue { [weak self] in self?.writeChar(\.charRtcSet, data: Data(payload)) }
    }

    func writeSyncControl(_ enabled: Bool) {
        enqueue { [weak self] in self?.writeChar(\.charSyncControl, data: Data([enabled ? 0x01 : 0x00])) }
    }

    func readSyncControl() {
        enqueue { [weak self] in self?.readChar(\.charSyncControl) }
    }

    func readBrewAndSteam() {
        enqueue { [weak self] in self?.readChar(\.charBrewTemp) }
        enqueue { [weak self] in self?.readChar(\.charSteamTemp) }
    }

    // MARK: - Schedule UI helpers

    func startScheduleSync() {
        currentSyncType = .schedule
        pendingSchedule = S1Schedule.convertUiToHardware(uiEntries)
        syncAttempts = 1
        performWrite()
    }

    func startClockSync() {
        currentSyncType = .clock
        let cal = Calendar.current
        var comps = cal.dateComponents([.year, .month, .day, .hour, .minute, .weekday], from: Date())
        comps.second = 0
        pendingClockSyncTime = cal.date(from: comps)
        syncAttempts = 1
        performWrite()
    }

    func startMasterToggle() {
        currentSyncType = .masterToggle
        syncAttempts = 1
        performWrite()
    }

    func addNewEntry() {
        uiEntries.append(UiEntry())
        checkScheduleChanged()
    }

    func removeEntry(_ entry: UiEntry) {
        uiEntries.removeAll { $0.id == entry.id }
        checkScheduleChanged()
    }

    func checkScheduleChanged() {
        let current = S1Schedule.convertUiToHardware(uiEntries)
        scheduleChanged = current.toBytes() != hardwareSchedule.toBytes()
    }

    // MARK: - Private perform write

    private func performWrite() {
        // BUG 5 FIX: validate pre-conditions before setting isSyncing so we
        // never leave isSyncing=true with no way to clear it.
        if currentSyncType == .schedule && pendingSchedule == nil {
            isSyncing = false
            currentSyncType = .none
            return
        }
        isSyncing = true
        switch currentSyncType {
        case .schedule:
            guard let sched = pendingSchedule else { return }
            syncStatus = "Syncing schedule…"
            syncSchedule(sched)
        case .clock:
            syncStatus = "Syncing clock…"
            syncRtc()
        case .masterToggle:
            syncStatus = "Updating timer…"
            writeSyncControl(masterEnabled)
            enqueue { [weak self] in self?.readChar(\.charSyncControl) }
        case .none:
            isSyncing = false
        }
    }

    // BUG 3 FIX: clear the queue before re-enqueuing so stale ops don't pile up.
    private func handleSyncRetry() {
        if syncAttempts < 3 {
            syncAttempts += 1
            opQueue.removeAll()
            opInProgress = false
            opTimer?.invalidate(); opTimer = nil
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) { [weak self] in
                self?.performWrite()
            }
        } else {
            syncStatus = "Sync failed after 3 attempts"
            isSyncing = false
            currentSyncType = .none
            pendingSchedule = nil
        }
    }

    // BUG 8 / 11 FIX: auto-clear status banner after success messages.
    private func scheduleStatusClear() {
        statusClearTimer?.invalidate()
        statusClearTimer = Timer.scheduledTimer(withTimeInterval: 3.0, repeats: false) { [weak self] _ in
            guard let selfRef = self else { return }
            Task { @MainActor in selfRef.syncStatus = "" }
        }
    }

    // MARK: - RTC helpers

    static func buildRtcPayload() -> [UInt8] {
        let cal = Calendar.current
        let now = Date()
        let year  = cal.component(.year,    from: now) - 2000
        let month = cal.component(.month,   from: now)
        let day   = cal.component(.day,     from: now)
        let dow   = cal.component(.weekday, from: now)   // 1=Sun..7=Sat
        let hour  = cal.component(.hour,    from: now)
        let min   = cal.component(.minute,  from: now)
        let sec   = cal.component(.second,  from: now)
        return [UInt8(year), UInt8(month), UInt8(day), UInt8(dow),
                UInt8(hour), UInt8(min),   UInt8(sec)]
    }

    static func parseRtc(_ data: Data) -> DateComponents? {
        guard data.count >= 7 else { return nil }
        var c = DateComponents()
        c.year    = Int(data[0]) + 2000
        c.month   = Int(data[1])
        c.day     = Int(data[2])
        c.weekday = Int(data[3])
        c.hour    = Int(data[4])
        c.minute  = Int(data[5])
        c.second  = Int(data[6])
        return c
    }

    static func parseTemperature(_ data: Data) -> Double {
        guard data.count >= 2 else { return 0 }
        let raw = Int16(bitPattern: UInt16(data[0]) | (UInt16(data[1]) << 8))
        return Double(raw) / 10.0
    }

    // MARK: - Operation Queue

    private func enqueue(_ op: @escaping () -> Void) {
        opQueue.append(op)
        if !opInProgress { drainQueue() }
    }

    private func drainQueue() {
        guard !opInProgress, !opQueue.isEmpty, peripheral != nil else { return }
        opInProgress = true
        opTimer = Timer.scheduledTimer(withTimeInterval: BLEManager.OP_TIMEOUT, repeats: false) { [weak self] _ in
            guard let selfRef = self else { return }
            Task { @MainActor in
                selfRef.opInProgress = false
                selfRef.drainQueue()
            }
        }
        let next = opQueue.removeFirst()
        next()
    }

    private func opComplete() {
        opTimer?.invalidate(); opTimer = nil
        opInProgress = false
        drainQueue()
    }

    // MARK: - Low level GATT

    private func readChar(_ keyPath: KeyPath<BLEManager, CBCharacteristic?>) {
        guard let p = peripheral, let c = self[keyPath: keyPath] else {
            opComplete(); return
        }
        p.readValue(for: c)
    }

    private func writeChar(_ keyPath: KeyPath<BLEManager, CBCharacteristic?>, data: Data) {
        guard let p = peripheral, let c = self[keyPath: keyPath] else {
            opComplete(); return
        }
        p.writeValue(data, for: c, type: .withResponse)
    }

    private func clearCharacteristics() {
        charSyncControl = nil; charSchedule  = nil
        charRtcSet      = nil; charRtcRead   = nil
        charBrewTemp    = nil; charSteamTemp = nil
    }

    private func onScanTimeout() {
        centralManager.stopScan()
        state = .timeout
    }
}

// MARK: - CBCentralManagerDelegate
extension BLEManager: CBCentralManagerDelegate {

    nonisolated func centralManagerDidUpdateState(_ central: CBCentralManager) {
        Task { @MainActor in
            // BUG 9 FIX: distinguish permission vs power-off
            switch central.state {
            case .poweredOn:
                if case .btDisabled = self.state { self.state = .idle }
                if case .permissionDenied = self.state { self.state = .idle }
            case .unauthorized:
                self.state = .permissionDenied
            default:
                self.state = .btDisabled
            }
        }
    }

    nonisolated func centralManager(_ central: CBCentralManager,
                                    didDiscover peripheral: CBPeripheral,
                                    advertisementData: [String: Any],
                                    rssi RSSI: NSNumber) {
        var matchedByUUID = false
        if let serviceUUIDs = advertisementData[CBAdvertisementDataServiceUUIDsKey] as? [CBUUID] {
            let s1Service = CBUUID(string: "ACAB0001-67F5-479E-8711-B3B99198CE6C")
            matchedByUUID = serviceUUIDs.contains(s1Service)
        }
        let name = (advertisementData[CBAdvertisementDataLocalNameKey] as? String)
                    ?? peripheral.name ?? ""
        let matchedByName = name.uppercased().hasPrefix("S1")
        guard matchedByUUID || matchedByName else { return }

        let fwSupportsTemp = name.lowercased().contains("v.2") || name.lowercased().contains("v.02")
        let addr = peripheral.identifier.uuidString

        Task { @MainActor in
            self.deviceName          = name.isEmpty ? "S1 Machine" : name
            self.deviceAddress       = addr
            self.supportsTemperature = fwSupportsTemp
            self.state = .deviceFound(name: self.deviceName, address: addr)
            self.connect(to: peripheral)
        }
    }

    nonisolated func centralManager(_ central: CBCentralManager,
                                    didConnect peripheral: CBPeripheral) {
        Task { @MainActor in
            self.voluntaryDisconnect = false
            self.state = .connected
            peripheral.delegate = self
            peripheral.discoverServices(nil)
        }
    }

    nonisolated func centralManager(_ central: CBCentralManager,
                                    didFailToConnect peripheral: CBPeripheral,
                                    error: Error?) {
        Task { @MainActor in
            self.state = .error(error?.localizedDescription ?? "Connection failed")
        }
    }

    nonisolated func centralManager(_ central: CBCentralManager,
                                    didDisconnectPeripheral peripheral: CBPeripheral,
                                    error: Error?) {
        Task { @MainActor in
            let wasVoluntary = self.voluntaryDisconnect
            self.voluntaryDisconnect = false
            self.peripheral = nil
            self.clearCharacteristics()

            // BUG 1 FIX: only show error for unexpected disconnects.
            // Voluntary disconnects (user tapped disconnect, or disconnect() was called)
            // already set state=.disconnected; don't overwrite with .error.
            if !wasVoluntary {
                let code = (error as NSError?)?.code ?? -1
                self.state = .error(self.humanReadableError(code, op: "connection"))
            }
            // Always reset sync state on any disconnect
            self.isSyncing = false
            self.currentSyncType = .none
            self.pendingSchedule = nil
            self.opQueue.removeAll()
            self.opInProgress = false
        }
    }
}

// MARK: - CBPeripheralDelegate
extension BLEManager: CBPeripheralDelegate {

    nonisolated func peripheral(_ peripheral: CBPeripheral,
                                didDiscoverServices error: Error?) {
        guard error == nil else { return }
        peripheral.services?.forEach { peripheral.discoverCharacteristics(nil, for: $0) }
    }

    nonisolated func peripheral(_ peripheral: CBPeripheral,
                                didDiscoverCharacteristicsFor service: CBService,
                                error: Error?) {
        guard error == nil else { return }

        Task { @MainActor [weak self] in
            guard let self else { return }
            for c in service.characteristics ?? [] {
                switch c.uuid.uuidString.uppercased() {
                case let u where u.contains("ACAB0002"): charSyncControl = c
                case let u where u.contains("ACAB0003"): charSchedule    = c
                case let u where u.contains("ACAB0004"): charRtcSet      = c
                case let u where u.contains("ACAB0005"): charRtcRead     = c
                case let u where u.contains("ACAB0006"): charBrewTemp    = c
                case let u where u.contains("ACAB0007"): charSteamTemp   = c
                default: break
                }
            }
            if charSyncControl != nil && charSchedule != nil {
                state = .servicesDiscovered
            }
        }
    }

    nonisolated func peripheral(_ peripheral: CBPeripheral,
                                didUpdateValueFor characteristic: CBCharacteristic,
                                error: Error?) {
        guard error == nil, let data = characteristic.value else {
            Task { @MainActor [weak self] in self?.opComplete() }
            return
        }
        let uuid = characteristic.uuid.uuidString.uppercased()

        Task { @MainActor [weak self] in
            guard let self else { return }
            self.opComplete()

            if uuid.contains("ACAB0002") {
                let enabled = data.count > 0 && data[0] == 0x01
                if currentSyncType == .masterToggle {
                    if enabled == masterEnabled {
                        syncStatus = "Scheduler \(enabled ? "enabled" : "disabled") ✓"
                        isSyncing = false
                        currentSyncType = .none
                        scheduleStatusClear()
                    } else {
                        handleSyncRetry()
                    }
                } else {
                    masterEnabled = enabled
                    isSyncing = false
                }

            } else if uuid.contains("ACAB0003") {
                var raw = [UInt8](repeating: 0, count: 84)
                data.copyBytes(to: &raw, count: min(data.count, 84))
                let readBack = S1Schedule.fromBytes(raw)

                if currentSyncType == .schedule, let pending = pendingSchedule {
                    if raw == pending.toBytes() {
                        syncStatus = "Schedule verified ✓"
                        hardwareSchedule = pending
                        pendingSchedule = nil
                        currentSyncType = .none
                        isSyncing = false
                        scheduleChanged = false
                        scheduleStatusClear()
                    } else {
                        handleSyncRetry()
                    }
                } else {
                    hardwareSchedule = readBack
                    uiEntries = S1Schedule.loadUiEntries(from: readBack)
                    syncStatus = "Schedule loaded ✓"
                    isSyncing = false
                    scheduleChanged = false
                    scheduleStatusClear()   // BUG 8/11 FIX: auto-dismiss banner
                }

            } else if uuid.contains("ACAB0005") || uuid.contains("ACAB0004") {
                guard let comps = BLEManager.parseRtc(data) else { return }
                let devDate = Calendar.current.date(from: comps) ?? Date()
                let df = DateFormatter(); df.dateStyle = .medium; df.timeStyle = .short
                rtcString = "Espresso Clock: \(df.string(from: devDate))"
                let drift = abs(devDate.timeIntervalSinceNow)
                rtcDriftWarning = drift > 300

                if currentSyncType == .clock, let pending = pendingClockSyncTime {
                    if abs(devDate.timeIntervalSince(pending)) < 30 {
                        syncStatus = "Clock verified ✓"
                        isSyncing = false
                        currentSyncType = .none
                        pendingClockSyncTime = nil
                        rtcDriftWarning = false
                        scheduleStatusClear()
                    } else {
                        handleSyncRetry()
                    }
                } else {
                    isSyncing = false
                }

            } else if uuid.contains("ACAB0006") {
                brewTemp = BLEManager.parseTemperature(data)
            } else if uuid.contains("ACAB0007") {
                steamTemp = BLEManager.parseTemperature(data)
            }
        }
    }

    nonisolated func peripheral(_ peripheral: CBPeripheral,
                                didWriteValueFor characteristic: CBCharacteristic,
                                error: Error?) {
        let uuid = characteristic.uuid.uuidString.uppercased()

        Task { @MainActor [weak self] in
            guard let self else { return }
            if let error {
                syncStatus = "Write error: \(error.localizedDescription)"
                isSyncing = false
                opQueue.removeAll()
                opComplete()
                return
            }
            opComplete()

            // BUG 2 FIX: only the write callback triggers the read-back, never syncRtc().
            if uuid.contains("ACAB0003") && currentSyncType == .schedule {
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.8) { [weak self] in
                    self?.enqueue { [weak self] in self?.readChar(\.charSchedule) }
                }
            }
            if uuid.contains("ACAB0004") && currentSyncType == .clock {
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.8) { [weak self] in
                    self?.enqueue { [weak self] in self?.readChar(\.charRtcRead) }
                }
            }
        }
    }

    private func humanReadableError(_ code: Int, op: String) -> String {
        switch code {
        case 8:   return "Connection lost. Device moved out of range or powered off."
        case 19:  return "Machine disconnected the link."
        case 133: return "Bluetooth stack error. Toggle Bluetooth off/on or restart the app."
        default:  return "Unexpected disconnection. Tap Scan to reconnect."
        }
    }
}
