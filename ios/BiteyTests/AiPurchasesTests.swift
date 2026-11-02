import StoreKitTest
import XCTest
@testable import Bitey

/// Runs inside the app against Apple's local StoreKit, with no dialogs and no
/// account. These are the same calls the page's plan picker drives.
///
/// Run these from Xcode (Cmd+U). SKTestSession fails from `xcodebuild test` on the
/// iOS 26.5 Simulator with SKInternalErrorDomain 3, an Apple bug (FB22237318), so
/// scripts/test.sh skips them. They have not passed yet.
final class AiPurchasesTests: XCTestCase {
    private var session: SKTestSession!
    private var purchases: AiPurchases!

    override func setUpWithError() throws {
        session = try SKTestSession(configurationFileNamed: "Bitey")
        session.disableDialogs = true
        session.clearTransactions()
        purchases = AiPurchases()
    }

    override func tearDown() {
        session.clearTransactions()
    }

    private func offers() async throws -> [String: [String: Any]] {
        let result = await purchases.requestAccess()
        XCTAssertEqual(result["status"] as? String, "offers")
        let list = try XCTUnwrap(result["offers"] as? [[String: Any]])
        return Dictionary(uniqueKeysWithValues: list.map { ($0["id"] as? String ?? "", $0) })
    }

    func testPlansCarryPriceAndTrial() async throws {
        let offers = try await offers()
        XCTAssertEqual(Set(offers.keys), ["monthly", "yearly"])
        XCTAssertTrue((offers["monthly"]?["price"] as? String ?? "").contains("2.99"))
        XCTAssertTrue((offers["yearly"]?["price"] as? String ?? "").contains("24.99"))
        XCTAssertEqual(offers["monthly"]?["trial"] as? String, "P1M")
        XCTAssertNil(offers["yearly"]?["trial"])
    }

    func testBuyingActivatesAndNamesTheTransaction() async throws {
        let bought = await purchases.purchase(offer: "yearly")
        XCTAssertEqual(bought["status"] as? String, "active")
        XCTAssertEqual(AiPurchases.cachedStatus, "active")
        let id = try XCTUnwrap(AiPurchases.transactionId)
        XCTAssertNotNil(UInt64(id), "the server only accepts a numeric id")

        let again = await purchases.requestAccess()
        XCTAssertEqual(again["status"] as? String, "active")
    }

    func testAnEndedSubscriptionLocksAgain() async throws {
        _ = await purchases.purchase(offer: "monthly")
        try session.expireSubscription(productIdentifier: "bitey_ai_monthly")
        let status = await purchases.refresh()
        XCTAssertEqual(status, "inactive")
        XCTAssertEqual(AiPurchases.cachedStatus, "inactive")
        XCTAssertNil(AiPurchases.transactionId)
    }

    func testAFailedPurchaseGrantsNothing() async {
        session.failTransactionsEnabled = true
        let result = await purchases.purchase(offer: "monthly")
        session.failTransactionsEnabled = false
        XCTAssertNotEqual(result["status"] as? String, "active")
        let status = await purchases.refresh()
        XCTAssertEqual(status, "inactive")
    }

    func testAnUnknownPlanIsRefused() async {
        let result = await purchases.purchase(offer: "lifetime")
        XCTAssertEqual(result["status"] as? String, "unavailable")
    }

    func testThePadlocksHearAboutChanges() async {
        let heard = expectation(description: "status changed")
        purchases.onChange = { status in
            if status == "active" { heard.fulfill() }
        }
        _ = await purchases.purchase(offer: "monthly")
        await fulfillment(of: [heard], timeout: 5)
    }
}
