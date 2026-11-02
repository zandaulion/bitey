import Foundation
import SafariServices
import UIKit
import WebKit
import WidgetKit

/// Android's `@JavascriptInterface` methods, by the same names and with the
/// same reply callbacks. Fixed operations only: the page cannot name a table
/// or run a query.
final class NativeBridge: NSObject, WKScriptMessageHandler {
    static let channel = "plate"
    private static let privacyNotice = "https://zandaulion.com/plate-privacy.html"

    private weak var webView: WKWebView?
    private let work = DispatchQueue(label: "bitey.bridge")
    private let store = LocalStore.shared
    private let foods = FoodSearch()
    private let capture: PhotoCapture
    private let backupPicker: BackupPicker
    private lazy var backup = LocalBackup(store: store)
    private let purchases = AiPurchases()
    private weak var presenter: UIViewController?
    /// The page sends its first widget snapshot once today is on screen.
    var onPageDrawn: (() -> Void)?

    init(webView: WKWebView, presenter: UIViewController) {
        self.webView = webView
        capture = PhotoCapture(presenter: presenter)
        backupPicker = BackupPicker(presenter: presenter)
        self.presenter = presenter
        super.init()
        purchases.onChange = { [weak self] status in self?.reply("__plateNativeAiEntitlement", status) }
        purchases.start()
    }

