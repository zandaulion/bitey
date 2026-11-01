import UIKit
import WebKit

/// Hosts the shared interface. The page is the app; this only gives it a
/// private origin and the native services it asks for through `PlateNative`.
final class WebViewController: UIViewController {
    private var webView: WKWebView!
    private var bridge: NativeBridge!

    override func loadView() {
        let configuration = WKWebViewConfiguration()
        configuration.setURLSchemeHandler(AppSchemeHandler(), forURLScheme: AppSchemeHandler.scheme)

        var source = Bundle.main.url(forResource: "bridge", withExtension: "js")
            .flatMap { try? String(contentsOf: $0, encoding: .utf8) } ?? ""
        #if DEBUG
        if ProcessInfo.processInfo.environment["BITEY_FAKE_AI"] == "1" {
            source = "window.__plateFakeAi = true;\n" + source
        }
        #endif
        configuration.userContentController.addUserScript(
            WKUserScript(source: source, injectionTime: .atDocumentStart, forMainFrameOnly: true)
        )

        webView = WKWebView(frame: .zero, configuration: configuration)
        webView.navigationDelegate = self
        webView.uiDelegate = self
        webView.scrollView.bounces = false
        // The page pads itself with env(safe-area-inset-*), as it does in Chrome.
        webView.scrollView.contentInsetAdjustmentBehavior = .never
        // The launch screen's colour, so the page appears without a flash.
        webView.isOpaque = false
        webView.backgroundColor = UIColor(named: "LaunchBackground")
        #if DEBUG
        webView.isInspectable = true
        #endif

        bridge = NativeBridge(webView: webView, presenter: self)
        let back = UIScreenEdgePanGestureRecognizer(target: self, action: #selector(swipedBack(_:)))
        back.edges = .left
        webView.addGestureRecognizer(back)
        configuration.userContentController.add(bridge, name: NativeBridge.channel)
        view = webView
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        webView.load(URLRequest(url: AppSchemeHandler.home))
        NotificationCenter.default.addObserver(
            self, selector: #selector(applyTextSize),
            name: UIContentSizeCategory.didChangeNotification, object: nil
        )
    }

    /// iOS's back gesture, which closes the page's top sheet as Android's Back does.
    @objc private func swipedBack(_ gesture: UIScreenEdgePanGestureRecognizer) {
        guard gesture.state == .ended else { return }
        webView.evaluateJavaScript("window.__plateNativeBack?.()", completionHandler: nil)
    }

    /// Ordinary sizes stay at the page's own, and accessibility sizes grow with the
    /// setting, the rule `textZoomFor` follows on Android.
    @objc private func applyTextSize() {
        let scale = UIFontMetrics(forTextStyle: .body).scaledValue(for: 100) / 100
        let percent = max(100, Int((scale * 100).rounded()) - 15)
        webView.evaluateJavaScript(
            "document.documentElement.style.webkitTextSizeAdjust = '\\(percent)%'",
            completionHandler: nil
        )
    }
}

/// WKWebView drops alert, confirm and prompt unless the app shows them, and an
/// unanswered confirm would quietly refuse every delete.
extension WebViewController: WKUIDelegate {
    func webView(
        _ webView: WKWebView,
        runJavaScriptAlertPanelWithMessage message: String,
        initiatedByFrame frame: WKFrameInfo,
        completionHandler: @escaping () -> Void
    ) {
        let alert = UIAlertController(title: nil, message: message, preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: dialogLabel("OK"), style: .default) { _ in completionHandler() })
        present(alert, animated: true)
    }

    func webView(
        _ webView: WKWebView,
        runJavaScriptConfirmPanelWithMessage message: String,
        initiatedByFrame frame: WKFrameInfo,
        completionHandler: @escaping (Bool) -> Void
    ) {
        let alert = UIAlertController(title: nil, message: message, preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: dialogLabel("Cancel"), style: .cancel) { _ in completionHandler(false) })
        alert.addAction(UIAlertAction(title: dialogLabel("OK"), style: .default) { _ in completionHandler(true) })
        present(alert, animated: true)
    }

    func webView(
        _ webView: WKWebView,
        runJavaScriptTextInputPanelWithPrompt prompt: String,
        defaultText: String?,
        initiatedByFrame frame: WKFrameInfo,
        completionHandler: @escaping (String?) -> Void
    ) {
        let alert = UIAlertController(title: nil, message: prompt, preferredStyle: .alert)
        alert.addTextField { $0.text = defaultText }
        alert.addAction(UIAlertAction(title: dialogLabel("Cancel"), style: .cancel) { _ in completionHandler(nil) })
        alert.addAction(UIAlertAction(title: dialogLabel("OK"), style: .default) { [weak alert] _ in
            completionHandler(alert?.textFields?.first?.text)
        })
        present(alert, animated: true)
    }

    /// UIKit's own translation, which follows the phone's language.
    private func dialogLabel(_ key: String) -> String {
        Bundle(for: UIApplication.self).localizedString(forKey: key, value: key, table: nil)
    }
}

extension WebViewController: WKNavigationDelegate {
    /// The app only navigates inside its packaged origin, as on Android.
    func webView(
        _ webView: WKWebView,
        decidePolicyFor navigationAction: WKNavigationAction,
        decisionHandler: @escaping (WKNavigationActionPolicy) -> Void
    ) {
        let inside = navigationAction.request.url?.scheme == AppSchemeHandler.scheme
        decisionHandler(inside ? .allow : .cancel)
    }

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        applyTextSize()
        #if DEBUG
        DebugSelfTest.run(in: webView)
        #endif
    }

    #if DEBUG
    func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
        NativeBridge.debugLog("navigation failed: \(error)")
    }

    func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
        NativeBridge.debugLog("load failed: \(error)")
    }
    #endif
}
