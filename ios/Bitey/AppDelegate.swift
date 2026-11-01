import UIKit

@main
final class AppDelegate: UIResponder, UIApplicationDelegate {
    var window: UIWindow?

    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?
    ) -> Bool {
        let window = UIWindow(frame: UIScreen.main.bounds)
        window.rootViewController = WebViewController()
        window.makeKeyAndVisible()
        self.window = window
        return true
    }

    /// The widget, the Control Center button: bitey://action/photo and so on.
    func application(
        _ app: UIApplication,
        open url: URL,
        options: [UIApplication.OpenURLOptionsKey: Any] = [:]
    ) -> Bool {
        #if DEBUG
        NativeBridge.debugLog("opened \(url) root \(String(describing: window?.rootViewController))")
        #endif
        guard url.host == "action" else { return false }
        (window?.rootViewController as? WebViewController)?.perform(action: url.lastPathComponent)
        return true
    }

    /// The long-press shortcuts on the app icon.
    func application(
        _ application: UIApplication,
        performActionFor shortcutItem: UIApplicationShortcutItem,
        completionHandler: @escaping (Bool) -> Void
    ) {
        (window?.rootViewController as? WebViewController)?.perform(action: shortcutItem.type)
        completionHandler(true)
    }
}
