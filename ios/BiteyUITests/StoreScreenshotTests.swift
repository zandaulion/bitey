import XCTest

/// App Store screenshots, from synthetic data. Copy scripts/store-screenshots/seed.js
/// to /tmp/seed.js to run it; ordinary test runs skip it. Use the largest iPhone.
final class StoreScreenshotTests: XCTestCase {
    private let app = XCUIApplication()
    private let folder = "/tmp/bitey-store"

    private func shot(_ name: String) {
        try? XCUIScreen.main.screenshot().pngRepresentation
            .write(to: URL(fileURLWithPath: "\(folder)/\(name).png"))
    }

    func testStoreScreens() throws {
        try XCTSkipUnless(FileManager.default.fileExists(atPath: "/tmp/seed.js"), "no seed script")
        try? FileManager.default.createDirectory(atPath: folder, withIntermediateDirectories: true)
        app.launchEnvironment["BITEY_SEED"] = "/tmp/seed.js"
        app.launch()
        let skip = app.webViews.buttons["Skip"]
        if skip.waitForExistence(timeout: 25) { skip.tap() }
        sleep(25)
        let web = app.webViews.firstMatch
        for label in ["Close", "Not today"] {
            let button = web.buttons[label].firstMatch
            if button.exists, button.isHittable { button.tap(); sleep(1) }
        }
        shot("01-day")
        web.swipeUp()
        sleep(1)
        web.swipeUp()
        sleep(2)
        shot("02-meals")
        web.swipeDown()
        web.swipeDown()
        web.swipeDown()
        sleep(1)
        web.buttons["Trends"].tap()
        sleep(3)
        shot("06-trends")
        close()
        web.buttons["Settings"].tap()
        sleep(2)
        web.swipeUp()
        sleep(2)
        shot("07-settings")
        close()
        web.buttons["Type"].tap()
        sleep(2)
        shot("03-describe")
    }

    private func close() {
        app.coordinate(withNormalizedOffset: CGVector(dx: 0, dy: 0.5))
            .press(forDuration: 0.1, thenDragTo: app.coordinate(withNormalizedOffset: CGVector(dx: 0.7, dy: 0.5)))
        sleep(2)
    }
}
