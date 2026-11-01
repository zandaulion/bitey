import Compression
import Foundation

/// The small part of ZIP that Bitey's backups need. iOS has no public ZIP API.
/// Reading walks the central directory, so it handles the data descriptors that
/// Java's ZipOutputStream writes. Writing stores without compressing: a backup is
/// mostly JPEGs, and any unzip tool can read it.
enum Zip {
    struct Failure: Error {}

    private static let crcTable: [UInt32] = (0..<256).map { index in
        (0..<8).reduce(UInt32(index)) { value, _ in
            value & 1 == 1 ? 0xEDB8_8320 ^ (value >> 1) : value >> 1
        }
    }

    static func crc32(_ data: Data) -> UInt32 {
        ~data.reduce(~UInt32(0)) { crc, byte in
            crcTable[Int((crc ^ UInt32(byte)) & 0xFF)] ^ (crc >> 8)
        }
    }

    final class Writer {
        private let file: FileHandle
        private var directory = Data()
        private var count: UInt16 = 0
        private var offset: UInt32 = 0

        init(url: URL) throws {
            FileManager.default.createFile(atPath: url.path, contents: nil)
            file = try FileHandle(forWritingTo: url)
        }

        func add(_ name: String, _ content: Data) throws {
            let nameBytes = Data(name.utf8)
            var common = Data()
            common.appendLE(UInt16(20))
            common.appendLE(UInt16(0x0800)) // UTF-8 names.
            common.appendLE(UInt16(0)) // Stored.
            common.appendLE(UInt16(0))
            common.appendLE(UInt16(0x21)) // 1980-01-01, the format's earliest date.
            common.appendLE(Zip.crc32(content))
            common.appendLE(UInt32(content.count))
            common.appendLE(UInt32(content.count))
            common.appendLE(UInt16(nameBytes.count))
            common.appendLE(UInt16(0))

            var local = Data()
            local.appendLE(UInt32(0x0403_4B50))
            local.append(common)
            local.append(nameBytes)
            try file.write(contentsOf: local)
            try file.write(contentsOf: content)

            directory.appendLE(UInt32(0x0201_4B50))
            directory.appendLE(UInt16(20))
            directory.append(common)
            directory.appendLE(UInt16(0))
            directory.appendLE(UInt16(0))
            directory.appendLE(UInt16(0))
            directory.appendLE(UInt32(0))
            directory.appendLE(offset)
            directory.append(nameBytes)

            offset += UInt32(local.count + content.count)
            count += 1
        }

        func finish() throws {
            var end = Data()
            end.appendLE(UInt32(0x0605_4B50))
            end.appendLE(UInt16(0))
            end.appendLE(UInt16(0))
            end.appendLE(count)
            end.appendLE(count)
            end.appendLE(UInt32(directory.count))
            end.appendLE(offset)
            end.appendLE(UInt16(0))
            try file.write(contentsOf: directory)
            try file.write(contentsOf: end)
            try file.close()
        }
    }

    struct Entry {
        let name: String
        let method: UInt16
        let compressedSize: Int
        let size: Int
        let crc: UInt32
        let offset: Int
    }

    final class Reader {
        private let data: Data
        let entries: [Entry]

        init(url: URL) throws {
            data = try Data(contentsOf: url, options: .alwaysMapped)
            var end = data.count - 22
            let floor = max(0, data.count - 22 - 65_535)
            while end >= floor, data.le32(end) != 0x0605_4B50 { end -= 1 }
            guard end >= floor else { throw Failure() }

            var entries: [Entry] = []
            var position = Int(data.le32(end + 16))
            for _ in 0..<Int(data.le16(end + 10)) {
                guard position + 46 <= data.count, data.le32(position) == 0x0201_4B50 else { throw Failure() }
                let nameLength = Int(data.le16(position + 28))
                let extraLength = Int(data.le16(position + 30))
                let commentLength = Int(data.le16(position + 32))
                let nameStart = data.startIndex + position + 46
                guard position + 46 + nameLength <= data.count,
                      let name = String(data: data[nameStart..<nameStart + nameLength], encoding: .utf8) else {
                    throw Failure()
                }
                entries.append(Entry(
                    name: name,
                    method: data.le16(position + 10),
                    compressedSize: Int(data.le32(position + 20)),
                    size: Int(data.le32(position + 24)),
                    crc: data.le32(position + 16),
                    offset: Int(data.le32(position + 42))
                ))
                position += 46 + nameLength + extraLength + commentLength
            }
            self.entries = entries
        }

        func read(_ entry: Entry, limit: Int) throws -> Data {
            guard entry.size <= limit, entry.offset + 30 <= data.count,
                  data.le32(entry.offset) == 0x0403_4B50 else { throw Failure() }
            let start = entry.offset + 30 + Int(data.le16(entry.offset + 26)) + Int(data.le16(entry.offset + 28))
            guard start + entry.compressedSize <= data.count else { throw Failure() }
            let raw = data[(data.startIndex + start)..<(data.startIndex + start + entry.compressedSize)]

            let content: Data
            switch entry.method {
            case 0:
                content = Data(raw)
            case 8:
                content = try Self.inflate(raw, size: entry.size)
            default:
                throw Failure()
            }
            guard content.count == entry.size, Zip.crc32(content) == entry.crc else { throw Failure() }
            return content
        }

        /// COMPRESSION_ZLIB is raw DEFLATE, which is what ZIP stores.
        private static func inflate(_ raw: Data, size: Int) throws -> Data {
            if size == 0 { return Data() }
            var output = Data(count: size)
            let written = output.withUnsafeMutableBytes { target in
                raw.withUnsafeBytes { source in
                    compression_decode_buffer(
                        target.bindMemory(to: UInt8.self).baseAddress!, size,
                        source.bindMemory(to: UInt8.self).baseAddress!, raw.count,
                        nil, COMPRESSION_ZLIB
                    )
                }
            }
            guard written == size else { throw Failure() }
            return output
        }
    }
}

private extension Data {
    mutating func appendLE<T: FixedWidthInteger>(_ value: T) {
        Swift.withUnsafeBytes(of: value.littleEndian) { append(contentsOf: $0) }
    }

    func le16(_ position: Int) -> UInt16 {
        let at = startIndex + position
        return UInt16(self[at]) | UInt16(self[at + 1]) << 8
    }

    func le32(_ position: Int) -> UInt32 {
        UInt32(le16(position)) | UInt32(le16(position + 2)) << 16
    }
}
