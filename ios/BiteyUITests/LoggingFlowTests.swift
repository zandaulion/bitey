import XCTest

final class LoggingFlowTests: XCTestCase {
    private let app = XCUIApplication()

    private func start(_ environment: [String: String] = [:]) {
        app.launchEnvironment = environment
        app.launch()
        let skip = app.webViews.buttons["Skip"]
        if skip.waitForExistence(timeout: 45) { skip.tap() }
    }

    /// By frame: asking an off-screen element whether it is hittable can throw.
    private func scrollTo(_ element: XCUIElement) {
        let screen = app.windows.firstMatch.frame.insetBy(dx: 0, dy: 120)
        for _ in 0..<6 where !screen.contains(element.frame) { app.webViews.firstMatch.swipeUp() }
    }

    private func shot(_ name: String) {
        let folder = URL(fileURLWithPath: "/tmp/bitey-shots")
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        try? XCUIScreen.main.screenshot().pngRepresentation
            .write(to: folder.appendingPathComponent("\(name).png"))
    }

    /// A real lookup, over the network, for a product that is certain to exist.
    func testScannedBarcodeFindsAProduct() {
        start(["BITEY_TEST_BARCODE": "3017620422003"])
        let barcode = app.webViews.buttons["Barcode"]
        XCTAssertTrue(barcode.waitForExistence(timeout: 60))
        barcode.tap()
        let allow = app.webViews.buttons["Allow lookup"]
        XCTAssertTrue(allow.waitForExistence(timeout: 10), "no consent before the lookup")
        shot("barcode-consent")
        allow.tap()
        let product = app.webViews.staticTexts.matching(NSPredicate(format: "label CONTAINS[c] 'nutella'")).firstMatch
        XCTAssertTrue(product.waitForExistence(timeout: 25), "the product never appeared")
        shot("barcode-product")
    }

    /// Fails on a WKWebView that ignores confirm(), where a delete does nothing.
    func testDeletingAnEntryAsksAndDeletes() {
        start()
        let bite = app.webViews.buttons["Log Bite (~50 kcal)"]
        XCTAssertTrue(bite.waitForExistence(timeout: 60))
        scrollTo(bite)
        bite.tap()
        sleep(2)
        let delete = app.webViews.buttons["Delete this entry"].firstMatch
        XCTAssertTrue(delete.waitForExistence(timeout: 10), "no delete button")
        scrollTo(delete)
        delete.tap()
        let alert = app.alerts.firstMatch
        XCTAssertTrue(alert.waitForExistence(timeout: 8), "confirm() never appeared")
        shot("delete-confirm")
        alert.buttons.element(boundBy: 1).tap()
        sleep(2)
        XCTAssertTrue(app.webViews.staticTexts["Nothing logged yet."].waitForExistence(timeout: 8), "the entry stayed")
    }

    func testSwipingFromTheEdgeClosesASheet() {
        start()
        let settings = app.webViews.buttons["Settings"]
        XCTAssertTrue(settings.waitForExistence(timeout: 60))
        settings.tap()
        sleep(2)
        let heading = app.webViews.staticTexts["Settings"].firstMatch
        XCTAssertTrue(heading.waitForExistence(timeout: 8))
        app.coordinate(withNormalizedOffset: CGVector(dx: 0, dy: 0.5))
            .press(forDuration: 0.1, thenDragTo: app.coordinate(withNormalizedOffset: CGVector(dx: 0.7, dy: 0.5)))
        sleep(2)
        XCTAssertTrue(app.webViews.buttons["Barcode"].isHittable, "the sheet stayed open")
    }

    func testThePrivacyNoticeOpens() {
        start()
        let settings = app.webViews.buttons["Settings"]
        XCTAssertTrue(settings.waitForExistence(timeout: 60))
        settings.tap()
        sleep(2)
        let notice = app.webViews.buttons.matching(NSPredicate(format: "label CONTAINS[c] 'privacy'")).firstMatch
        XCTAssertTrue(notice.waitForExistence(timeout: 8))
        for _ in 0..<8 where !notice.isHittable { app.webViews.firstMatch.swipeUp() }
        notice.tap()
        sleep(6)
        shot("privacy")
    }

    func testTheWidgetGetsItsSnapshot() {
        try? FileManager.default.createDirectory(atPath: "/tmp/bitey-widget", withIntermediateDirectories: true)
        start(["BITEY_RENDER_WIDGET": "/tmp/bitey-widget"])
        // The page sends a snapshot as soon as it has drawn today.
        let render = "/tmp/bitey-widget/widget-large-light.png"
        let rendered = expectation(for: NSPredicate { _, _ in FileManager.default.fileExists(atPath: render) }, evaluatedWith: nil)
        wait(for: [rendered], timeout: 60)
    }

    /// The route the widget, the shortcuts and the Control Center button all take.
    func testADeepLinkOpensTheRightScreen() {
        start()
        XCTAssertTrue(app.webViews.buttons["Barcode"].waitForExistence(timeout: 60))
        // A cold start, as when the app is not running and the widget is tapped.
        app.terminate()
        app.open(URL(string: "bitey://action/manual")!)
        let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        let open = springboard.buttons["Open"]
        if open.waitForExistence(timeout: 4) { open.tap() }
        // A first launch can show the welcome over the sheet the link opened.
        let skip = app.webViews.buttons["Skip"]
        if skip.waitForExistence(timeout: 12) { skip.tap() }
        let heading = app.webViews.staticTexts["Add food"].firstMatch
        let opened = heading.waitForExistence(timeout: 40)
        shot("deeplink")
        XCTAssertTrue(opened, "the link did not open Add food")
    }
}
