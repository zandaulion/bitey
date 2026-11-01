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
        webView.scrollView.bounces = false
        // The page pads itself with env(safe-area-inset-*), as it does in Chrome.
        webView.scrollView.contentInsetAdjustmentBehavior = .never
        webView.isOpaque = false
        webView.backgroundColor = .systemBackground
        #if DEBUG
        webView.isInspectable = true
        #endif

        bridge = NativeBridge(webView: webView, presenter: self)
        configuration.userContentController.add(bridge, name: NativeBridge.channel)
        view = webView
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        webView.load(URLRequest(url: AppSchemeHandler.home))
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

    #if DEBUG
    func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
        NativeBridge.debugLog("navigation failed: \(error)")
    }

    func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
        NativeBridge.debugLog("load failed: \(error)")
    }

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        DebugSelfTest.run(in: webView)
    }
    #endif
}
