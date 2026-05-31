import SwiftUI

struct ScanView: View {
    @EnvironmentObject var ble: BLEManager
    @State private var logLines: [String] = []
    @State private var showSchedule = false
    @State private var pulseAnim = false

    var body: some View {
        NavigationStack {
            ZStack {
                Color.bgDark.ignoresSafeArea()

                VStack(spacing: 0) {
                    // ── Header ──────────────────────────────────────────
                    header

                    // ── Central icon + status ────────────────────────────
                    Spacer()
                    centralStatus
                    Spacer()

                    // ── Buttons ──────────────────────────────────────────
                    actionButtons
                        .padding(.horizontal, 24)

                    // ── Log ──────────────────────────────────────────────
                    if !logLines.isEmpty {
                        logPanel
                            .frame(maxHeight: 140)
                            .padding(.top, 8)
                    }

                    Spacer(minLength: 32)
                }
            }
            .navigationDestination(isPresented: $showSchedule) {
                ScheduleView()
                    .environmentObject(ble)
            }
        }
        .onChange(of: ble.state) { _, newState in
            handleStateChange(newState)
        }
    }

    // MARK: - Sub-views

    private var header: some View {
        VStack(spacing: 2) {
            Text("LUCCA A53 MINI")
                .font(.system(size: 11, weight: .bold, design: .monospaced))
                .tracking(4)
                .foregroundColor(.copper)
                .padding(.top, 20)
            Text("BT Remote")
                .font(.system(size: 11, design: .monospaced))
                .foregroundColor(.textMuted)
        }
        .frame(maxWidth: .infinity)
        .padding(.bottom, 8)
        .overlay(alignment: .bottom) {
            Divider().background(Color.divider)
        }
    }

    private var centralStatus: some View {
        VStack(spacing: 20) {
            // BLE Icon
            ZStack {
                Circle()
                    .stroke(iconColor.opacity(0.15), lineWidth: 1)
                    .frame(width: 120, height: 120)
                    .scaleEffect(pulseAnim ? 1.25 : 1)
                    .opacity(pulseAnim ? 0 : 1)

                Circle()
                    .fill(iconColor.opacity(0.08))
                    .frame(width: 100, height: 100)

                Image(systemName: iconName)
                    .resizable()
                    .scaledToFit()
                    .frame(width: 48, height: 48)
                    .foregroundColor(iconColor)
                    .scaleEffect(pulseAnim ? 1.05 : 1.0)
            }
            .animation(
                shouldPulse
                    ? .easeInOut(duration: 0.9).repeatForever(autoreverses: true)
                    : .default,
                value: pulseAnim
            )

            // Title
            Text(titleText)
                .font(.system(size: 20, weight: .bold, design: .monospaced))
                .foregroundColor(.textPrimary)
                .multilineTextAlignment(.center)

            // Subtitle
            Text(subtitleText)
                .font(.system(size: 12, design: .monospaced))
                .foregroundColor(.textMuted)
                .multilineTextAlignment(.center)
                .lineSpacing(4)
                .padding(.horizontal, 32)

            // Device info (found state)
            if case .deviceFound(let name, let addr) = ble.state {
                VStack(spacing: 3) {
                    Text(name)
                        .font(.system(size: 13, weight: .semibold, design: .monospaced))
                        .foregroundColor(.cream)
                    Text(addr)
                        .font(.system(size: 10, design: .monospaced))
                        .foregroundColor(.textMuted)
                }
                .padding(.horizontal, 16).padding(.vertical, 8)
                .background(Color.cardSurface2)
                .clipShape(RoundedRectangle(cornerRadius: 8))
            }

            // Spinner for scanning / connecting
            if isSpinning {
                ProgressView()
                    .tint(.copper)
                    .scaleEffect(1.1)
            }
        }
        .padding(.horizontal, 24)
    }

    private var actionButtons: some View {
        VStack(spacing: 10) {
            switch ble.state {
            case .idle, .timeout, .disconnected, .btDisabled, .permissionDenied:
                Button(primaryButtonLabel) {
                    addLog("Searching for machines...")
                    ble.startScan()
                }
                .buttonStyle(CopperButtonStyle())

            case .scanning:
                Button("Stop Scanning") {
                    addLog("Scan stopped by user.")
                    ble.stopScan()
                }
                .buttonStyle(CopperButtonStyle(isSecondary: true))

            case .deviceFound, .connecting, .connected:
                Button("Cancel") {
                    addLog("Connection cancelled by user.")
                    ble.disconnect()
                }
                .buttonStyle(CopperButtonStyle(isDestructive: true))

            case .servicesDiscovered:
                EmptyView()

            case .error(let msg):
                Text(msg)
                    .font(.system(size: 11, design: .monospaced))
                    .foregroundColor(.bleRed)
                    .multilineTextAlignment(.center)
                    .padding(.bottom, 4)
                Button("Retry") {
                    addLog("Retrying scan…")
                    ble.startScan()
                }
                .buttonStyle(CopperButtonStyle())
            }
        }
    }

