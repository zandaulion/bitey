import Foundation
import SQLite3

private let transient = unsafeBitCast(-1, to: sqlite3_destructor_type.self)

/// The little of SQLite this app needs, with every value bound and never
/// spliced into SQL.
final class SQLiteDatabase {
    enum Value {
        case text(String)
        case double(Double)
        case int(Int64)
    }

    private var handle: OpaquePointer?

    init(path: String, flags: Int32) throws {
        guard sqlite3_open_v2(path, &handle, flags, nil) == SQLITE_OK else {
            throw CocoaError(.fileReadUnknown)
        }
    }

    deinit { sqlite3_close(handle) }

    func execute(_ sql: String, _ values: [Value] = []) throws {
        _ = try rows(sql, values) { _ in () }
    }

    /// Runs one statement and maps each row.
    func rows<T>(_ sql: String, _ values: [Value] = [], map: (OpaquePointer) -> T) throws -> [T] {
        var statement: OpaquePointer?
        guard sqlite3_prepare_v2(handle, sql, -1, &statement, nil) == SQLITE_OK, let statement else {
            throw CocoaError(.fileReadUnknown)
        }
        defer { sqlite3_finalize(statement) }
        for (index, value) in values.enumerated() {
            let position = Int32(index + 1)
            switch value {
            case .text(let text): sqlite3_bind_text(statement, position, text, -1, transient)
            case .double(let number): sqlite3_bind_double(statement, position, number)
            case .int(let number): sqlite3_bind_int64(statement, position, number)
            }
        }
        var output: [T] = []
        while true {
            switch sqlite3_step(statement) {
            case SQLITE_ROW: output.append(map(statement))
            case SQLITE_DONE: return output
            default: throw CocoaError(.fileWriteUnknown)
            }
        }
    }

    var changes: Int { Int(sqlite3_changes(handle)) }

    static func text(_ row: OpaquePointer, _ column: Int32) -> String? {
        sqlite3_column_text(row, column).map { String(cString: $0) }
    }

    static func double(_ row: OpaquePointer, _ column: Int32) -> Double? {
        sqlite3_column_type(row, column) == SQLITE_NULL ? nil : sqlite3_column_double(row, column)
    }

    static func int(_ row: OpaquePointer, _ column: Int32) -> Int64 {
        sqlite3_column_int64(row, column)
    }
}
