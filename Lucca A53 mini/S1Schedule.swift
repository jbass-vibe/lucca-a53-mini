import Foundation
import Combine

// MARK: - S1Schedule
// Wire format: 84 bytes = 7 days × 12 bytes = 7 days × 3 slots × 4 bytes
// Each 4-byte slot: [offMin][offHour][onMin][flags_onHour]
//   flags_onHour: bit7 = enabled, bits6-0 = onHour

struct S1Schedule {

    static let DAYS  = 7
    static let SLOTS = 3

    static let dayNames: [String] = ["Sunday","Monday","Tuesday","Wednesday","Thursday","Friday","Saturday"]
    static let dayShort: [String] = ["Sun","Mon","Tue","Wed","Thu","Fri","Sat"]

    /// [day 0-6][slot 0-2]
    var slots: [[TimeSlot]]

    // BUG 10 FIX: explicit nested-loop init avoids any risk of COW aliasing
    // when inner arrays happen to share the same backing store before mutation.
    init() {
        slots = (0..<S1Schedule.DAYS).map { _ in
            (0..<S1Schedule.SLOTS).map { _ in TimeSlot() }
        }
    }

    // MARK: - Serialize to 84-byte wire format
    func toBytes() -> [UInt8] {
        var out = [UInt8](repeating: 0, count: 84)
        for d in 0..<S1Schedule.DAYS {
            for s in 0..<S1Schedule.SLOTS {
                let ts = slots[d][s]
                guard ts.enabled else { continue }
                let base = d * 12 + s * 4
                out[base]     = UInt8(ts.offMinute & 0x3F)
                out[base + 1] = UInt8(ts.offHour   & 0x1F)
                out[base + 2] = UInt8(ts.onMinute  & 0x3F)
                out[base + 3] = UInt8(0x80 | (ts.onHour & 0x7F))
            }
        }
        return out
    }

    // MARK: - Deserialize from 84-byte wire format
    static func fromBytes(_ raw: [UInt8]) -> S1Schedule {
        var sched = S1Schedule()
        guard raw.count >= 84 else { return sched }
        for d in 0..<DAYS {
            for s in 0..<SLOTS {
                let base = d * 12 + s * 4
                let b0 = Int(raw[base])
                let b1 = Int(raw[base + 1])
                let b2 = Int(raw[base + 2])
                let b3 = Int(raw[base + 3])
                sched.slots[d][s].enabled   = (b3 & 0x80) != 0
                sched.slots[d][s].offMinute = b0 & 0x3F
                sched.slots[d][s].offHour   = b1 & 0x1F
                sched.slots[d][s].onMinute  = b2 & 0x3F
                sched.slots[d][s].onHour    = b3 & 0x7F
            }
        }
        return sched
    }

    // MARK: - TimeSlot
    struct TimeSlot: Equatable {
        var enabled:   Bool = false
        var onHour:    Int  = 7
        var onMinute:  Int  = 0
        var offHour:   Int  = 8
        var offMinute: Int  = 0

        var onTimeString:  String { formatted(hour: onHour,  minute: onMinute) }
        var offTimeString: String { formatted(hour: offHour, minute: offMinute) }

        private func formatted(hour: Int, minute: Int) -> String {
            var c = DateComponents(); c.hour = hour; c.minute = minute
            let date = Calendar.current.date(from: c) ?? Date()
            let f = DateFormatter(); f.timeStyle = .short; f.dateStyle = .none
            return f.string(from: date)
        }
    }
}

// MARK: - UiEntry
// Aggregates slots that share the same ON/OFF time across multiple days into one card.
// Mirrors the Android UiEntry.
class UiEntry: ObservableObject, Identifiable, Equatable {
    let id = UUID()
    @Published var onH:  Int = 7
    @Published var onM:  Int = 0
    @Published var offH: Int = 8
    @Published var offM: Int = 0
    @Published var days: [Bool] = Array(repeating: false, count: 7)  // Mon=0..Sun=6

    init() {}
    init(onH: Int, onM: Int, offH: Int, offM: Int, days: [Bool]) {
        self.onH = onH; self.onM = onM
        self.offH = offH; self.offM = offM
        self.days = days
    }

    var onTimeString:  String { formatTime(onH,  onM)  }
    var offTimeString: String { formatTime(offH, offM) }

    private func formatTime(_ h: Int, _ m: Int) -> String {
        var c = DateComponents(); c.hour = h; c.minute = m
        let date = Calendar.current.date(from: c) ?? Date()
        let f = DateFormatter(); f.timeStyle = .short; f.dateStyle = .none
        return f.string(from: date)
    }

    static func == (lhs: UiEntry, rhs: UiEntry) -> Bool { lhs.id == rhs.id }
}

// MARK: - Schedule Conversion Helpers
// UI day order : Sun=0, Mon=1, Tue=2, Wed=3, Thu=4, Fri=5, Sat=6
// HW day order : Sun=0, Mon=1, Tue=2, Wed=3, Thu=4, Fri=5, Sat=6  (Java Calendar)
// hwToUi[hwDay] -> uiDay index
// uiToHw[uiDay] -> hwDay index

extension S1Schedule {

    static func loadUiEntries(from hw: S1Schedule) -> [UiEntry] {
        var entries: [UiEntry] = []
        var map: [String: UiEntry] = [:]
        let hwToUi = [1, 2, 3, 4, 5, 6, 0]   // Sun(0)->0, Mon(1)->1, … Sat(6)->6

        for hwDay in 0..<DAYS {
            let uiDay = hwToUi[hwDay]
            for s in 0..<SLOTS {
                let ts = hw.slots[hwDay][s]
                guard ts.enabled else { continue }
                let key = String(format: "%02d:%02d-%02d:%02d",
                                 ts.onHour, ts.onMinute, ts.offHour, ts.offMinute)
                if let entry = map[key] {
                    entry.days[uiDay] = true
                } else {
                    let entry = UiEntry(onH: ts.onHour, onM: ts.onMinute,
                                        offH: ts.offHour, offM: ts.offMinute,
                                        days: Array(repeating: false, count: 7))
                    entry.days[uiDay] = true
                    map[key] = entry
                    entries.append(entry)
                }
            }
        }
        return entries
    }

    static func convertUiToHardware(_ entries: [UiEntry]) -> S1Schedule {
        var hw = S1Schedule()
        var slotCounts = [Int](repeating: 0, count: 7)
        let uiToHw = [6, 0, 1, 2, 3, 4, 5]   // Sun(0)->0, Mon(1)->1, … Sat(6)->6

        for e in entries {
            for uiDay in 0..<7 {
                guard e.days[uiDay] else { continue }
                let hwDay = uiToHw[uiDay]
                let slotIdx = slotCounts[hwDay]
                guard slotIdx < SLOTS else { continue }
                hw.slots[hwDay][slotIdx].enabled   = true
                hw.slots[hwDay][slotIdx].onHour    = e.onH
                hw.slots[hwDay][slotIdx].onMinute  = e.onM
                hw.slots[hwDay][slotIdx].offHour   = e.offH
                hw.slots[hwDay][slotIdx].offMinute = e.offM
                slotCounts[hwDay] += 1
            }
        }
        return hw
    }
}
