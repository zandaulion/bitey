import StoreKit
import UIKit

/// Bitey AI through in-app purchase, in the words the page already speaks: the same
/// statuses and offers Android's PlayBilling hands it. What a device says here only
/// shapes the interface; the server asks Apple before any photo reaches the model.
final class AiPurchases {
    static let products = ["monthly": "bitey_ai_monthly", "yearly": "bitey_ai_yearly"]
    private static let statusKey = "bitey.ai.status"
    private static let transactionKey = "bitey.ai.transaction"

    /// Called when the entitlement changes, so padlocks follow a renewal or a refund.
    var onChange: ((String) -> Void)?

    /// Read before the page paints, so a subscriber never sees padlocks flash.
    static var cachedStatus: String {
        #if DEBUG
        if ProcessInfo.processInfo.environment["BITEY_FAKE_AI"] == "1" { return "active" }
        #endif
        return UserDefaults.standard.string(forKey: statusKey) ?? "inactive"
    }

    /// The transaction the server asks Apple about.
    static var transactionId: String? {
        #if DEBUG
        if let id = ProcessInfo.processInfo.environment["BITEY_TEST_TRANSACTION"] { return id }
        #endif
        return UserDefaults.standard.string(forKey: transactionKey)
    }

    func start() {
        Task.detached { [weak self] in
            await self?.refresh()
            for await update in Transaction.updates {
                if case .verified(let transaction) = update { await transaction.finish() }
                await self?.refresh()
            }
        }
    }

    @discardableResult
    func refresh() async -> String {
        var active: Transaction?
        for await entitlement in Transaction.currentEntitlements {
            guard case .verified(let transaction) = entitlement,
                  Self.products.values.contains(transaction.productID),
                  transaction.revocationDate == nil else { continue }
            if active == nil || (transaction.expirationDate ?? .distantPast) > (active?.expirationDate ?? .distantPast) {
                active = transaction
            }
        }
        let defaults = UserDefaults.standard
        let previous = defaults.string(forKey: Self.statusKey)
        let status = active == nil ? "inactive" : "active"
        defaults.set(status, forKey: Self.statusKey)
        defaults.set(active.map { String($0.id) }, forKey: Self.transactionKey)
        if previous != status { onChange?(status) }
        return status
    }

    /// `active` lets the page carry on; otherwise the page shows these plans.
    func requestAccess() async -> [String: Any] {
        #if DEBUG
        if ProcessInfo.processInfo.environment["BITEY_FAKE_AI"] == "1" { return ["status": "active"] }
        if ProcessInfo.processInfo.environment["BITEY_TEST_PLANS"] == "1" {
            return ["status": "offers", "offers": [
                ["id": "monthly", "price": "€2.99", "trial": "P1M"],
                ["id": "yearly", "price": "€24.99"],
            ]]
        }
        #endif
        if await refresh() == "active" { return ["status": "active"] }
        guard let products = try? await Product.products(for: Array(Self.products.values)), !products.isEmpty else {
            return ["status": "unavailable"]
        }
        var offers: [[String: Any]] = []
        for (id, productId) in Self.products.sorted(by: { $0.key < $1.key }) {
            guard let product = products.first(where: { $0.id == productId }) else { continue }
            var offer: [String: Any] = ["id": id, "price": product.displayPrice]
            offer["trial"] = await Self.freeTrial(of: product)
            offers.append(offer)
        }
        return offers.isEmpty ? ["status": "unavailable"] : ["status": "offers", "offers": offers]
    }

    /// An ISO period, as Android gives the page, and only if Apple says this
    /// Apple Account may still have it.
    private static func freeTrial(of product: Product) async -> String? {
        guard let subscription = product.subscription,
              let offer = subscription.introductoryOffer,
              offer.paymentMode == .freeTrial,
              await subscription.isEligibleForIntroOffer else { return nil }
        let unit: String
        switch offer.period.unit {
        case .day: unit = "D"
        case .week: unit = "W"
        case .month: unit = "M"
        case .year: unit = "Y"
        @unknown default: return nil
        }
        return "P\(offer.period.value)\(unit)"
    }

    func purchase(offer: String) async -> [String: Any] {
        guard let productId = Self.products[offer],
              let product = try? await Product.products(for: [productId]).first else {
            return ["status": "unavailable"]
        }
        do {
            switch try await product.purchase() {
            case .success(.verified(let transaction)):
                await transaction.finish()
                await refresh()
                return ["status": "active"]
            case .success(.unverified):
                return ["status": "error"]
            case .pending:
                return ["status": "pending"]
            case .userCancelled:
                return ["status": "cancelled"]
            @unknown default:
                return ["status": "error"]
            }
        } catch {
            return ["status": "error"]
        }
    }

    /// Asks Apple directly, which may show a sign-in. Only for the Restore button.
    func restore() async -> String {
        do {
            try await AppStore.sync()
            return await refresh()
        } catch StoreKitError.userCancelled {
            return await refresh()
        } catch {
            return "error"
        }
    }

    @MainActor
    func manage(from view: UIView?) async {
        guard let scene = view?.window?.windowScene else { return }
        try? await AppStore.showManageSubscriptions(in: scene)
    }
}