    private var logPanel: some View {
        VStack(alignment: .leading, spacing: 0) {
            Divider().background(Color.divider)
            Text("CONNECTION LOG")
                .font(.system(size: 9, weight: .bold, design: .monospaced))
                .tracking(2)
                .foregroundColor(.textMuted)
                .padding(.horizontal, 16)
                .padding(.top, 8)
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 3) {
                    ForEach(logLines.indices, id: \.self) { i in
                        Text("› \(logLines[i])")
                            .font(.system(size: 11, design: .monospaced))
                            .foregroundColor(Color(hex: "#888888"))
                    }
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 6)
            }
        }
    }

    // MARK: - State helpers

    private var iconName: String {
        switch ble.state {
        case .idle, .scanning:            return "antenna.radiowaves.left.and.right"
        case .deviceFound, .connecting:   return "dot.radiowaves.left.and.right"
        case .connected, .servicesDiscovered: return "checkmark.circle"
        case .timeout:                    return "antenna.radiowaves.left.and.right.slash"
        case .error:                      return "exclamationmark.triangle"
        case .btDisabled:                 return "bluetooth.slash"
        case .permissionDenied:           return "lock.slash"
        case .disconnected:               return "antenna.radiowaves.left.and.right"
        }
    }

    private var iconColor: Color {
        switch ble.state {
        case .idle, .scanning, .disconnected:            return .copper
        case .deviceFound, .connecting:                  return .copper
        case .connected, .servicesDiscovered:            return .bleGreen
        case .timeout, .error, .btDisabled, .permissionDenied: return .bleRed
        }
    }

    private var shouldPulse: Bool {
        switch ble.state {
        case .scanning, .connecting, .deviceFound, .connected: return true
        default: return false
        }
    }

    private var isSpinning: Bool {
        switch ble.state {
        case .scanning, .connecting, .connected, .deviceFound: return true
        default: return false
        }
    }

    private var titleText: String {
        switch ble.state {
        case .idle:               return "Looking for Machine"
        case .scanning:           return "Scanning…"
        case .deviceFound:        return "Device Found"
        case .connecting:         return "Connecting…"
        case .connected:          return "Connected!"
        case .servicesDiscovered: return "Ready"
        case .timeout:            return "No Device Found"
        case .error:              return "Connection Error"
        case .btDisabled:         return "Bluetooth Off"
        case .permissionDenied:   return "Permission Required"
        case .disconnected:       return "Disconnected"
        }
    }

    private var subtitleText: String {
        switch ble.state {
        case .idle:
            return "Make sure your espresso machine is powered on and nearby."
        case .scanning:
            return "Searching for an espresso machine via Bluetooth LE."
        case .deviceFound:
            return "Connecting automatically…"
        case .connecting:
            return "Establishing GATT connection."
        case .connected:
            return "Discovering services…"
        case .servicesDiscovered:
            return "Opening schedule…"
        case .timeout:
            return "Scan timed out after 30 seconds.\nMake sure the machine is on and in range."
        case .error(let msg):
            return msg
        case .btDisabled:
            return "Please enable Bluetooth in Settings, then try again."
        case .permissionDenied:
            return "Bluetooth permission is needed to discover the espresso machine."
        case .disconnected:
            return "Make sure your espresso machine is powered on and nearby."
        }
    }

    private var primaryButtonLabel: String {
        switch ble.state {
        case .timeout:          return "Try Again"
        case .error:            return "Retry"
        case .btDisabled:       return "Retry"
        case .permissionDenied: return "Grant Permission"
        default:                return "Start Scan"
        }
    }

    // MARK: - State change handler

    private func handleStateChange(_ state: S1State) {
        switch state {
        case .scanning:
            pulseAnim = true
            addLog("BLE scan started — scanning for S1 devices")
        case .deviceFound(let name, let addr):
            addLog("Found: \(name) [\(addr)]")
        case .connecting:
            addLog("Connecting to machine...")
        case .connected:
            addLog("Connected!")
        case .servicesDiscovered:
            addLog("Services discovered. Opening schedule…")
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) {
                showSchedule = true
            }
        case .timeout:
            pulseAnim = false
            addLog("Scan timed out (30s)")
        case .error(let msg):
            pulseAnim = false
            addLog("Error: \(msg)")
        case .disconnected:
            pulseAnim = false
        default:
            pulseAnim = false
        }
    }

    private func addLog(_ msg: String) {
        logLines.append(msg)
        if logLines.count > 50 { logLines.removeFirst() }
    }
}
