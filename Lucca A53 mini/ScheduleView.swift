import SwiftUI

struct ScheduleView: View {
    @EnvironmentObject var ble: BLEManager
    @Environment(\.dismiss) var dismiss

    @State private var showDisconnectAlert  = false
    @State private var showDriftAlert       = false
    @State private var showConflictAlert:  String? = nil
    @State private var showMaxSlotsAlert:  String? = nil
    @State private var showTimeRangeAlert   = false
    @State private var showRemoveAlert: UiEntry? = nil
    @State private var initialLoadDone      = false

    // BUG 6 & 7 FIX: Task is @MainActor-isolated and stored so it can be cancelled.
    @State private var tempPollTask: Task<Void, Never>? = nil

    var body: some View {
        ZStack {
            Color.bgDark.ignoresSafeArea()
            VStack(spacing: 0) {
                topBar
                // BUG 11 FIX: banner only shows while actually syncing or while a
                // non-empty status exists; auto-clear is handled in BLEManager.
                if ble.isSyncing || !ble.syncStatus.isEmpty {
                    statusBanner
                }
                ScrollView {
                    VStack(spacing: 0) {
                        if !ble.rtcString.isEmpty { rtcRow }
                        if ble.supportsTemperature, ble.brewTemp != nil || ble.steamTemp != nil {
                            boilerTemps
                        }
                        masterToggleCard
                            .padding(.horizontal, 16).padding(.top, 12)
                        scheduleCards
                            .padding(.horizontal, 16).padding(.top, 8)
                        addSlotButton
                            .padding(.horizontal, 16).padding(.top, 8)
                        Spacer(minLength: 100)
                    }
                }
                bottomBar
            }
        }
        .navigationBarHidden(true)
        // ── Alerts ───────────────────────────────────────────────────────
        .alert("Disconnect or Exit", isPresented: $showDisconnectAlert) {
            Button("Exit App",    role: .destructive) { ble.disconnect(); exit(0) }
            Button("Disconnect")                      { ble.disconnect(); dismiss() }
            Button("Cancel",      role: .cancel)      {}
        } message: {
            Text("Would you like to disconnect from the machine and return to the scan screen, or exit the application entirely?")
        }
        .alert("Update Clock", isPresented: $showDriftAlert) {
            Button("Sync Now") { ble.startClockSync() }
            Button("Later", role: .cancel) {}
        } message: {
            Text("Your espresso machine's internal clock has drifted and no longer matches your phone.\n\nWould you like to synchronize the machine's time now?")
        }
        .alert("Time Conflict", isPresented: .init(
            get: { showConflictAlert != nil },
            set: { if !$0 { showConflictAlert = nil } }
        )) {
            Button("Got it", role: .cancel) {}
        } message: { Text(showConflictAlert ?? "") }
        .alert("Limit Reached", isPresented: .init(
            get: { showMaxSlotsAlert != nil },
            set: { if !$0 { showMaxSlotsAlert = nil } }
        )) {
            Button("OK", role: .cancel) {}
        } message: { Text(showMaxSlotsAlert ?? "") }
        .alert("Invalid Time Range", isPresented: $showTimeRangeAlert) {
            Button("OK", role: .cancel) {}
        } message: {
            Text("The boiler ON time must be earlier than the OFF time.")
        }
        .alert("Remove Scheduled Time", isPresented: .init(
            get: { showRemoveAlert != nil },
            set: { if !$0 { showRemoveAlert = nil } }
        )) {
            Button("Remove", role: .destructive) {
                if let e = showRemoveAlert { ble.removeEntry(e); showRemoveAlert = nil }
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("Are you sure you want to remove this scheduled ON/OFF time?")
        }
        // ── Lifecycle ────────────────────────────────────────────────────
        .onChange(of: ble.state) { _, newState in
            // BUG 1 FIX: now reliably fires because disconnect() correctly sets .disconnected
            if case .disconnected = newState { dismiss() }
            if case .error        = newState { dismiss() }
        }
        .onChange(of: ble.rtcDriftWarning) { _, warning in
            if warning { showDriftAlert = true }
        }
        .onAppear {
            if !initialLoadDone {
                initialLoadDone = true
                ble.isSyncing  = true
                ble.syncStatus = "Reading device schedule…"
                ble.readScheduleAndState()
            }
            if ble.supportsTemperature { startTempPolling() }
        }
        // BUG 7 FIX: cancel the poll task when the view disappears.
        .onDisappear {
            tempPollTask?.cancel()
            tempPollTask = nil
        }
    }

    // MARK: - Top Bar

    private var topBar: some View {
        HStack(spacing: 12) {
            Circle().fill(bleStatusColor).frame(width: 8, height: 8)
            Text(bleStatusText)
                .font(.system(size: 12, weight: .semibold, design: .monospaced))
                .foregroundColor(bleStatusColor)
            Spacer()
            if ble.rtcDriftWarning {
                Button { ble.startClockSync() } label: {
                    Image(systemName: "clock.arrow.2.circlepath")
                        .foregroundColor(.copper).frame(width: 36, height: 36)
                }
            }
            Button { showDisconnectAlert = true } label: {
                Image(systemName: "xmark.circle")
                    .foregroundColor(.textMuted).frame(width: 36, height: 36)
            }
        }
        .padding(.horizontal, 16).padding(.vertical, 10)
        .background(Color.cardSurface)
        .overlay(alignment: .bottom) { Divider().background(Color.divider) }
    }

    private var bleStatusColor: Color {
        switch ble.state {
        case .disconnected, .error: return .bleRed
        default:                    return .bleGreen
        }
    }
    private var bleStatusText: String {
        if case .disconnected = ble.state { return "Disconnected" }
        return "Connected"
    }

    // MARK: - Status Banner

    private var statusBanner: some View {
        HStack(spacing: 8) {
            if ble.isSyncing {
                ProgressView().tint(.copper).scaleEffect(0.8)
            } else {
                Image(systemName: ble.syncStatus.contains("✓") ? "checkmark.circle.fill" : "info.circle.fill")
                    .foregroundColor(ble.syncStatus.contains("✓") ? .bleGreen : .copper)
                    .font(.system(size: 13))
            }
            Text(ble.syncStatus)
                .font(.system(size: 12, design: .monospaced))
                .foregroundColor(.textPrimary)
            Spacer()
        }
        .padding(.horizontal, 16).padding(.vertical, 8)
        .background(Color.cardSurface2)
        .overlay(alignment: .bottom) { Divider().background(Color.divider) }
    }

    // MARK: - RTC / Temps

    private var rtcRow: some View {
        Text(ble.rtcString)
            .font(.system(size: 11, design: .monospaced))
            .foregroundColor(.textMuted)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 16).padding(.vertical, 6)
    }

