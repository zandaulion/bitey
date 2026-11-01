import Foundation

/// Backups in the same `plate.json` ZIP that Android and the PWA use, so a diary can
/// move between phones in either direction. Unencrypted, like Android's, and the
/// interface says that it is sensitive. Everything is validated before anything
/// is replaced: a bad file must never cost someone their diary.
final class LocalBackup {
    struct Problem: Error {
        let message: String
        init(_ message: String) { self.message = message }
    }

    private static let maxJsonBytes = 2 * 1024 * 1024
    private static let maxPhotoBytes = 5 * 1024 * 1024
    private static let maxTotalPhotoBytes = 250 * 1024 * 1024
    private static let idPattern = "^[A-Za-z0-9_-]{1,80}$"
    private static let dayPattern = "^\\d{4}-\\d{2}-\\d{2}$"

    private let store: LocalStore

    init(store: LocalStore) {
        self.store = store
    }

    // MARK: Export

    /// Writes to a temporary file, for the user to save with the document picker.
    func export() throws -> (url: URL, summary: [String: Any]) {
        guard let db = store.database else { throw Problem("The backup could not be written.") }
        let photoRows = try db.rows("SELECT id, mimeType FROM photos") {
            (SQLiteDatabase.text($0, 0) ?? "", SQLiteDatabase.text($0, 1) ?? "")
        }
        var names: [String: String] = [:]
        for (id, mimeType) in photoRows {
            names[id] = "\(id).\(mimeType == "image/png" ? "png" : "jpg")"
        }

        let stored = try db.rows("SELECT entryJson FROM entries ORDER BY day ASC, sortKey DESC") { SQLiteDatabase.text($0, 0) }
        let entries: [[String: Any]] = try stored.compactMap { text in
            guard let source = Self.parsed(text) as? [String: Any], let id = source["id"] as? String else { return nil }
            var photo: Any = NSNull()
            if let photoId = Self.text(source["photoId"]) {
                guard let name = names[photoId] else { throw Problem("A diary photo is missing from this device.") }
                photo = name
            }
            return [
                "id": id,
                "day": source["day"] ?? "",
                "meal": source["meal"] ?? NSNull(),
                "loggedAt": Self.text(source["createdAt"]) ?? NSNull(),
                "portionSource": Self.text(source["portionSource"]) ?? NSNull(),
                "note": source["note"] ?? NSNull(),
                "photo": photo,
                "items": source["items"] ?? [],
                "totals": source["totals"] ?? [:],
            ]
        }

        let profile = try db.rows("SELECT profileJson FROM profile WHERE id = 'profile' LIMIT 1") { SQLiteDatabase.text($0, 0) }
        let weights: [[String: Any]] = try db.rows("SELECT day, kg, at FROM weights ORDER BY day ASC") {
            ["day": SQLiteDatabase.text($0, 0) ?? "", "kg": SQLiteDatabase.double($0, 1) ?? 0, "measuredAt": SQLiteDatabase.text($0, 2) ?? ""]
        }
        let foods: [[String: Any]] = try db.rows("SELECT barcode, foodJson, cachedAt FROM barcode_foods ORDER BY barcode ASC") {
            ["barcode": SQLiteDatabase.text($0, 0) ?? "", "foodJson": SQLiteDatabase.text($0, 1) ?? "", "cachedAt": SQLiteDatabase.int($0, 2)]
        }
        let manifest: [String: Any] = [
            "exportVersion": 1,
            "exportedAt": ISO8601DateFormatter().string(from: Date()),
            "profile": Self.parsed(profile.first ?? nil) ?? NSNull(),
            "weights": weights,
            "entryCount": entries.count,
            "entries": entries,
            "barcodeFoods": foods,
            "photos": photoRows.compactMap { names[$0.0] },
        ]

        let stamp = Int(Date().timeIntervalSince1970 * 1000)
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("plate-backup-\(stamp).zip")
        let zip = try Zip.Writer(url: url)
        try zip.add("plate.json", JSONSerialization.data(withJSONObject: manifest, options: [.prettyPrinted, .sortedKeys]))
        for (id, _) in photoRows {
            guard let photo = store.photo(id: id), let name = names[id] else {
                throw Problem("A diary photo is missing from this device.")
            }
            try zip.add("photos/\(name)", photo.data)
        }
        try zip.finish()
        return (url, ["entries": entries.count, "photos": photoRows.count])
    }

