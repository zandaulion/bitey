import XCTest

final class LoggingFlowTests: XCTestCase {
    private let app = XCUIApplication()

    private func start(_ environment: [String: String] = [:]) {
        app.launchEnvironment = environment
        app.launch()
        let skip = app.webViews.buttons["Skip"]
        if skip.waitForExistence(timeout: 15) { skip.tap() }
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
        XCTAssertTrue(barcode.waitForExistence(timeout: 20))
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
        XCTAssertTrue(bite.waitForExistence(timeout: 20))
        for _ in 0..<6 where !bite.isHittable { app.webViews.firstMatch.swipeUp() }
        bite.tap()
        sleep(2)
        let delete = app.webViews.buttons["Delete this entry"].firstMatch
        XCTAssertTrue(delete.waitForExistence(timeout: 10), "no delete button")
        for _ in 0..<6 where !delete.isHittable { app.webViews.firstMatch.swipeUp() }
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
        XCTAssertTrue(settings.waitForExistence(timeout: 20))
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
        XCTAssertTrue(settings.waitForExistence(timeout: 20))
        settings.tap()
        sleep(2)
        let notice = app.webViews.buttons.matching(NSPredicate(format: "label CONTAINS[c] 'privacy'")).firstMatch
        XCTAssertTrue(notice.waitForExistence(timeout: 8))
        for _ in 0..<8 where !notice.isHittable { app.webViews.firstMatch.swipeUp() }
        notice.tap()
        sleep(6)
        shot("privacy")
    }
}