    private var boilerTemps: some View {
        HStack(spacing: 16) {
            if let b = ble.brewTemp {
                Label(String(format: "Brew: %.1f°C", b), systemImage: "thermometer.medium")
                    .font(.system(size: 12, design: .monospaced)).foregroundColor(.copper)
            }
            if let s = ble.steamTemp {
                Label(String(format: "Steam: %.1f°C", s), systemImage: "thermometer.high")
                    .font(.system(size: 12, design: .monospaced)).foregroundColor(.copperLight)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 16).padding(.vertical, 6)
        .background(Color.cardSurface2)
    }

    // MARK: - Master Toggle

    private var masterToggleCard: some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text(ble.masterEnabled ? "All timers enabled" : "All timers disabled")
                    .font(.system(size: 14, weight: .semibold, design: .monospaced))
                    .foregroundColor(.textPrimary)
                Text("Controls whether the weekly schedule is active")
                    .font(.system(size: 11, design: .monospaced))
                    .foregroundColor(.textMuted)
            }
            Spacer()
            Toggle("", isOn: Binding(
                get: { ble.masterEnabled },
                set: { val in ble.masterEnabled = val; ble.startMasterToggle() }
            ))
            .tint(.copper).labelsHidden()
        }
        .padding(14)
        .luccaCard()
    }

    // MARK: - Schedule Cards

    private var scheduleCards: some View {
        VStack(spacing: 12) {
            ForEach(ble.uiEntries) { entry in
                SlotCardView(
                    entry: entry,
                    isActive: ble.masterEnabled,
                    onDelete: { showRemoveAlert = entry },
                    onTimeConflict: { dayName in
                        showConflictAlert = "This time overlaps an existing schedule on \(dayName). Ensure ON and OFF times don't conflict with other slots."
                    },
                    onMaxSlots: { dayName in
                        showMaxSlotsAlert = "\(dayName) already has the maximum 3 scheduled times. Remove one before adding another."
                    },
                    onTimeRangeError: { showTimeRangeAlert = true },
                    onChanged: { ble.checkScheduleChanged() },
                    allEntries: ble.uiEntries
                )
            }
        }
    }

    // MARK: - Add Slot Button

    private var addSlotButton: some View {
        Button {
            guard ble.masterEnabled else { return }
            ble.addNewEntry()
        } label: {
            Label("Add Scheduled Time", systemImage: "plus.circle")
                .font(.system(size: 13, weight: .semibold, design: .monospaced))
                .frame(maxWidth: .infinity)
        }
        .buttonStyle(CopperButtonStyle(isSecondary: true))
        .opacity(ble.masterEnabled ? 1 : 0.45)
        .disabled(!ble.masterEnabled)
    }

    // MARK: - Bottom Bar

    private var bottomBar: some View {
        VStack(spacing: 0) {
            Divider().background(Color.divider)
            Button { checkAndSync() } label: {
                HStack {
                    if ble.isSyncing {
                        ProgressView().tint(.bgDark).scaleEffect(0.85)
                        Text("Syncing…")
                    } else if ble.scheduleChanged {
                        Text("Save & Sync  →")
                    } else {
                        Image(systemName: "checkmark.circle.fill")
                        Text("Schedule Synced ✓")
                    }
                }
                .frame(maxWidth: .infinity)
            }
            .buttonStyle(SyncButtonStyle(changed: ble.scheduleChanged, syncing: ble.isSyncing))
            .disabled(!ble.scheduleChanged || ble.isSyncing)
            .padding(16)
        }
        .background(Color.cardSurface)
    }

    // MARK: - Validation & Sync

    private func checkAndSync() {
        for entry in ble.uiEntries {
            if !entry.days.contains(true) {
                showConflictAlert = "One of your scheduled times has no days selected. Please select at least one day or remove the card before syncing."
                return
            }
        }
        ble.startScheduleSync()
    }

    // MARK: - Temperature Polling
    // BUG 6 FIX: Task is @MainActor so calling ble (also @MainActor) is safe.
    // BUG 7 FIX: stored in @State and cancelled in onDisappear.
    private func startTempPolling() {
        tempPollTask?.cancel()
        tempPollTask = Task { @MainActor in
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(30))
                guard !Task.isCancelled else { break }
                ble.readBrewAndSteam()
            }
        }
    }
}

