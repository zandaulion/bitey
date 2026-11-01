import Foundation

/// What the page sends for the widget: figures and words, already translated, so
/// the widget works nothing out and follows the language chosen in Bitey. The same
/// snapshot Android's widget draws. No entries or foods reach it, only totals.
struct TodaySnapshot: Codable {
    static let group = "group.com.zandaulion.bitey"

    var day: String
    var kcal: Int
    var burn: Int?
    var protein: Double
    var carbs: Double
    var fat: Double
    var fiber: Double
    var mood: String?
    var text: [String: String]

    private static var folder: URL? {
        FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: group)
    }

    static var faceFile: URL? { folder?.appendingPathComponent("bitey.png") }

    static func load() -> TodaySnapshot? {
        folder.flatMap { try? Data(contentsOf: $0.appendingPathComponent("today.json")) }
            .flatMap { try? JSONDecoder().decode(TodaySnapshot.self, from: $0) }
    }

    static func save(_ json: Data) {
        guard (try? JSONDecoder().decode(TodaySnapshot.self, from: json)) != nil, let folder else { return }
        try? json.write(to: folder.appendingPathComponent("today.json"), options: .atomic)
    }

    var isToday: Bool {
        let formatter = DateFormatter()
        formatter.calendar = Calendar(identifier: .gregorian)
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "yyyy-MM-dd"
        return formatter.string(from: Date()) == day
    }

    func words(_ key: String, _ fallback: String) -> String {
        text[key] ?? fallback
    }

    /// After midnight the new day starts at nothing, without waiting for Bitey to open.
    var shownKcal: Int { isToday ? kcal : 0 }
    var shownLine: String { isToday ? words("eaten", "\(kcal) kcal") : words("fresh", "0 kcal") }
    var shownAdvice: String { isToday ? words("advice", "") : words("greeting", "") }

    /// Protein, carbs and fat by energy: 4, 4 and 9 kcal per gram.
    var split: (protein: Double, carbs: Double, fat: Double)? {
        guard isToday else { return nil }
        let energy = protein * 4 + carbs * 4 + fat * 9
        guard energy > 0 else { return nil }
        return (protein * 4 / energy, carbs * 4 / energy, fat * 9 / energy)
    }
}
