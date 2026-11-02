import XCTest

/// Walks the main screens and saves a picture of each, to read by eye. It asserts
/// little: its job is to show what a small phone does to the layout.
final class ScreenTourTests: XCTestCase {
    private let app = XCUIApplication()

    private func shot(_ name: String) {
        let folder = URL(fileURLWithPath: "/tmp/bitey-tour")
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        try? XCUIScreen.main.screenshot().pngRepresentation
            .write(to: folder.appendingPathComponent("\(name).png"))
    }

    /// Bitey's back swipe, which closes whatever is on top.
    private func close() {
        app.coordinate(withNormalizedOffset: CGVector(dx: 0, dy: 0.5))
            .press(forDuration: 0.1, thenDragTo: app.coordinate(withNormalizedOffset: CGVector(dx: 0.7, dy: 0.5)))
        sleep(2)
    }

    func testTour() {
        app.launch()
        let next = app.webViews.buttons["Next"]
        if next.waitForExistence(timeout: 30) {
            sleep(1)
            shot("01-welcome-1")
            for index in 2...3 where next.exists && next.isHittable {
                next.tap()
                sleep(1)
                shot("01-welcome-\(index)")
            }
            let skip = app.webViews.buttons["Skip"]
            if skip.exists, skip.isHittable { skip.tap() }
            for label in ["Get started", "Start logging", "Done", "Maybe later", "Let's go"] {
                let button = app.webViews.buttons[label]
                if button.exists, button.isHittable { button.tap(); break }
            }
        }
        sleep(2)
        shot("02-home")

        let web = app.webViews.firstMatch
        web.swipeUp()
        sleep(1)
        shot("03-home-scrolled")
        web.swipeUp()
        sleep(1)
        shot("04-home-bottom")
        web.swipeDown()
        web.swipeDown()

        app.webViews.buttons["Type"].tap()
        sleep(2)
        shot("05-add-food")
        let description = app.webViews.textViews.firstMatch
        if description.exists {
            description.tap()
            sleep(2)
            app.typeText("Two eggs")
            sleep(2)
            shot("06-keyboard-text")
            app.webViews.firstMatch.swipeDown()
            sleep(1)
        }
        let done = app.toolbars.buttons["Done"]
        if done.exists { done.tap() }
        sleep(1)
        let search = app.webViews.buttons.matching(NSPredicate(format: "label CONTAINS 'Search foods'")).firstMatch
        for _ in 0..<4 where !search.isHittable { web.swipeUp() }
        if search.exists, search.isHittable {
            search.tap()
            sleep(2)
            shot("06-search")
            let field = app.webViews.searchFields.firstMatch.exists ? app.webViews.searchFields.firstMatch : app.webViews.textFields.firstMatch
            if field.exists {
                field.tap()
                sleep(2)
                app.typeText("orez")
                sleep(3)
                shot("06-keyboard-search")
            }
        }
        for _ in 0..<3 { close() }

        app.webViews.buttons["Settings"].tap()
        sleep(2)
        shot("07-settings")
        web.swipeUp()
        sleep(1)
        shot("08-settings-scrolled")
        close()

        app.webViews.buttons["You"].tap()
        sleep(2)
        shot("09-profile")
        web.swipeUp()
        sleep(1)
        shot("09-profile-scrolled")
        close()

        web.swipeDown()
        sleep(1)
        let trends = app.webViews.buttons["Trends"]
        for _ in 0..<3 where !trends.isHittable { web.swipeUp() }
        trends.tap()
        sleep(3)
        shot("10-trends")
        web.swipeUp()
        sleep(1)
        shot("11-trends-scrolled")
    }

    /// The first-run card that names Control Center, in the phone's language.
    func testWelcomeCardInRomanian() {
        app.launchArguments += ["-AppleLanguages", "(ro)", "-AppleLocale", "ro_RO"]
        app.launch()
        XCTAssertTrue(app.webViews.firstMatch.waitForExistence(timeout: 30))
        sleep(6)
        app.webViews.firstMatch.swipeUp()
        sleep(1)
        shot("12-welcome-card-ro")
    }

    func testWelcomeCardInEnglish() {
        app.launch()
        XCTAssertTrue(app.webViews.firstMatch.waitForExistence(timeout: 30))
        sleep(6)
        app.webViews.firstMatch.swipeUp()
        sleep(1)
        shot("12-welcome-card-en")
        XCTAssertTrue(app.webViews.staticTexts["Control Center button"].exists, "still the Android wording")
    }
}