// MARK: - Sync Button Style

struct SyncButtonStyle: ButtonStyle {
    let changed: Bool
    let syncing: Bool

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.system(size: 15, weight: .bold, design: .monospaced))
            .foregroundColor(changed ? .bgDark : .bleGreen)
            .padding(.vertical, 14)
            .frame(maxWidth: .infinity)
            .background(
                RoundedRectangle(cornerRadius: 14)
                    .fill(changed ? Color.copper : Color.bleGreen.opacity(0.18))
                    .opacity(configuration.isPressed ? 0.8 : 1)
            )
    }
}

// MARK: - Slot Card View

struct SlotCardView: View {
    @ObservedObject var entry: UiEntry
    let isActive:        Bool
    let onDelete:        () -> Void
    let onTimeConflict:  (String) -> Void
    let onMaxSlots:      (String) -> Void
    let onTimeRangeError: () -> Void
    let onChanged:       () -> Void
    let allEntries:      [UiEntry]

    @State private var showOnTimePicker  = false
    @State private var showOffTimePicker = false

    var body: some View {
        VStack(spacing: 12) {
            HStack(spacing: 10) {
                timeButton(label: "ON",  time: entry.onTimeString,  color: .onLabel,  bg: .onBtnBg)  { showOnTimePicker  = true }
                Image(systemName: "arrow.right").font(.system(size: 11)).foregroundColor(.textMuted)
                timeButton(label: "OFF", time: entry.offTimeString, color: .offLabel, bg: .offBtnBg) { showOffTimePicker = true }
                Spacer()
                Button(action: onDelete) {
                    Image(systemName: "trash").foregroundColor(.textMuted).frame(width: 36, height: 36)
                }
            }

            if entry.onH == entry.offH && entry.onM == entry.offM {
                Text("⚠ ON and OFF times are the same")
                    .font(.system(size: 10, design: .monospaced))
                    .foregroundColor(.bleRed)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }

            HStack(spacing: 6) {
                ForEach(0..<7, id: \.self) { i in dayChip(index: i) }
            }
        }
        .padding(14)
        .luccaCard()
        .opacity(isActive ? 1 : 0.5)
        .disabled(!isActive)
        .sheet(isPresented: $showOnTimePicker) {
            TimePickerSheet(hour: entry.onH, minute: entry.onM, label: "ON Time") { h, m in
                guard isBefore(h, m, entry.offH, entry.offM) else { onTimeRangeError(); return }
                if let day = conflictDay(onH: h, onM: m, offH: entry.offH, offM: entry.offM) {
                    onTimeConflict(day); return
                }
                entry.onH = h; entry.onM = m; onChanged()
            }
        }
        .sheet(isPresented: $showOffTimePicker) {
            TimePickerSheet(hour: entry.offH, minute: entry.offM, label: "OFF Time") { h, m in
                guard isBefore(entry.onH, entry.onM, h, m) else { onTimeRangeError(); return }
                if let day = conflictDay(onH: entry.onH, onM: entry.onM, offH: h, offM: m) {
                    onTimeConflict(day); return
                }
                entry.offH = h; entry.offM = m; onChanged()
            }
        }
    }

