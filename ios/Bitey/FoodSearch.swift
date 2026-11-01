import Foundation
import SQLite3

/// Searches the food table packaged with the app, on the device. The query and
/// the shape of its answer match GenericFoodSearch.kt; the page does the
/// ranking. Bundled files are read-only here, so unlike Android there is no
/// need to copy the table first.
final class FoodSearch {
    private lazy var database: SQLiteDatabase? = {
        guard let url = Bundle.main.url(forResource: "foods", withExtension: "sqlite", subdirectory: "www/database"),
              var components = URLComponents(url: url, resolvingAgainstBaseURL: false) else { return nil }
        components.queryItems = [URLQueryItem(name: "immutable", value: "1")]
        guard let uri = components.url?.absoluteString else { return nil }
        return try? SQLiteDatabase(path: uri, flags: SQLITE_OPEN_READONLY | SQLITE_OPEN_URI)
    }()

    /// core/foods.js foldText, exactly: the table's search column was built with
    /// it, so a query folded any other way would miss.
    static func fold(_ text: String) -> String {
        text.decomposedStringWithCanonicalMapping
            .replacingOccurrences(of: "\\p{M}+", with: "", options: .regularExpression)
            .lowercased()
            .replacingOccurrences(of: "œ", with: "oe")
            .replacingOccurrences(of: "æ", with: "ae")
            .replacingOccurrences(of: "ß", with: "ss")
    }

    func search(query rawQuery: String, lang rawLang: String) -> String {
        let query = String(rawQuery.trimmingCharacters(in: .whitespacesAndNewlines).prefix(80))
        let tokens = Self.fold(query)
            .components(separatedBy: CharacterSet.alphanumerics.inverted)
            .filter { !$0.isEmpty }
            .prefix(6)
        guard query.count >= 2, !tokens.isEmpty else {
            return Self.json(["ok": false, "code": "short_query", "message": "Type at least two characters."])
        }
        let lower = String(rawLang.lowercased().prefix(2))
        let lang = lower.range(of: "^[a-z]{2}$", options: .regularExpression) == nil ? "en" : lower

        let conditions = tokens.map { _ in "(n.search LIKE ? OR n.search LIKE ?)" }.joined(separator: " AND ")
        var values: [SQLiteDatabase.Value] = [.text(lang)]
        for token in tokens {
            values.append(.text("\(token)%"))
            values.append(.text("% \(token)%"))
        }
        values.append(.text(tokens.joined(separator: " ") + "%"))

        let sql = """
        SELECT f.id, f.kcal, f.protein, f.fat, f.carbs, f.fiber, f.serving_g, f.source,
               (SELECT name FROM names WHERE food_id = f.id AND lang = ? AND rank < 2 ORDER BY rank LIMIT 1),
               (SELECT name FROM names WHERE food_id = f.id AND lang = 'en' AND rank < 2 ORDER BY rank LIMIT 1),
               n.name AS matched
        FROM names n JOIN foods f ON f.id = n.food_id
        WHERE \(conditions)
        GROUP BY f.id
        ORDER BY MAX(n.search LIKE ?) DESC, MIN(length(n.name))
        LIMIT 300
        """

        guard let database, let rows = try? database.rows(sql, values, map: Self.food) else {
            return Self.json(["ok": false, "code": "search_error", "message": "The on-device food table could not be opened."])
        }
        return Self.json(["ok": true, "results": rows])
    }

    private static func food(_ row: OpaquePointer) -> [String: Any] {
        let source = SQLiteDatabase.text(row, 7) ?? ""
        let matched = SQLiteDatabase.text(row, 10) ?? ""
        var per100: [String: Any] = [
            "calories": SQLiteDatabase.double(row, 1) ?? 0,
            "protein": SQLiteDatabase.double(row, 2) ?? 0,
            "fat": SQLiteDatabase.double(row, 3) ?? 0,
            "carbs": SQLiteDatabase.double(row, 4) ?? 0,
        ]
        if let fiber = SQLiteDatabase.double(row, 5) { per100["fiber"] = fiber }
        return [
            "id": "\(source):\(SQLiteDatabase.int(row, 0))",
            "source": source,
            "barcode": NSNull(),
            "name": SQLiteDatabase.text(row, 8) ?? SQLiteDatabase.text(row, 9) ?? matched,
            "matched": matched,
            "per100": per100,
            "servingG": SQLiteDatabase.double(row, 6) ?? NSNull(),
        ]
    }

    private static func json(_ value: [String: Any]) -> String {
        let data = try? JSONSerialization.data(withJSONObject: value)
        return data.flatMap { String(data: $0, encoding: .utf8) } ?? "{}"
    }
}
