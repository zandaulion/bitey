import FirebaseAppCheck
import FirebaseCore
import Foundation

/// Sends one meal to Bitey AI, the counterpart of BiteyAnalysis.kt. The server asks
/// Apple about the subscription before anything reaches the model.
///
/// Nothing else leaves the device: the body is rebuilt here from an allow-list, so
/// a field added to the page's request cannot quietly start being sent.
enum AiAnalysis {
    private static let endpoint = URL(string: "https://europe-west1-plate-cc703.cloudfunctions.net/analyse")!
    private static let maxImageChars = 7 * 1024 * 1024
    private static let maxDescriptionChars = 1000
    private static let maxLeftoverItems = 40
    private static let diets: Set<String> = ["vegetarian", "vegan", "pescatarian"]

    /// Before Firebase, in the app's start-up. A build without the config file
    /// skips this, and the server then refuses its requests, as it does on Android.
    static func configure() {
        guard Bundle.main.url(forResource: "GoogleService-Info", withExtension: "plist") != nil else { return }
        AppCheck.setAppCheckProviderFactory(AttestFactory())
        FirebaseApp.configure()
    }

    private final class AttestFactory: NSObject, AppCheckProviderFactory {
        func createProvider(with app: FirebaseApp) -> AppCheckProvider? {
            AppAttestProvider(app: app)
        }
    }

    private static func appCheckToken() async -> String? {
        guard FirebaseApp.app() != nil else { return nil }
        return try? await AppCheck.appCheck().token(forcingRefresh: false).token
    }

    /// Always answers `{"httpStatus": n, "body": {...}}`: the page is waiting on this,
    /// and a thrown error would leave its photo button dead.
    static func analyse(pageRequest: String) async -> String {
        guard let token = AiPurchases.transactionId else {
            return answer(403, "not_entitled", "Bitey AI is not active on this Apple Account.")
        }
        guard let data = pageRequest.data(using: .utf8),
              let request = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else {
            return answer(400, "no_image", "No photo was received.")
        }
        let body: [String: Any]
        switch allowed(from: request, token: token) {
        case .success(let built): body = built
        case .failure(let problem): return answer(problem.status, problem.code, problem.message)
        }

        var urlRequest = URLRequest(url: endpoint, timeoutInterval: 95)
        urlRequest.httpMethod = "POST"
        urlRequest.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
        urlRequest.setValue("application/json", forHTTPHeaderField: "Accept")
        // Identifies the software, never the person or the device.
        urlRequest.setValue("Bitey-iOS (meal analysis)", forHTTPHeaderField: "User-Agent")
        if let appCheck = await appCheckToken() {
            urlRequest.setValue(appCheck, forHTTPHeaderField: "X-Firebase-AppCheck")
        }
        guard let payload = try? JSONSerialization.data(withJSONObject: body) else {
            return answer(400, "no_image", "No photo was received.")
        }
        urlRequest.httpBody = payload

        do {
            let (reply, response) = try await URLSession.shared.data(for: urlRequest)
            let status = (response as? HTTPURLResponse)?.statusCode ?? 0
            // A proxy can answer with HTML. The page needs a shape it understands.
            let parsed = (try? JSONSerialization.jsonObject(with: reply)) as? [String: Any] ?? [
                "error": (200..<300).contains(status) ? "unreadable" : "upstream_error",
                "message": "The analysis could not be read. Try again.",
            ]
            return json(["httpStatus": status, "body": parsed])
        } catch {
            return answer(0, "network", "Could not reach Bitey AI. Check the connection and try again.")
        }
    }

    private struct Problem: Error {
        let status: Int
        let code: String
        let message: String
    }

    private static func allowed(from request: [String: Any], token: String) -> Result<[String: Any], Problem> {
        var body: [String: Any] = ["purchaseToken": token, "platform": "ios"]
        let locale = request["locale"] as? String ?? ""
        if locale.range(of: "^[a-z]{2}(-[A-Za-z]{2})?$", options: .regularExpression) != nil {
            body["locale"] = locale
        }

        if request["mode"] as? String == "text" {
            let description = (request["description"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            guard !description.isEmpty else {
                return .failure(Problem(status: 400, code: "no_description", message: "Describe what you ate first."))
            }
            guard description.count <= maxDescriptionChars else {
                return .failure(Problem(status: 400, code: "description_too_long", message: "Keep the meal description to 1,000 characters."))
            }
            body["mode"] = "text"
            body["description"] = description
            return .success(body)
        }

        let image = request["image"] as? String ?? ""
        guard image.count >= 100 else { return .failure(Problem(status: 400, code: "no_image", message: "No photo was received.")) }
        guard image.count <= maxImageChars else {
            return .failure(Problem(status: 400, code: "image_too_large", message: "That photo is too large to read."))
        }
        body["image"] = image
        body["mimeType"] = request["mimeType"] as? String == "image/png" ? "image/png" : "image/jpeg"
        let correction = (request["correction"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        if !correction.isEmpty { body["correction"] = String(correction.prefix(200)) }
        // The diet consent covers photos only, not descriptions.
        if let diet = request["diet"] as? String, diets.contains(diet) { body["diet"] = diet }

        // Leftovers: the photo just taken is `image`, and the meal's original photo
        // and its served foods travel with it. Only those.
        if request["mode"] as? String == "leftovers" {
            let original = request["original"] as? String ?? ""
            guard original.count >= 100 else {
                return .failure(Problem(status: 400, code: "no_original", message: "The photo of the meal before eating was not received."))
            }
            guard original.count <= maxImageChars else {
                return .failure(Problem(status: 400, code: "image_too_large", message: "That photo is too large to read."))
            }
            let served = (request["items"] as? [[String: Any]] ?? []).prefix(maxLeftoverItems)
            body["mode"] = "leftovers"
            body["original"] = original
            body["originalMimeType"] = request["originalMimeType"] as? String == "image/png" ? "image/png" : "image/jpeg"
            body["items"] = served.map { item -> [String: Any] in
                [
                    "id": String((item["id"] as? String ?? "").prefix(64)),
                    "name": String((item["name"] as? String ?? "").prefix(80)),
                    "grams": (item["grams"] as? NSNumber)?.doubleValue ?? 0,
                ]
            }
        }
        return .success(body)
    }

    private static func answer(_ status: Int, _ code: String, _ message: String) -> String {
        json(["httpStatus": status, "body": ["error": code, "message": message]])
    }

    private static func json(_ value: [String: Any]) -> String {
        let data = try? JSONSerialization.data(withJSONObject: value)
        return data.flatMap { String(data: $0, encoding: .utf8) } ?? "{}"
    }
}