    // MARK: Import

    func importFrom(url: URL) throws -> [String: Any] {
        let fileManager = FileManager.default
        let staging = fileManager.temporaryDirectory.appendingPathComponent("plate-import-\(UUID().uuidString)", isDirectory: true)
        try fileManager.createDirectory(at: staging, withIntermediateDirectories: true)
        defer { try? fileManager.removeItem(at: staging) }

        let zip: Zip.Reader
        do { zip = try Zip.Reader(url: url) } catch { throw Problem("This is not a Plate backup.") }

        var portable: Data?
        var legacy: Data?
        var staged: [String: URL] = [:]
        var totalPhotoBytes = 0
        for entry in zip.entries where !entry.name.hasSuffix("/") {
            switch entry.name {
            case "plate-backup.json": legacy = try read(zip, entry, limit: Self.maxJsonBytes)
            case "plate.json": portable = try read(zip, entry, limit: Self.maxJsonBytes)
            case "plate.csv", "plate-weights.csv", "README.txt": continue
            default:
                guard entry.name.range(of: "^photos/[A-Za-z0-9_.-]{1,120}$", options: .regularExpression) != nil else {
                    throw Problem("The backup contains an unsupported file.")
                }
                let name = String(entry.name.dropFirst("photos/".count))
                guard staged[name] == nil else { throw Problem("The backup contains a duplicate photo.") }
                let bytes = try read(zip, entry, limit: Self.maxPhotoBytes)
                totalPhotoBytes += bytes.count
                guard totalPhotoBytes <= Self.maxTotalPhotoBytes else { throw Problem("The backup is too large.") }
                // Never a path taken from the archive.
                let target = staging.appendingPathComponent(UUID().uuidString)
                try bytes.write(to: target)
                staged[name] = target
            }
        }

        let manifest: [String: Any]
        if let legacy, let parsed = Self.parsedData(legacy) as? [String: Any] {
            manifest = parsed
        } else if let portable, let parsed = Self.parsedData(portable) as? [String: Any] {
            manifest = try Self.portableAsLocal(parsed, staged: &staged)
        } else {
            throw Problem("This is not a Plate backup.")
        }
        guard manifest["format"] as? String == "plate-android-backup", (manifest["version"] as? NSNumber)?.intValue == 1 else {
            throw Problem("This backup is not compatible with this version of Plate.")
        }
        return try restore(manifest, staged: staged)
    }

    private func read(_ zip: Zip.Reader, _ entry: Zip.Entry, limit: Int) throws -> Data {
        do { return try zip.read(entry, limit: limit) } catch { throw Problem("The backup is too large or damaged.") }
    }

