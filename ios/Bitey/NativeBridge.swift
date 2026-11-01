import Foundation
import WebKit

/// Android's `@JavascriptInterface` methods, by the same names and with the
/// same reply callbacks. Fixed operations only: the page cannot name a table
/// or run a query.
final class NativeBridge: NSObject, WKScriptMessageHandler {
    static let channel = "plate"

    private weak var webView: WKWebView?
    private let work = DispatchQueue(label: "bitey.bridge")
    private let store = LocalStore.shared
    private let foods = FoodSearch()

    init(webView: WKWebView) {
        self.webView = webView
    }

    func userContentController(_ controller: WKUserContentController, didReceive message: WKScriptMessage) {
        guard let body = message.body as? [String: Any],
              let method = body["method"] as? String,
              let args = body["args"] as? [String] else { return }
        work.async { self.handle(method, args) }
    }

    private func handle(_ method: String, _ args: [String]) {
        switch (method, args.count) {
        case ("localDiary", 3):
            reply("__plateNativeDiaryResult", args[2], store.diary(operation: args[0], payload: args[1]))
        case ("localPhoto", 5):
            reply("__plateNativePhotoResult", args[4],
                  store.photoOperation(args[0], id: args[1], payload: args[2], mimeType: args[3]))
        case ("searchGenericFoods", 3):
            reply("__plateNativeGenericFoodSearchResult", args[2], foods.search(query: args[0], lang: args[1]))
        case ("readCachedFood", 1):
            reply("__plateNativeFoodCacheResult", store.readCachedFood(barcode: args[0]))
        case ("writeCachedFood", 2):
            reply("__plateNativeFoodCacheResult", store.writeCachedFood(barcode: args[0], foodJson: args[1]))
        case ("log", 1):
            Self.debugLog(args[0])
        default:
            Self.debugLog("unhandled native call \(method)")
        }
    }

    static func debugLog(_ line: String) {
        #if DEBUG
        let file = FileManager.default.temporaryDirectory.appendingPathComponent("page.log")
        let data = Data((line + "\n").utf8)
        if let handle = try? FileHandle(forWritingTo: file) {
            _ = try? handle.seekToEnd()
            try? handle.write(contentsOf: data)
            try? handle.close()
        } else {
            try? data.write(to: file)
        }
        #endif
    }

    private func reply(_ callback: String, _ arguments: String...) {
        let list = arguments.map(Self.quoted).joined(separator: ", ")
        DispatchQueue.main.async {
            self.webView?.evaluateJavaScript("window.\(callback)?.(\(list))", completionHandler: nil)
        }
    }

    private static func quoted(_ text: String) -> String {
        let data = try? JSONSerialization.data(withJSONObject: text, options: [.fragmentsAllowed])
        return data.flatMap { String(data: $0, encoding: .utf8) } ?? "\"\""
    }
}
