import XCTest

/// Drives the real app. AI access is faked by a debug-only switch, because the page
/// only asks for a photo once it has access, and BITEY_TEST_PHOTO stands in for the
/// tap inside Apple's picker, which UI tests cannot reach.
final class PhotoFlowTests: XCTestCase {
    private let app = XCUIApplication()

    private func shot(_ name: String) {
        let folder = URL(fileURLWithPath: "/tmp/bitey-shots")
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        try? XCUIScreen.main.screenshot().pngRepresentation
            .write(to: folder.appendingPathComponent("\(name).png"))
    }

    private func start() {
        app.launchEnvironment["BITEY_FAKE_AI"] = "1"
        app.launchEnvironment["BITEY_TEST_PHOTO"] = "/tmp/sample.jpg"
        app.launch()
        let skip = app.webViews.buttons["Skip"]
        if skip.waitForExistence(timeout: 40) { skip.tap() }
    }

    func testPickingAPhotoReachesTheReviewSheet() {
        start()
        let pick = app.webViews.buttons["Already have a photo?"]
        XCTAssertTrue(pick.waitForExistence(timeout: 20))
        // It sits below the fold.
        for _ in 0..<6 where !pick.isHittable { app.webViews.firstMatch.swipeUp() }
        shot("1-before")
        pick.tap()
        sleep(2)
        shot("2-after-tap")

        // The first photo asks for consent.
        let consent = app.webViews.buttons["Agree and continue"]
        if consent.waitForExistence(timeout: 6) { consent.tap() }
        sleep(5)
        shot("4-after-pick")

        // Native code has no way to make this appear: the page only gets here with
        // the photo it fetched. The reading then stops, as AI is not built yet.
        let refused = app.webViews.staticTexts["Bitey AI is not available on iPhone yet."]
        XCTAssertTrue(refused.waitForExistence(timeout: 15), "the photo never reached the page")
    }
}