    private func restore(_ manifest: [String: Any], staged: [String: URL]) throws -> [String: Any] {
        guard let db = store.database else { throw Problem("The backup could not be restored.") }
        let weights = manifest["weights"] as? [[String: Any]] ?? []
        let entries = manifest["entries"] as? [[String: Any]] ?? []
        let foods = manifest["barcodeFoods"] as? [[String: Any]] ?? []
        let photoRows = manifest["photos"] as? [[String: Any]] ?? []

        var photos: [String: (mimeType: String, data: Data)] = [:]
        for photo in photoRows {
            guard let id = photo["id"] as? String, Self.matches(id, Self.idPattern), photo["path"] as? String == "photos/\(id)",
                  let mimeType = photo["mimeType"] as? String, ["image/jpeg", "image/png"].contains(mimeType),
                  let file = staged[id], let data = try? Data(contentsOf: file),
                  (1...Self.maxPhotoBytes).contains(data.count),
                  (photo["byteCount"] as? NSNumber)?.intValue == data.count else {
                throw Problem("A photo in the backup is incomplete.")
            }
            guard photos[id] == nil else { throw Problem("The backup contains a duplicate photo.") }
            photos[id] = (mimeType, data)
        }
        for entry in entries {
            guard let id = entry["id"] as? String, Self.matches(id, Self.idPattern),
                  let day = entry["day"] as? String, Self.matches(day, Self.dayPattern),
                  (entry["sortKey"] as? NSNumber) != nil else {
                throw Problem("The backup has an invalid diary entry.")
            }
            if let photoId = Self.text(entry["photoId"]), photos[photoId] == nil {
                throw Problem("A diary photo is missing from the backup.")
            }
        }

        // Files first, so no diary row can point at a photo that failed to restore.
        for (id, photo) in photos { try store.writePhotoFile(id: id, data: photo.data) }

        let now = Int64(Date().timeIntervalSince1970 * 1000)
        try db.execute("BEGIN IMMEDIATE")
        do {
            for table in ["profile", "weights", "entries", "barcode_foods", "photos"] {
                try db.execute("DELETE FROM \(table)")
            }
            if var profile = manifest["profile"] as? [String: Any] {
                profile["id"] = "profile"
                try db.execute("INSERT INTO profile VALUES ('profile', ?, ?)", [.text(Self.json(profile)), .int(now)])
            }
            for weight in weights {
                guard let day = weight["day"] as? String, let kg = (weight["kg"] as? NSNumber)?.doubleValue else {
                    throw Problem("The backup has an invalid weigh-in.")
                }
                try db.execute("INSERT OR REPLACE INTO weights VALUES (?, ?, ?)", [.text(day), .double(kg), .text(weight["at"] as? String ?? "")])
            }
            for var entry in entries {
                entry["photoData"] = nil
                try db.execute("INSERT OR REPLACE INTO entries VALUES (?, ?, ?, ?, ?)", [
                    .text(entry["id"] as? String ?? ""), .text(entry["day"] as? String ?? ""),
                    .int((entry["sortKey"] as? NSNumber)?.int64Value ?? 0), .text(Self.json(entry)), .int(now),
                ])
            }
            for food in foods {
                guard let barcode = food["barcode"] as? String, let foodJson = food["foodJson"] as? String else {
                    throw Problem("The backup has an invalid cached food.")
                }
                try db.execute("INSERT OR REPLACE INTO barcode_foods VALUES (?, ?, ?)", [
                    .text(barcode), .text(foodJson), .int((food["cachedAt"] as? NSNumber)?.int64Value ?? now),
                ])
            }
            for (id, photo) in photos {
                try db.execute("INSERT OR REPLACE INTO photos VALUES (?, ?, ?)", [.text(id), .text(photo.mimeType), .int(Int64(photo.data.count))])
            }
            try db.execute("COMMIT")
        } catch {
            try? db.execute("ROLLBACK")
            throw error
        }
        store.prunePhotoFiles(keeping: Set(photos.keys))
        return ["entries": entries.count, "photos": photos.count]
    }

