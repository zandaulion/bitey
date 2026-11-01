import Foundation
import SQLite3

/// App-private diary storage, the counterpart of Android's Room database.
/// Answers are JSON strings of the same shape, so the page cannot tell them
/// apart. Validation rules match PlateWebView.kt.
///
/// Everything is excluded from device backups: Apple does not allow personal
/// health information in iCloud (guideline 5.1.3). Bitey's own backup file is
/// how a diary moves to a new phone.
final class LocalStore {
    static let shared = LocalStore()

    private static let idPattern = "^[A-Za-z0-9_-]{1,80}$"
    private static let dayPattern = "^\\d{4}-\\d{2}-\\d{2}$"
    private static let maxPhotoBytes = 5 * 1024 * 1024

    let database: SQLiteDatabase?
    private let photos: URL

    private init() {
        let fileManager = FileManager.default
        var root = (try? fileManager.url(
            for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true
        )).map { $0.appendingPathComponent("Bitey", isDirectory: true) } ?? fileManager.temporaryDirectory
        photos = root.appendingPathComponent("photos", isDirectory: true)
        try? fileManager.createDirectory(at: photos, withIntermediateDirectories: true)
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try? root.setResourceValues(values)

        database = try? SQLiteDatabase(
            path: root.appendingPathComponent("plate.db").path,
            flags: SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE
        )
        for table in [
            "CREATE TABLE IF NOT EXISTS barcode_foods (barcode TEXT PRIMARY KEY, foodJson TEXT NOT NULL, cachedAt INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS profile (id TEXT PRIMARY KEY, profileJson TEXT NOT NULL, updatedAt INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS weights (day TEXT PRIMARY KEY, kg REAL NOT NULL, at TEXT NOT NULL)",
            "CREATE TABLE IF NOT EXISTS entries (id TEXT PRIMARY KEY, day TEXT NOT NULL, sortKey INTEGER NOT NULL, entryJson TEXT NOT NULL, updatedAt INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS photos (id TEXT PRIMARY KEY, mimeType TEXT NOT NULL, byteCount INTEGER NOT NULL)",
        ] {
            try? database?.execute(table)
        }
    }

    // MARK: Diary

    func diary(operation: String, payload: String) -> String {
        do {
            guard let database else { throw CocoaError(.fileReadUnknown) }
            return try diaryResult(database, operation, payload)
        } catch {
            return Self.failure("storage_error", "The local diary could not be updated.")
        }
    }

    private func diaryResult(_ db: SQLiteDatabase, _ operation: String, _ payload: String) throws -> String {
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        switch operation {
        case "profile.read":
            let saved = try db.rows("SELECT profileJson FROM profile WHERE id = 'profile' LIMIT 1") { SQLiteDatabase.text($0, 0) }
            return Self.success(["profile": Self.parsed(saved.first ?? nil) ?? NSNull()])

        case "profile.write":
            guard payload.count <= 4_000, var profile = Self.parsed(payload) as? [String: Any] else {
                return Self.failure("bad_profile", "The profile could not be saved.")
            }
            profile["id"] = "profile"
            try db.execute("INSERT OR REPLACE INTO profile VALUES ('profile', ?, ?)", [.text(Self.json(profile)), .int(now)])
            return Self.success()

        case "weights.list":
            let rows: [[String: Any]] = try db.rows("SELECT day, kg, at FROM weights ORDER BY day ASC") {
                ["day": SQLiteDatabase.text($0, 0) ?? "", "kg": SQLiteDatabase.double($0, 1) ?? 0, "at": SQLiteDatabase.text($0, 2) ?? ""]
            }
            return Self.success(["weights": rows])

        case "weights.write":
            let weight = Self.parsed(payload) as? [String: Any] ?? [:]
            let day = weight["day"] as? String ?? ""
            let kg = (weight["kg"] as? NSNumber)?.doubleValue ?? .nan
            guard Self.matches(day, Self.dayPattern), (20.0...400.0).contains(kg) else {
                return Self.failure("bad_weight", "That weight looks wrong.")
            }
            let given = weight["at"] as? String ?? ""
            let at = given.isEmpty ? ISO8601DateFormatter().string(from: Date()) : given
            try db.execute("INSERT OR REPLACE INTO weights VALUES (?, ?, ?)", [.text(day), .double(kg), .text(at)])
            return Self.success(["day": day, "kg": kg])

        case "weights.delete":
            let day = (Self.parsed(payload) as? [String: Any])?["day"] as? String ?? ""
            guard Self.matches(day, Self.dayPattern) else {
                return Self.failure("bad_weight", "That weight could not be removed.")
            }
            try db.execute("DELETE FROM weights WHERE day = ?", [.text(day)])
            return Self.success(["deleted": db.changes > 0])

        case "entries.list":
            let rows = try db.rows("SELECT entryJson FROM entries ORDER BY day ASC, sortKey DESC") { SQLiteDatabase.text($0, 0) }
            return Self.success(["entries": rows.compactMap { Self.parsed($0) }])

        case "entries.read":
            let id = (Self.parsed(payload) as? [String: Any])?["id"] as? String ?? ""
            guard Self.matches(id, Self.idPattern) else {
                return Self.failure("bad_entry", "That diary entry could not be read.")
            }
            let saved = try db.rows("SELECT entryJson FROM entries WHERE id = ? LIMIT 1", [.text(id)]) { SQLiteDatabase.text($0, 0) }
            return Self.success(["entry": Self.parsed(saved.first ?? nil) ?? NSNull()])

        case "entries.write":
            var entry = Self.parsed(payload) as? [String: Any] ?? [:]
            let id = entry["id"] as? String ?? ""
            let day = entry["day"] as? String ?? ""
            let sortKey = (entry["sortKey"] as? NSNumber)?.int64Value ?? -1
            guard Self.matches(id, Self.idPattern), Self.matches(day, Self.dayPattern), sortKey >= 0 else {
                return Self.failure("bad_entry", "That diary entry could not be saved.")
            }
            entry["photoData"] = nil
            let text = Self.json(entry)
            guard text.count <= 64_000 else {
                return Self.failure("bad_entry", "That diary entry is too large to save.")
            }
            try db.execute(
                "INSERT OR REPLACE INTO entries VALUES (?, ?, ?, ?, ?)",
                [.text(id), .text(day), .int(sortKey), .text(text), .int(now)]
            )
            return Self.success(["entry": entry])

        case "entries.delete":
            let id = (Self.parsed(payload) as? [String: Any])?["id"] as? String ?? ""
            guard Self.matches(id, Self.idPattern) else {
                return Self.failure("bad_entry", "That diary entry could not be removed.")
            }
            try db.execute("DELETE FROM entries WHERE id = ?", [.text(id)])
            return Self.success(["deleted": db.changes > 0])

        default:
            return Self.failure("not_implemented", "That local diary operation is not available.")
        }
    }

