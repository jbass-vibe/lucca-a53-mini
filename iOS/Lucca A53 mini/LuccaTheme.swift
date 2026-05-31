import SwiftUI

// MARK: - Lucca A53 Dark Theme
// Exact port of Android colors.xml

extension Color {
    // Base palette
    static let bgDark        = Color(hex: "#0E0E0F")
    static let cardSurface   = Color(hex: "#1A1A1C")
    static let cardSurface2  = Color(hex: "#222225")
    static let divider       = Color(hex: "#2A2A2E")

    // Brand / accent
    static let copper        = Color(hex: "#D4813A")
    static let copperLight   = Color(hex: "#EDAC6B")
    static let copperDark    = Color(hex: "#9E5E20")
    static let cream         = Color(hex: "#F4EFE6")

    // Text
    static let textPrimary   = Color(hex: "#F4EFE6")
    static let textMuted     = Color(hex: "#666666")
    static let textDisabled  = Color(hex: "#444444")

    // Slot buttons
    static let onBtnBg       = Color(hex: "#1E2B1A")
    static let onLabel       = Color(hex: "#6AB04C")
    static let offBtnBg      = Color(hex: "#2B1A1A")
    static let offLabel      = Color(hex: "#E05C5C")

    // Status
    static let bleGreen      = Color(hex: "#4CAF50")
    static let bleRed        = Color(hex: "#E53935")

    // Helper init
    init(hex: String) {
        let hex = hex.trimmingCharacters(in: CharacterSet.alphanumerics.inverted)
        var int: UInt64 = 0
        Scanner(string: hex).scanHexInt64(&int)
        let a, r, g, b: UInt64
        switch hex.count {
        case 6: (a, r, g, b) = (255, int >> 16, int >> 8 & 0xFF, int & 0xFF)
        case 8: (a, r, g, b) = (int >> 24, int >> 16 & 0xFF, int >> 8 & 0xFF, int & 0xFF)
        default:(a, r, g, b) = (255, 0, 0, 0)
        }
        self.init(.sRGB,
                  red:   Double(r) / 255,
                  green: Double(g) / 255,
                  blue:  Double(b) / 255,
                  opacity: Double(a) / 255)
    }
}

// MARK: - Button Styles

struct CopperButtonStyle: ButtonStyle {
    var isDestructive = false
    var isSecondary   = false

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.system(size: 14, weight: .semibold, design: .monospaced))
            .foregroundColor(isDestructive ? .bleRed : (isSecondary ? .textMuted : .bgDark))
            .padding(.vertical, 12)
            .padding(.horizontal, 16)
            .frame(maxWidth: .infinity)
            .background(
                RoundedRectangle(cornerRadius: 12)
                    .fill(isDestructive ? Color.bleRed.opacity(0.15)
                          : (isSecondary ? Color.cardSurface2
                             : Color.copper))
                    .opacity(configuration.isPressed ? 0.7 : 1)
            )
    }
}

struct CopperPillButtonStyle: ButtonStyle {
    var active: Bool = true
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.system(size: 12, weight: .bold, design: .monospaced))
            .foregroundColor(active ? .white : .textMuted)
            .padding(.horizontal, 10)
            .padding(.vertical, 6)
            .background(
                RoundedRectangle(cornerRadius: 8)
                    .fill(active ? Color.copper : Color.cardSurface2)
            )
            .opacity(configuration.isPressed ? 0.7 : 1)
    }
}

// MARK: - Card Modifier

struct LuccaCard: ViewModifier {
    func body(content: Content) -> some View {
        content
            .background(Color.cardSurface)
            .clipShape(RoundedRectangle(cornerRadius: 16))
            .overlay(
                RoundedRectangle(cornerRadius: 16)
                    .stroke(Color.divider, lineWidth: 1)
            )
    }
}

extension View {
    func luccaCard() -> some View { modifier(LuccaCard()) }
}

// MARK: - Day chip labels (Mon=0..Sun=6 UI order)
let uiDayLabels = ["M","T","W","T","F","S","S"]
let uiDayNames  = ["Mon","Tue","Wed","Thu","Fri","Sat","Sun"]