    // MARK: - Sub-views

    private func timeButton(label: String, time: String, color: Color, bg: Color, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(spacing: 2) {
                Text(label)
                    .font(.system(size: 9, weight: .bold, design: .monospaced))
                    .tracking(1).foregroundColor(color.opacity(0.7))
                Text(time)
                    .font(.system(size: 15, weight: .bold, design: .monospaced))
                    .foregroundColor(color)
            }
            .padding(.horizontal, 16).padding(.vertical, 8)
            .background(bg)
            .clipShape(RoundedRectangle(cornerRadius: 10))
        }
    }

    private func dayChip(index: Int) -> some View {
        let selected = entry.days[index]
        return Button { toggleDay(index) } label: {
            Text(uiDayLabels[index])
                .font(.system(size: 11, weight: .bold, design: .monospaced))
                .foregroundColor(selected ? .bgDark : .textMuted)
                .frame(width: 32, height: 32)
                .background(RoundedRectangle(cornerRadius: 8).fill(selected ? Color.copper : Color.cardSurface2))
        }
    }

    // MARK: - Day toggle with validation

    private func toggleDay(_ dayIdx: Int) {
        let turningOn = !entry.days[dayIdx]
        if turningOn {
            let count = allEntries.filter { $0.days[dayIdx] }.count
            if count >= 3 { onMaxSlots(uiDayNames[dayIdx]); return }
            if hasOverlap(onH: entry.onH, onM: entry.onM,
                          offH: entry.offH, offM: entry.offM, dayIdx: dayIdx) {
                onTimeConflict(uiDayNames[dayIdx]); return
            }
        }
        entry.days[dayIdx] = turningOn
        onChanged()
    }

    // MARK: - Conflict detection helpers

    private func isBefore(_ h1: Int, _ m1: Int, _ h2: Int, _ m2: Int) -> Bool {
        (h1 * 60 + m1) < (h2 * 60 + m2)
    }

    private func conflictDay(onH: Int, onM: Int, offH: Int, offM: Int) -> String? {
        for d in 0..<7 {
            guard entry.days[d] else { continue }
            if hasOverlap(onH: onH, onM: onM, offH: offH, offM: offM, dayIdx: d) {
                return uiDayNames[d]
            }
        }
        return nil
    }

    private func hasOverlap(onH: Int, onM: Int, offH: Int, offM: Int, dayIdx: Int) -> Bool {
        let tS = onH * 60 + onM, tE = offH * 60 + offM
        let tWraps = tE <= tS
        for other in allEntries {
            guard other !== entry, other.days[dayIdx] else { continue }
            let oS = other.onH * 60 + other.onM, oE = other.offH * 60 + other.offM
            let oWraps = oE <= oS
            if rangesOverlap(tS, tE, tWraps, oS, oE, oWraps) { return true }
        }
        return false
    }

    /// Overlap check that handles midnight-wrapping ranges.
    private func rangesOverlap(_ s1: Int, _ e1: Int, _ w1: Bool,
                               _ s2: Int, _ e2: Int, _ w2: Bool) -> Bool {
        if !w1 && !w2 { return s1 < e2 && s2 < e1 }
        typealias R = (Int, Int)
        let r1: [R] = w1 ? [(s1, 1440), (0, e1)] : [(s1, e1)]
        let r2: [R] = w2 ? [(s2, 1440), (0, e2)] : [(s2, e2)]
        for p1 in r1 { for p2 in r2 { if p1.0 < p2.1 && p2.0 < p1.1 { return true } } }
        return false
    }
}