    /// `plate.json` to the local shape, as `pwaExportAsLocal` does on Android.
    private static func portableAsLocal(_ portable: [String: Any], staged: inout [String: URL]) throws -> [String: Any] {
        guard (portable["exportVersion"] as? NSNumber)?.intValue == 1 else {
            throw Problem("This backup is not compatible with this version of Plate.")
        }
        var entries: [[String: Any]] = []
        var photos: [[String: Any]] = []
        var localIds: [String: String] = [:]
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        let plainFormatter = ISO8601DateFormatter()

        for (index, source) in (portable["entries"] as? [[String: Any]] ?? []).enumerated() {
            guard let id = source["id"] as? String, matches(id, idPattern) else {
                throw Problem("The backup has an invalid diary entry.")
            }
            let original = text(source["photo"])
            var localId: String?
            if let original {
                if let known = localIds[original] {
                    localId = known
                } else {
                    guard let file = staged[original] else { throw Problem("A diary photo is missing from the backup.") }
                    let created = UUID().uuidString.lowercased()
                    staged[created] = file
                    localIds[original] = created
                    localId = created
                    let size = (try? file.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
                    photos.append([
                        "id": created,
                        "mimeType": original.lowercased().hasSuffix(".png") ? "image/png" : "image/jpeg",
                        "byteCount": size,
                        "path": "photos/\(created)",
                    ])
                }
            }
            let loggedAt = text(source["loggedAt"]) ?? ISO8601DateFormatter().string(from: Date())
            let date = formatter.date(from: loggedAt) ?? plainFormatter.date(from: loggedAt) ?? Date()
            var entry = source
            entry["loggedAt"] = nil
            entry["photo"] = nil
            entry["createdAt"] = loggedAt
            entry["sortKey"] = Int64(date.timeIntervalSince1970 * 1000) * 1000 + Int64(index)
            entry["photoId"] = localId ?? NSNull()
            entry["photoMimeType"] = original?.lowercased().hasSuffix(".png") == true ? "image/png" : "image/jpeg"
            entry["corrections"] = 0
            entries.append(entry)
        }

        var weights: [[String: Any]] = []
        for source in portable["weights"] as? [[String: Any]] ?? [] {
            guard let day = source["day"] as? String, matches(day, dayPattern),
                  let kg = (source["kg"] as? NSNumber)?.doubleValue, (20.0...400.0).contains(kg) else {
                throw Problem("The backup has an invalid weigh-in.")
            }
            let at = text(source["at"]) ?? text(source["measuredAt"]) ?? "\(day)T12:00:00.000Z"
            weights.append(["day": day, "kg": kg, "at": at])
        }

        var foods: [[String: Any]] = []
        for source in portable["barcodeFoods"] as? [[String: Any]] ?? [] {
            let barcode = (source["barcode"] as? String ?? "").filter(\.isNumber)
            guard (6...14).contains(barcode.count), let foodJson = text(source["foodJson"]) else {
                throw Problem("The backup has an invalid cached food.")
            }
            foods.append(["barcode": barcode, "foodJson": foodJson, "cachedAt": source["cachedAt"] ?? 0])
        }

        var profile: Any = NSNull()
        if var source = portable["profile"] as? [String: Any] {
            // Old exports can carry ageYears without birthYear.
            if source["birthYear"] == nil, let age = (source["ageYears"] as? NSNumber)?.intValue, (13...120).contains(age) {
                source["birthYear"] = Calendar.current.component(.year, from: Date()) - age
            }
            profile = source
        }

        return [
            "format": "plate-android-backup", "version": 1, "profile": profile,
            "weights": weights, "entries": entries, "barcodeFoods": foods, "photos": photos,
        ]
    }

    // MARK: JSON

    private static func matches(_ text: String, _ pattern: String) -> Bool {
        text.range(of: pattern, options: .regularExpression) != nil
    }

    private static func text(_ value: Any?) -> String? {
        guard let text = value as? String, !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return nil }
        return text
    }

    private static func parsed(_ text: String?) -> Any? {
        text?.data(using: .utf8).flatMap(parsedData)
    }

    private static func parsedData(_ data: Data) -> Any? {
        try? JSONSerialization.jsonObject(with: data)
    }

    private static func json(_ value: Any) -> String {
        let data = try? JSONSerialization.data(withJSONObject: value)
        return data.flatMap { String(data: $0, encoding: .utf8) } ?? "{}"
    }
}
