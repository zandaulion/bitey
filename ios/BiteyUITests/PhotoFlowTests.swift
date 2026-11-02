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
        app.launchEnvironment["BITEY_TEST_TRANSACTION"] = "1000"
        app.launch()
        let skip = app.webViews.buttons["Skip"]
        if skip.waitForExistence(timeout: 15) { skip.tap() }
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

        // A real request to the live server. It holds no App Check token, so the
        // server refuses it before Apple or the model is asked, at no cost. Only the
        // page's reaction to that refusal can put this on screen.
        let refused = app.webViews.staticTexts.matching(NSPredicate(format: "label CONTAINS 'did not come from Bitey'")).firstMatch
        XCTAssertTrue(refused.waitForExistence(timeout: 30), "the server's answer never reached the page")
        shot("5-server-refused")
    }

    /// What Apple asks every subscription screen to state.
    func testThePlanScreenCarriesApplesTerms() {
        app.launchEnvironment["BITEY_TEST_PLANS"] = "1"
        app.launch()
        let skip = app.webViews.buttons["Skip"]
        if skip.waitForExistence(timeout: 15) { skip.tap() }
        let photo = app.webViews.buttons["Add a meal from a photo"]
        XCTAssertTrue(photo.waitForExistence(timeout: 20))
        photo.tap()
        let terms = app.webViews.buttons["Terms of Use"]
        XCTAssertTrue(terms.waitForExistence(timeout: 15), "no plan screen")
        sleep(1)
        shot("plans")
        XCTAssertTrue(app.webViews.buttons["Privacy Policy"].exists)
        XCTAssertTrue(app.webViews.staticTexts.matching(NSPredicate(format: "label CONTAINS 'renews automatically'")).firstMatch.exists)
    }
}