// MARK: - Time Picker Sheet

struct TimePickerSheet: View {
    let hour: Int; let minute: Int; let label: String
    let onConfirm: (Int, Int) -> Void

    @Environment(\.dismiss) var dismiss
    @State private var selectedDate: Date

    init(hour: Int, minute: Int, label: String, onConfirm: @escaping (Int, Int) -> Void) {
        self.hour = hour; self.minute = minute; self.label = label; self.onConfirm = onConfirm
        var c = DateComponents(); c.hour = hour; c.minute = minute
        _selectedDate = State(initialValue: Calendar.current.date(from: c) ?? Date())
    }

    var body: some View {
        NavigationStack {
            ZStack {
                Color.bgDark.ignoresSafeArea()
                DatePicker("", selection: $selectedDate, displayedComponents: .hourAndMinute)
                    .datePickerStyle(.wheel)
                    .colorScheme(.dark)
                    .labelsHidden()
            }
            .navigationTitle(label)
            .navigationBarTitleDisplayMode(.inline)
            .toolbarBackground(Color.cardSurface, for: .navigationBar)
            .toolbarColorScheme(.dark, for: .navigationBar)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("Cancel") { dismiss() }
                        .foregroundColor(.textMuted)
                        .font(.system(size: 14, design: .monospaced))
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Done") {
                        let cal = Calendar.current
                        onConfirm(cal.component(.hour, from: selectedDate),
                                  cal.component(.minute, from: selectedDate))
                        dismiss()
                    }
                    .foregroundColor(.copper)
                    .font(.system(size: 14, weight: .bold, design: .monospaced))
                }
            }
        }
        .presentationDetents([.medium])
        .presentationBackground(Color.bgDark)
    }
}
