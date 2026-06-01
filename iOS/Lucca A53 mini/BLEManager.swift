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

    // Global override flag for developer mode
    static let enableDeveloperModeOverride = false

    // MARK: Published State
    @Published var state: S1State = .idle
    @Published var deviceName: String = ""
    @Published var deviceAddress: String = ""
    @Published var rtcString: String = ""
    @Published var rtcDriftWarning: Bool = false
    @Published var masterEnabled: Bool = false
    @Published var uiEntries: [UiEntry] = []
    @Published var syncStatus: String = "" {
        didSet {
            if !syncStatus.isEmpty {
                scheduleStatusClear()
            }
        }
    }
    @Published var isSyncing: Bool = false {
        didSet {
            if !isSyncing {
                isFullSync = false
            }
        }
    }
    @Published var scheduleChanged: Bool = false
    @Published var brewTemp: Double? = nil
    @Published var steamTemp: Double? = nil
    @Published var supportsTemperature: Bool = false
    
    // UI Failures
    @Published var showSyncFailure: Bool = false
    @Published var syncFailureItem: String = ""

    // Developer / Stub Mode State
    @Published var developerMode: Bool = false {
        didSet {
            UserDefaults.standard.set(developerMode, forKey: "lucca_dev_mode")
        }
    }
    
    // Stub properties (mirrors StubS1Device.java)
    private var stubSchedule = S1Schedule()
    private var pendingWriteError = false
    private var pendingDropConnection = false
    private var pendingVerifyFail = false
    private var rtcOffsetMs: TimeInterval = -37_000
    private var simTask: Task<Void, Never>? = nil
    private var tempSimTask: Task<Void, Never>? = nil

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
    private var rtcRetryCount = 0
    private var pendingSchedule: S1Schedule? = nil
    private var pendingClockSyncTime: Date? = nil
    private var isFullSync = false

    // BUG 1 FIX: track that a voluntary disconnect is in progress so
    // didDisconnectPeripheral doesn't misread wasConnected as false.
    private var voluntaryDisconnect = false

    private var scanTimer: Timer?
    private var opTimer: Timer?
    private var statusClearTimer: Timer?
    private var connectTimer: Timer?
    
    private static let SCAN_TIMEOUT: TimeInterval = 30
    private static let OP_TIMEOUT:   TimeInterval = 5

    override init() {
        super.init()
        if BLEManager.enableDeveloperModeOverride {
            self.developerMode = UserDefaults.standard.bool(forKey: "lucca_dev_mode")
        } else {
            self.developerMode = false
        }
        centralManager = CBCentralManager(delegate: self, queue: .main)
        
        // Android buildSampleSchedule() parity: Set up default mock entries for stub
        for d in 0..<5 {
            stubSchedule.slots[d][0].enabled = true
            stubSchedule.slots[d][0].onHour = 7
            stubSchedule.slots[d][0].onMinute = 30
            stubSchedule.slots[d][0].offHour = 8
            stubSchedule.slots[d][0].offMinute = 30
        }
    }

    // MARK: - Public API

    func startScan() {
        if developerMode {
            state = .scanning
            simTask?.cancel()
            simTask = Task { @MainActor in
                try? await Task.sleep(for: .milliseconds(1800)) // DELAY_SCAN_FOUND
                guard !Task.isCancelled else { return }
                self.deviceName = "Lucca #STUB"
                self.deviceAddress = "DE:AD:BE:EF:CA:FE"
                self.supportsTemperature = true
                self.state = .deviceFound(name: "Lucca #STUB", address: "DE:AD:BE:EF:CA:FE")
                
                // Connection handshake sequence:
                try? await Task.sleep(for: .milliseconds(600)) // DELAY_CONNECTING
                guard !Task.isCancelled else { return }
                self.state = .connecting
                
                try? await Task.sleep(for: .milliseconds(400)) // DELAY_CONNECTED
                guard !Task.isCancelled else { return }
                self.state = .connected
                
                try? await Task.sleep(for: .milliseconds(700)) // DELAY_SERVICES
                guard !Task.isCancelled else { return }
                self.state = .servicesDiscovered
            }
            return
        }

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
        if developerMode {
            simTask?.cancel()
            simTask = nil
            if case .scanning = state { state = .idle }
            return
        }
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
        
        // Android parity: Explicit 10-second connection timeout guard
        connectTimer?.invalidate()
        connectTimer = Timer.scheduledTimer(withTimeInterval: 10.0, repeats: false) { [weak self] _ in
            guard let self = self else { return }
            Task { @MainActor in
                if case .connecting = self.state {
                    if let p = self.peripheral {
                        self.centralManager.cancelPeripheralConnection(p)
                    }
                    self.state = .error("Connection timed out")
                }
            }
        }
    }

    func disconnect() {
        // BUG 1 FIX: set flag BEFORE cancelling so didDisconnectPeripheral
        // knows this was intentional and doesn't flip to .error.
        voluntaryDisconnect = true

        opQueue.removeAll()
        opInProgress = false
        opTimer?.invalidate(); opTimer = nil
        connectTimer?.invalidate(); connectTimer = nil

        // BUG 4 FIX: reset sync state on disconnect so spinner never hangs.
        isSyncing = false
        currentSyncType = .none
        pendingSchedule = nil
        pendingClockSyncTime = nil
        syncAttempts = 0

        if developerMode {
            simTask?.cancel()
            simTask = nil
            tempSimTask?.cancel()
            tempSimTask = nil
            state = .disconnected
            return
        }

        if let p = peripheral { centralManager.cancelPeripheralConnection(p) }
        // peripheral / characteristics are cleared in didDisconnectPeripheral
        state = .disconnected
    }

    // MARK: - Protocol Operations

    func readScheduleAndState() {
        if developerMode {
            simTask?.cancel()
            simTask = Task { @MainActor in
                // Simulating schedule read
                try? await Task.sleep(for: .milliseconds(120)) // DELAY_GATT_OP
                guard !Task.isCancelled else { return }
                
                var raw: [UInt8]
                if pendingVerifyFail {
                    pendingVerifyFail = false
                    // Android buildSampleSchedule() parity
                    var bad = S1Schedule()
                    for d in 0..<5 {
                        bad.slots[d][0].enabled = true
                        bad.slots[d][0].onHour = 7
                        bad.slots[d][0].onMinute = 30
                        bad.slots[d][0].offHour = 8
                        bad.slots[d][0].offMinute = 30
                    }
                    raw = bad.toBytes()
                } else {
                    raw = stubSchedule.toBytes()
                }
                
                let readBack = S1Schedule.fromBytes(raw)
                hardwareSchedule = readBack
                uiEntries = S1Schedule.loadUiEntries(from: readBack)
                
                // Simulating RTC read
                try? await Task.sleep(for: .milliseconds(120)) // DELAY_GATT_OP
                guard !Task.isCancelled else { return }
                
                let simulatedDate = Date().addingTimeInterval(rtcOffsetMs)
                rtcString = "Espresso Clock: \(BLEManager.formatRtcDate(simulatedDate)) [STUB]"
                rtcDriftWarning = abs(simulatedDate.timeIntervalSinceNow) > 120
                
                // Simulating Sync Control read
                try? await Task.sleep(for: .milliseconds(120)) // DELAY_GATT_OP
                guard !Task.isCancelled else { return }
                
                masterEnabled = true // default enabled
                
                isSyncing = false
                syncStatus = "Schedule loaded"
                scheduleStatusClear()
            }
            return
        }

        enqueue { [weak self] in self?.readChar(\.charSchedule) }
        enqueue { [weak self] in self?.readChar(\.charRtcRead) }
        enqueue { [weak self] in self?.readChar(\.charSyncControl) }
    }

    func syncSchedule(_ schedule: S1Schedule) {
        enqueue { [weak self] in self?.writeChar(\.charSyncControl, data: Data([0x00])) }
        enqueue { [weak self] in self?.writeChar(\.charSyncControl, data: Data([0x01])) }
        enqueue { [weak self] in self?.writeChar(\.charSchedule,    data: Data(schedule.toBytes())) }
    }

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
        if developerMode {
            tempSimTask?.cancel()
            tempSimTask = Task { @MainActor in
                try? await Task.sleep(for: .milliseconds(120))
                guard !Task.isCancelled else { return }
                brewTemp = 93.4
                steamTemp = 122.1
            }
            return
        }
        enqueue { [weak self] in self?.readChar(\.charBrewTemp) }
        enqueue { [weak self] in self?.readChar(\.charSteamTemp) }
    }

    // MARK: - Schedule UI helpers

    func startScheduleSync() {
        isFullSync = true
        pendingSchedule = S1Schedule.convertUiToHardware(uiEntries)
        
        currentSyncType = .clock
        let cal = Calendar.current
        var comps = cal.dateComponents([.year, .month, .day, .hour, .minute, .weekday], from: Date())
        comps.second = 0
        pendingClockSyncTime = cal.date(from: comps)
        syncAttempts = 1
        rtcRetryCount = 0
        performWrite()
    }

    func startClockSync() {
        isFullSync = false
        currentSyncType = .clock
        let cal = Calendar.current
        var comps = cal.dateComponents([.year, .month, .day, .hour, .minute, .weekday], from: Date())
        comps.second = 0
        pendingClockSyncTime = cal.date(from: comps)
        syncAttempts = 1
        rtcRetryCount = 0
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
        if currentSyncType == .schedule && pendingSchedule == nil {
            isSyncing = false
            currentSyncType = .none
            return
        }
        isSyncing = true

        if developerMode {
            simTask?.cancel()
            simTask = Task { @MainActor in
                switch currentSyncType {
                case .schedule:
                    guard let sched = pendingSchedule else { return }
                    syncStatus = "Syncing schedule…"
                    
                    try? await Task.sleep(for: .milliseconds(120))
                    guard !Task.isCancelled else { return }
                    
                    try? await Task.sleep(for: .milliseconds(120))
                    guard !Task.isCancelled else { return }
                    
                    try? await Task.sleep(for: .milliseconds(250)) // DELAY_WRITE_SCHEDULE
                    guard !Task.isCancelled else { return }
                    
                    if pendingWriteError {
                        pendingWriteError = false
                        isSyncing = false
                        currentSyncType = .none
                        pendingSchedule = nil
                        syncStatus = "Error: GATT Write Error (Simulated)"
                        showSyncFailure = true
                        syncFailureItem = "schedule"
                        return
                    }
                    
                    try? await Task.sleep(for: .milliseconds(120))
                    guard !Task.isCancelled else { return }
                    
                    if pendingVerifyFail {
                        handleSyncRetry()
                    } else {
                        stubSchedule = sched
                        hardwareSchedule = sched
                        pendingSchedule = nil
                        currentSyncType = .none
                        isSyncing = false
                        scheduleChanged = false
                        syncStatus = "Schedule verified"
                        scheduleStatusClear()
                        
                        if pendingDropConnection {
                            pendingDropConnection = false
                            try? await Task.sleep(for: .milliseconds(400))
                            voluntaryDisconnect = false
                            state = .disconnected
                        }
                    }
                    
                case .clock:
                    guard let pending = pendingClockSyncTime else { return }
                    syncStatus = "Syncing clock…"
                    
                    try? await Task.sleep(for: .milliseconds(120))
                    guard !Task.isCancelled else { return }
                    
                    try? await Task.sleep(for: .milliseconds(120))
                    guard !Task.isCancelled else { return }
                    
                    if pendingVerifyFail {
                        handleSyncRetry()
                    } else {
                        rtcOffsetMs = 0
                        let simulatedDate = Date()
                        rtcString = "Espresso Clock: \(BLEManager.formatRtcDate(simulatedDate)) [STUB]"
                        rtcDriftWarning = false
                        
                        if isFullSync {
                            currentSyncType = .schedule
                            syncAttempts = 1
                            performWrite()
                        } else {
                            currentSyncType = .none
                            pendingClockSyncTime = nil
                            isSyncing = false
                            syncStatus = "Clock verified"
                            scheduleStatusClear()
                        }
                    }
                    
                case .masterToggle:
                    syncStatus = "Updating timer…"
                    
                    try? await Task.sleep(for: .milliseconds(120))
                    guard !Task.isCancelled else { return }
                    
                    try? await Task.sleep(for: .milliseconds(120))
                    guard !Task.isCancelled else { return }
                    
                    isSyncing = false
                    currentSyncType = .none
                    syncStatus = "Scheduler \(masterEnabled ? "enabled" : "disabled")"
                    scheduleStatusClear()
                    
                case .none:
                    isSyncing = false
                }
            }
            return
        }

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
            isSyncing = false
            let itemName = (currentSyncType == .schedule) ? "schedule" : "clock settings"
            currentSyncType = .none
            pendingSchedule = nil
            pendingClockSyncTime = nil
            syncStatus = ""
            
            syncFailureItem = itemName
            showSyncFailure = true
        }
    }

    // BUG 8 / 11 FIX: auto-clear status banner after success messages.
    private func scheduleStatusClear() {
        statusClearTimer?.invalidate()
        statusClearTimer = Timer.scheduledTimer(withTimeInterval: 2.0, repeats: false) { [weak self] _ in
            guard let selfRef = self else { return }
            Task { @MainActor in selfRef.syncStatus = "" }
        }
    }
    
    private func logScheduleMismatch(read: [UInt8], pending: [UInt8]) {
        print("Verification failed. Byte mismatch:")
        for i in 0..<7 {
            let start = i * 12
            let end = start + 12
            guard end <= read.count, end <= pending.count else { continue }
            let rDay = Array(read[start..<end])
            let pDay = Array(pending[start..<end])
            if rDay != pDay {
                print("Day \(i) mismatch!")
                print("  Read:    \(rDay)")
                print("  Pending: \(pDay)")
            }
        }
    }

    // MARK: - RTC helpers

    static func buildRtcPayload() -> [UInt8] {
        let cal = Calendar.current
        let now = Date()
        let year  = cal.component(.year,    from: now) - 2000
        let month = cal.component(.month,   from: now)
        let day   = cal.component(.day,     from: now)
        // BUG FIX: Map iOS DOW (Sun=1..Sat=7) to S1 Machine DOW (Mon=0..Sun=6)
        let calendarDow = cal.component(.weekday, from: now)
        let dow = (calendarDow + 5) % 7
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
        // BUG FIX: Map S1 Machine DOW (Mon=0..Sun=6) back to iOS DOW (Sun=1..Sat=7)
        let rawDow = Int(data[3])
        c.weekday = rawDow != 255 ? ((rawDow + 1) % 7 + 1) : 255
        c.hour    = Int(data[4])
        c.minute  = Int(data[5])
        c.second  = Int(data[6])
        return c
    }

    static func formatRtcDate(_ date: Date) -> String {
        let timeFormatter = DateFormatter()
        timeFormatter.dateStyle = .none
        timeFormatter.timeStyle = .short
        let timeStr = timeFormatter.string(from: date)

        let dayFormatter = DateFormatter()
        dayFormatter.dateFormat = "EEE"
        let dayStr = dayFormatter.string(from: date)

        let dateFormatter = DateFormatter()
        dateFormatter.dateStyle = .short
        dateFormatter.timeStyle = .none
        let dateStr = dateFormatter.string(from: date)

        return "\(timeStr) \(dayStr) \(dateStr)"
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
                if selfRef.currentSyncType != .none {
                    print("Operation timed out during sync! Retrying...")
                    selfRef.handleSyncRetry()
                } else {
                    selfRef.drainQueue()
                }
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
            self.connectTimer?.invalidate(); self.connectTimer = nil
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
            self.connectTimer?.invalidate(); self.connectTimer = nil
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
                        syncStatus = "Scheduler \(enabled ? "enabled" : "disabled")"
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
                        syncStatus = "Schedule verified"
                        hardwareSchedule = pending
                        pendingSchedule = nil
                        currentSyncType = .none
                        isSyncing = false
                        scheduleChanged = false
                        isFullSync = false
                        scheduleStatusClear()
                    } else {
                        logScheduleMismatch(read: raw, pending: pending.toBytes())
                        handleSyncRetry()
                    }
                } else {
                    hardwareSchedule = readBack
                    uiEntries = S1Schedule.loadUiEntries(from: readBack)
                    syncStatus = "Schedule loaded"
                    isSyncing = false
                    scheduleChanged = false
                    scheduleStatusClear()   // BUG 8/11 FIX: auto-dismiss banner
                }

            } else if uuid.contains("ACAB0005") || uuid.contains("ACAB0004") {
                guard let comps = BLEManager.parseRtc(data) else { return }
                let devDate = Calendar.current.date(from: comps) ?? Date()
                rtcString = "Espresso Clock: \(BLEManager.formatRtcDate(devDate))"
                let drift = abs(devDate.timeIntervalSinceNow)
                
                // Matches Android threshold (2 minutes)
                rtcDriftWarning = drift > 120

                if currentSyncType == .clock, let pending = pendingClockSyncTime {
                    let deviceDow = comps.weekday ?? 255
                    let expectedDow = Calendar.current.component(.weekday, from: pending)

                    // Error verification: If the machine rejects the Day of Week
                    if deviceDow != expectedDow || deviceDow == 255 {
                        if rtcRetryCount < 2 {
                            rtcRetryCount += 1
                            let delay = rtcRetryCount > 1 ? 0.5 : 0.0
                            DispatchQueue.main.asyncAfter(deadline: .now() + delay) { [weak self] in
                                self?.enqueue { [weak self] in self?.writeChar(\.charRtcSet, data: Data(BLEManager.buildRtcPayload())) }
                            }
                            return
                        } else {
                            syncStatus = "RTC Sync Failed: Device rejected DOW"
                            isSyncing = false
                            currentSyncType = .none
                            pendingClockSyncTime = nil
                            
                            syncFailureItem = "clock settings"
                            showSyncFailure = true
                            return
                        }
                    }

                    if abs(devDate.timeIntervalSince(pending)) < 30 {
                        rtcDriftWarning = false
                        pendingClockSyncTime = nil
                        
                        if isFullSync {
                            currentSyncType = .schedule
                            syncAttempts = 1
                            performWrite()
                        } else {
                            syncStatus = "Clock verified"
                            isSyncing = false
                            currentSyncType = .none
                            scheduleStatusClear()
                        }
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

    // MARK: - Developer / Fault Injection APIs
    
    func injectWriteError() {
        pendingWriteError = true
    }
    
    func injectConnectionDrop() {
        pendingDropConnection = true
    }
    
    func injectVerifyFail() {
        pendingVerifyFail = true
    }
    
    func clearVerifyFail() {
        pendingVerifyFail = false
    }
    
    func injectRtcDrift(_ minutes: Int) {
        rtcOffsetMs += TimeInterval(minutes * 60)
    }
    
    func injectCorruptSchedule() {
        var randomSched = S1Schedule()
        for day in 0..<7 {
            for slot in 0..<3 {
                if Double.random(in: 0...1) > 0.6 {
                    randomSched.slots[day][slot].enabled = true
                    randomSched.slots[day][slot].onHour = Int.random(in: 0...23)
                    randomSched.slots[day][slot].onMinute = Int.random(in: 0...59)
                    randomSched.slots[day][slot].offHour = Int.random(in: 0...23)
                    randomSched.slots[day][slot].offMinute = Int.random(in: 0...59)
                }
            }
        }
        stubSchedule = randomSched
    }
}