    func userContentController(_ controller: WKUserContentController, didReceive message: WKScriptMessage) {
        guard let body = message.body as? [String: Any],
              let method = body["method"] as? String,
              let args = body["args"] as? [String] else { return }
        if method == "capturePhoto", args.count == 2 {
            capturePhoto(source: args[0], requestId: args[1])
        } else if method == "openPrivacyNotice", let url = URL(string: Self.privacyNotice) {
            presenter?.present(SFSafariViewController(url: url), animated: true)
        } else if method == "scanBarcode" {
            scanBarcode(labels: args.first ?? "{}")
        } else {
            work.async { self.handle(method, args) }
        }
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
        case ("requestAiAccess", 2):
            Task {
                let result = await purchases.requestAccess()
                reply("__plateNativeAiAccessResult", args[1], Self.json(result))
            }
        case ("purchaseAiOffer", 2):
            Task {
                let result = await purchases.purchase(offer: args[0])
                reply("__plateNativeAiAccessResult", args[1], Self.json(result))
            }
        case ("refreshAiPurchase", 0):
            Task { reply("__plateNativeAiRestoreResult", await purchases.restore()) }
        case ("manageAiSubscription", 0):
            Task { await purchases.manage(from: webView) }
        case ("analysePhoto", 2):
            reply("__plateNativeAnalysisResult", args[1], Self.json([
                "httpStatus": 503,
                "body": ["error": "unavailable", "message": "Bitey AI is not available on iPhone yet."],
            ]))
        case ("lookupOpenFoodFacts", 1):
            OpenFoodFacts.lookup(barcode: args[0]) { [weak self] result in
                self?.reply("__plateNativeOpenFoodFactsResult", result)
            }
        case ("setPrimaryActionLabels", 4):
            publishShortcuts(manual: args[0], barcode: args[1], gallery: args[2], photo: args[3])
        case ("setWidgetSnapshot", 1):
            TodaySnapshot.save(Data(args[0].utf8))
            WidgetCenter.shared.reloadAllTimelines()
            DispatchQueue.main.async { self.onPageDrawn?() }
            #if DEBUG
            DebugSelfTest.renderWidget()
            #endif
        case ("setWidgetBitey", 1):
            if let data = Data(base64Encoded: args[0]), data.count < 500_000, let file = TodaySnapshot.faceFile {
                try? data.write(to: file, options: .atomic)
                WidgetCenter.shared.reloadAllTimelines()
                #if DEBUG
                DebugSelfTest.renderWidget()
                #endif
            }
        case ("exportBackup", 0):
            exportBackup()
        case ("importBackup", 0):
            importBackup()
        case ("log", 1):
            Self.debugLog(args[0])
        default:
            Self.debugLog("unhandled native call \(method)")
        }
    }

    /// Sent on every start and language change, so these follow the language chosen
    /// in Bitey and not the phone's, as on Android. Photo sits nearest the icon.
    private func publishShortcuts(manual: String, barcode: String, gallery: String, photo: String) {
        let items: [(String, String, String)] = [
            ("photo", photo, "camera.fill"),
            ("gallery", gallery, "photo.on.rectangle"),
            ("barcode", barcode, "barcode.viewfinder"),
            ("manual", manual, "pencil"),
        ]
        DispatchQueue.main.async {
            UIApplication.shared.shortcutItems = items.map { type, title, icon in
                UIApplicationShortcutItem(
                    type: type, localizedTitle: title, localizedSubtitle: nil,
                    icon: UIApplicationShortcutIcon(systemImageName: icon)
                )
            }
        }
    }

    private func scanBarcode(labels: String) {
        #if DEBUG
        if let code = ProcessInfo.processInfo.environment["BITEY_TEST_BARCODE"] {
            reply("__plateNativeBarcodeResult", code)
            return
        }
        #endif
        let parsed = labels.data(using: .utf8)
            .flatMap { try? JSONSerialization.jsonObject(with: $0) } as? [String: String] ?? [:]
        let scanner = BarcodeScannerViewController(labels: parsed) { [weak self] code in
            self?.reply("__plateNativeBarcodeResult", code)
        }
        presenter?.present(scanner, animated: true)
    }

    private func exportBackup() {
        do {
            let result = try backup.export()
            #if DEBUG
            if let path = ProcessInfo.processInfo.environment["BITEY_TEST_BACKUP"] {
                try? FileManager.default.removeItem(atPath: path)
                try FileManager.default.copyItem(at: result.url, to: URL(fileURLWithPath: path))
                try? FileManager.default.removeItem(at: result.url)
                backupReply("export", ["ok": true, "summary": result.summary])
                return
            }
            #endif
            DispatchQueue.main.async {
                self.backupPicker.save(result.url) { [weak self] saved in
                    try? FileManager.default.removeItem(at: result.url)
                    self?.backupReply("export", saved == nil
                        ? ["ok": false, "code": "cancelled"]
                        : ["ok": true, "summary": result.summary])
                }
            }
        } catch {
            backupFailed("export", error, fallback: "The backup could not be written.")
        }
    }

    private func importBackup() {
        #if DEBUG
        if let path = ProcessInfo.processInfo.environment["BITEY_TEST_BACKUP"] {
            restoreBackup(from: URL(fileURLWithPath: path))
            return
        }
        #endif
        DispatchQueue.main.async {
            self.backupPicker.open { [weak self] url in
                guard let self else { return }
                guard let url else {
                    self.backupReply("import", ["ok": false, "code": "cancelled"])
                    return
                }
                self.work.async { self.restoreBackup(from: url) }
            }
        }
    }

    private func restoreBackup(from url: URL) {
        do {
            backupReply("import", ["ok": true, "summary": try backup.importFrom(url: url)])
        } catch {
            backupFailed("import", error, fallback: "The backup could not be restored.")
        }
    }

    private func backupFailed(_ operation: String, _ error: Error, fallback: String) {
        let message = (error as? LocalBackup.Problem)?.message ?? fallback
        backupReply(operation, ["ok": false, "code": "backup_error", "message": message])
    }

    private func backupReply(_ operation: String, _ result: [String: Any]) {
        reply("__plateNativeBackupResult", operation, Self.json(result))
    }

    private func capturePhoto(source: String, requestId: String) {
        capture.capture(source: source) { [weak self] id in
            var answer: [String: Any] = ["status": id == nil ? "cancelled" : "ok"]
            answer["id"] = id
            self?.reply("__plateNativeCaptureResult", requestId, Self.json(answer))
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

    private static func json(_ value: [String: Any]) -> String {
        let data = try? JSONSerialization.data(withJSONObject: value)
        return data.flatMap { String(data: $0, encoding: .utf8) } ?? "{}"
    }

    private static func quoted(_ text: String) -> String {
        let data = try? JSONSerialization.data(withJSONObject: text, options: [.fragmentsAllowed])
        return data.flatMap { String(data: $0, encoding: .utf8) } ?? "\"\""
    }
}