    // MARK: Photos

    func photo(id: String) -> (data: Data, mimeType: String)? {
        guard Self.matches(id, Self.idPattern), let database,
              let rows = try? database.rows("SELECT mimeType FROM photos WHERE id = ?", [.text(id)], map: { SQLiteDatabase.text($0, 0) }),
              let mimeType = rows.first ?? nil,
              let data = try? Data(contentsOf: photos.appendingPathComponent(id)) else { return nil }
        return (data, mimeType)
    }

    func photoOperation(_ operation: String, id: String, payload: String, mimeType: String) -> String {
        guard Self.matches(id, Self.idPattern), let database else {
            return Self.failure("bad_photo", "That photo could not be saved.")
        }
        do {
            switch operation {
            case "write":
                guard payload.count <= 7_000_000,
                      let data = Data(base64Encoded: payload, options: .ignoreUnknownCharacters) else {
                    return Self.failure("bad_photo", "That photo is too large to save.")
                }
                try save(database, id: id, data: data, mimeType: mimeType)
            case "copy":
                guard let source = photo(id: payload) else {
                    return Self.failure("bad_photo", "That photo could not be copied.")
                }
                try save(database, id: id, data: source.data, mimeType: source.mimeType)
            case "delete":
                try database.execute("DELETE FROM photos WHERE id = ?", [.text(id)])
                try? FileManager.default.removeItem(at: photos.appendingPathComponent(id))
                return Self.success()
            default:
                return Self.failure("not_implemented", "That photo operation is not available.")
            }
            return Self.success(["id": id])
        } catch {
            return Self.failure("photo_error", "The local photo could not be saved.")
        }
    }

    private func save(_ db: SQLiteDatabase, id: String, data: Data, mimeType: String) throws {
        guard (1...Self.maxPhotoBytes).contains(data.count), ["image/jpeg", "image/png"].contains(mimeType) else {
            throw CocoaError(.fileWriteUnknown)
        }
        try data.write(to: photos.appendingPathComponent(id), options: .atomic)
        try db.execute("INSERT OR REPLACE INTO photos VALUES (?, ?, ?)", [.text(id), .text(mimeType), .int(Int64(data.count))])
    }

    func writePhotoFile(id: String, data: Data) throws {
        try data.write(to: photos.appendingPathComponent(id), options: .atomic)
    }

    func prunePhotoFiles(keeping ids: Set<String>) {
        let files = (try? FileManager.default.contentsOfDirectory(at: photos, includingPropertiesForKeys: nil)) ?? []
        for file in files where !ids.contains(file.lastPathComponent) {
            try? FileManager.default.removeItem(at: file)
        }
    }

    // MARK: Barcode cache

    func readCachedFood(barcode: String) -> String {
        guard Self.matches(barcode, "^[0-9]{8,14}$") else {
            return Self.failure("invalid_barcode", "This does not look like a food barcode.")
        }
        guard let rows = try? database?.rows("SELECT foodJson FROM barcode_foods WHERE barcode = ? LIMIT 1", [.text(barcode)], map: { SQLiteDatabase.text($0, 0) }) else {
            return Self.failure("cache_error", "The local barcode cache could not be opened.")
        }
        return Self.success(["foodJson": (rows.first ?? nil) ?? NSNull()])
    }

    func writeCachedFood(barcode: String, foodJson: String) -> String {
        guard Self.matches(barcode, "^[0-9]{8,14}$"), foodJson.count <= 12_000, Self.parsed(foodJson) is [String: Any] else {
            return Self.failure("bad_food", "That barcode result could not be saved locally.")
        }
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        guard (try? database?.execute("INSERT OR REPLACE INTO barcode_foods VALUES (?, ?, ?)", [.text(barcode), .text(foodJson), .int(now)])) != nil else {
            return Self.failure("cache_error", "The local barcode cache could not be opened.")
        }
        return Self.success()
    }

    // MARK: JSON

    private static func matches(_ text: String, _ pattern: String) -> Bool {
        text.range(of: pattern, options: .regularExpression) != nil
    }

    private static func parsed(_ text: String?) -> Any? {
        text?.data(using: .utf8).flatMap { try? JSONSerialization.jsonObject(with: $0) }
    }

    private static func json(_ value: Any) -> String {
        let data = try? JSONSerialization.data(withJSONObject: value)
        return data.flatMap { String(data: $0, encoding: .utf8) } ?? "{}"
    }

    private static func success(_ fields: [String: Any] = [:]) -> String {
        json(fields.merging(["ok": true]) { _, new in new })
    }

    private static func failure(_ code: String, _ message: String) -> String {
        json(["ok": false, "code": code, "message": message])
    }
}
