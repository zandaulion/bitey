import Foundation
import UniformTypeIdentifiers
import WebKit

/// Serves the packaged interface and saved photos under a private origin.
/// WKWebView cannot intercept https, so a custom scheme plays the part that
/// https://plate.local plays on Android.
final class AppSchemeHandler: NSObject, WKURLSchemeHandler {
    static let scheme = "bitey"
    static let home = URL(string: "\(scheme)://app/index.html")!

    private let root = Bundle.main.bundleURL.appendingPathComponent("www", isDirectory: true)

    func webView(_ webView: WKWebView, start task: WKURLSchemeTask) {
        guard let url = task.request.url else {
            task.didFailWithError(URLError(.badURL))
            return
        }
        let path = url.path.isEmpty || url.path == "/" ? "/index.html" : url.path

        if path.hasPrefix("/local-photo/"),
           let photo = LocalStore.shared.photo(id: String(path.dropFirst("/local-photo/".count))) {
            respond(task, url: url, data: photo.data, mimeType: photo.mimeType)
            return
        }

        let file = root.appendingPathComponent(String(path.dropFirst())).standardizedFileURL
        guard file.path.hasPrefix(root.standardizedFileURL.path),
              let data = try? Data(contentsOf: file) else {
            respond(task, url: url, data: Data(), mimeType: "text/plain", status: 404)
            return
        }
        respond(task, url: url, data: data, mimeType: Self.mimeType(for: file))
    }

    func webView(_ webView: WKWebView, stop task: WKURLSchemeTask) {}

    private func respond(_ task: WKURLSchemeTask, url: URL, data: Data, mimeType: String, status: Int = 200) {
        let response = HTTPURLResponse(
            url: url,
            statusCode: status,
            httpVersion: "HTTP/1.1",
            headerFields: [
                "Content-Type": mimeType,
                "Content-Length": String(data.count),
                "Cache-Control": "no-store",
            ]
        )!
        task.didReceive(response)
        task.didReceive(data)
        task.didFinish()
    }

    private static func mimeType(for file: URL) -> String {
        switch file.pathExtension.lowercased() {
        case "js", "mjs": return "text/javascript"
        case "webmanifest": return "application/manifest+json"
        default: return UTType(filenameExtension: file.pathExtension)?.preferredMIMEType ?? "application/octet-stream"
        }
    }
}
