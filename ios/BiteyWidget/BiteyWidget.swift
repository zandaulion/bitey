import AppIntents
import SwiftUI
import WidgetKit

private struct TodayEntry: TimelineEntry {
    let date: Date
    let snapshot: TodaySnapshot
}

private struct TodayProvider: TimelineProvider {
    /// A widget added before Bitey has ever been opened.
    private var blank: TodaySnapshot {
        TodaySnapshot(day: "", kcal: 0, burn: nil, protein: 0, carbs: 0, fat: 0, fiber: 0, mood: nil, text: [:])
    }

    func placeholder(in context: Context) -> TodayEntry {
        TodayEntry(date: Date(), snapshot: TodaySnapshot.load() ?? blank)
    }

    func getSnapshot(in context: Context, completion: @escaping (TodayEntry) -> Void) {
        completion(placeholder(in: context))
    }

    /// Redrawn each half hour, as on Android, which is what catches midnight.
    func getTimeline(in context: Context, completion: @escaping (Timeline<TodayEntry>) -> Void) {
        let next = Date().addingTimeInterval(30 * 60)
        completion(Timeline(entries: [placeholder(in: context)], policy: .after(next)))
    }
}

private struct TodayWidgetEntryView: View {
    @Environment(\.widgetFamily) private var family
    let entry: TodayEntry

    private var size: TodayWidgetSize {
        switch family {
        case .systemSmall: .small
        case .systemLarge: .large
        default: .medium
        }
    }

    var body: some View {
        TodayWidgetView(snapshot: entry.snapshot, size: size)
            .containerBackground(BiteyPalette.ground, for: .widget)
    }
}

struct TodayWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "BiteyToday", provider: TodayProvider()) { entry in
            TodayWidgetEntryView(entry: entry)
        }
        .configurationDisplayName("Today in Bitey")
        .description("Today's total, with buttons for Photo, Barcode and Type.")
        .supportedFamilies([.systemSmall, .systemMedium, .systemLarge])
    }
}

/// The counterpart of Android's Quick Settings tile: one press, ready for a photo.
@available(iOS 18.0, *)
struct MealPhotoControl: ControlWidget {
    var body: some ControlWidgetConfiguration {
        StaticControlConfiguration(kind: "BiteyMealPhoto") {
            ControlWidgetButton(action: OpenURLIntent(URL(string: "bitey://action/photo")!)) {
                // Control Center draws symbols only: Android's tile, as a symbol.
                Label("Meal photo", image: "BiteyHead")
            }
        }
        .displayName("Meal photo")
        .description("Open Bitey ready for a photo.")
    }
}

@main
struct BiteyWidgets: WidgetBundle {
    var body: some Widget {
        TodayWidget()
        if #available(iOS 18.0, *) {
            MealPhotoControl()
        }
    }
}
