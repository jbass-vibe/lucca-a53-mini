import SwiftUI

@main
struct LuccaA53MiniApp: App {
    @StateObject private var ble = BLEManager()

    var body: some Scene {
        WindowGroup {
            ScanView()
                .environmentObject(ble)
                .preferredColorScheme(.dark)
        }
    }
}
