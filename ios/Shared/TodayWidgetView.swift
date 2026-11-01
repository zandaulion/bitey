import SwiftUI
import UIKit

enum TodayWidgetSize {
    case small, medium, large
}

private extension Color {
    init(light: UInt32, dark: UInt32) {
        self.init(uiColor: UIColor { traits in
            let value = traits.userInterfaceStyle == .dark ? dark : light
            return UIColor(
                red: CGFloat((value >> 16) & 0xFF) / 255,
                green: CGFloat((value >> 8) & 0xFF) / 255,
                blue: CGFloat(value & 0xFF) / 255,
                alpha: 1
            )
        })
    }
}

/// web/app.css's palette, so the widget reads as part of Bitey.
enum BiteyPalette {
    static let ground = Color(light: 0xFAF6EF, dark: 0x121713)
    static let surface = Color(light: 0xFFFFFF, dark: 0x1B221C)
    static let ink = Color(light: 0x1C241D, dark: 0xF2F7F2)
    static let ink2 = Color(light: 0x4A574C, dark: 0xB0BEB2)
    static let track = Color(light: 0xEFE8DC, dark: 0x243529)
    static let accent = Color(light: 0x287A4D, dark: 0x48BB78)
    static let protein = Color(light: 0x10B981, dark: 0x34D399)
    static let carbs = Color(light: 0xF59E0B, dark: 0xFBBF24)
    static let fat = Color(light: 0x8B5CF6, dark: 0xA78BFA)
    static let fiber = Color(light: 0x06B6D4, dark: 0x22D3EE)
}

/// Shared with the app, so a debug build can draw it for checking.
struct TodayWidgetView: View {
    let snapshot: TodaySnapshot
    let size: TodayWidgetSize

    private func action(_ name: String) -> URL {
        URL(string: "bitey://action/\(name)")!
    }

    var body: some View {
        switch size {
        case .small: small
        case .medium: medium
        case .large: large
        }
    }

    private var small: some View {
        VStack(alignment: .leading, spacing: 4) {
            header
            Spacer(minLength: 0)
            total
            macroBar
        }
        .widgetURL(action("photo"))
    }

    private var medium: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(alignment: .top) {
                VStack(alignment: .leading, spacing: 2) {
                    header
                    total
                }
                Spacer(minLength: 0)
                face.frame(width: 62, height: 48)
            }
            macroBar
            Spacer(minLength: 0)
            buttons
        }
    }

    private var large: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .top) {
                VStack(alignment: .leading, spacing: 2) {
                    header
                    total
                }
                Spacer(minLength: 0)
                face.frame(width: 86, height: 66)
            }
            macroBar
            legend
            Text(snapshot.shownAdvice)
                .font(.subheadline)
                .foregroundStyle(BiteyPalette.ink)
                .lineLimit(5)
                .minimumScaleFactor(0.8)
            Spacer(minLength: 0)
            buttons
        }
    }

    private var header: some View {
        Text(snapshot.words("today", "Today"))
            .font(.subheadline.weight(.semibold))
            .foregroundStyle(BiteyPalette.accent)
    }

    private var total: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text("\(snapshot.shownKcal)")
                .font(.system(size: size == .small ? 38 : 32, weight: .heavy, design: .rounded))
                .foregroundStyle(BiteyPalette.ink)
                .minimumScaleFactor(0.6)
            Text(snapshot.shownLine)
                .font(.caption)
                .foregroundStyle(BiteyPalette.ink2)
                .lineLimit(1)
                .minimumScaleFactor(0.7)
        }
    }

    private var macroBar: some View {
        GeometryReader { space in
            HStack(spacing: 0) {
                if let split = snapshot.split {
                    BiteyPalette.protein.frame(width: space.size.width * split.protein)
                    BiteyPalette.carbs.frame(width: space.size.width * split.carbs)
                    BiteyPalette.fat
                } else {
                    BiteyPalette.track
                }
            }
            .clipShape(Capsule())
        }
        .frame(height: 8)
    }

    private var legend: some View {
        HStack {
            legendItem("protein", "Protein", snapshot.protein, BiteyPalette.protein)
            legendItem("carbs", "Carbs", snapshot.carbs, BiteyPalette.carbs)
            legendItem("fat", "Fat", snapshot.fat, BiteyPalette.fat)
            legendItem("fiber", "Fiber", snapshot.fiber, BiteyPalette.fiber)
        }
    }

    private func legendItem(_ key: String, _ fallback: String, _ grams: Double, _ colour: Color) -> some View {
        VStack(alignment: .leading, spacing: 1) {
            Text(snapshot.words(key, fallback))
                .font(.caption2.weight(.semibold))
                .foregroundStyle(colour)
                .lineLimit(1)
                .minimumScaleFactor(0.7)
            Text("\(snapshot.isToday ? Int(grams.rounded()) : 0) g")
                .font(.footnote.weight(.bold))
                .foregroundStyle(BiteyPalette.ink)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var buttons: some View {
        HStack(spacing: 6) {
            button("photo", "camera.fill", snapshot.words("photo", "Photo"), primary: true)
            button("barcode", "barcode.viewfinder", snapshot.words("barcode", "Barcode"), primary: false)
            button("manual", "pencil", snapshot.words("manual", "Type"), primary: false)
        }
    }

    private func button(_ name: String, _ icon: String, _ title: String, primary: Bool) -> some View {
        Link(destination: action(name)) {
            HStack(spacing: 4) {
                Image(systemName: icon).font(.caption2.weight(.bold))
                Text(title).font(.caption.weight(.semibold)).lineLimit(1).minimumScaleFactor(0.7)
            }
            .frame(maxWidth: .infinity, minHeight: 32)
            .foregroundStyle(primary ? Color.white : BiteyPalette.ink)
            .background(primary ? BiteyPalette.accent : BiteyPalette.surface, in: RoundedRectangle(cornerRadius: 11))
        }
    }

    @ViewBuilder
    private var face: some View {
        if let file = TodaySnapshot.faceFile, let image = UIImage(contentsOfFile: file.path) {
            Image(uiImage: image).resizable().scaledToFit()
        }
    }
}
