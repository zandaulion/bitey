import Foundation

/// A direct request from the device to Open Food Facts, made only after the page
/// has asked for consent. Nothing identifies the person or the device.
enum OpenFoodFacts {
    private static let fields = [
        "code", "product_name", "product_name_en", "product_name_ro",
        "product_name_de", "product_name_fr", "product_name_es", "product_name_pt",
        "product_name_uk", "product_name_ar", "product_name_hi", "product_name_ja",
        "product_name_ko", "product_name_zh",
        "brands", "quantity", "serving_size", "nutriments",
    ].joined(separator: ",")

    private static let session: URLSession = {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = 10
        configuration.timeoutIntervalForResource = 20
        configuration.httpAdditionalHeaders = [
            "User-Agent": "Bitey-iOS/1.0.0 (anonymous device lookup)",
            "Accept": "application/json",
        ]
        return URLSession(configuration: configuration)
    }()

    private static func failure(_ code: String, _ message: String) -> String {
        let data = try? JSONSerialization.data(withJSONObject: ["ok": false, "code": code, "message": message])
        return data.flatMap { String(data: $0, encoding: .utf8) } ?? "{}"
    }

    static func lookup(barcode: String, finish: @escaping (String) -> Void) {
        let networkError = failure("network_error", "Could not contact Open Food Facts. Try again or enter it manually.")
        // Only barcode-shaped values can become a request URL.
        guard barcode.range(of: "^[0-9]{8,14}$", options: .regularExpression) != nil,
              let url = URL(string: "https://world.openfoodfacts.org/api/v2/product/\(barcode).json?fields=\(fields)") else {
            finish(failure("invalid_barcode", "This does not look like a food barcode. Enter it manually instead."))
            return
        }
        session.dataTask(with: url) { data, response, _ in
            let status = (response as? HTTPURLResponse)?.statusCode ?? 0
            // A 404 is an answer, not an outage.
            guard status != 404 else {
                finish(failure("not_found", "That barcode is not in Open Food Facts yet. Enter it manually instead."))
                return
            }
            guard (200..<300).contains(status), let data,
                  let body = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else {
                finish(networkError)
                return
            }
            finish(answer(body) ?? networkError)
        }.resume()
    }

    private static func answer(_ body: [String: Any]) -> String? {
        guard (body["status"] as? NSNumber)?.intValue == 1, let product = body["product"] as? [String: Any] else {
            return failure("not_found", "That barcode is not in Open Food Facts yet. Enter it manually instead.")
        }
        let data = try? JSONSerialization.data(withJSONObject: ["ok": true, "product": product])
        return data.flatMap { String(data: $0, encoding: .utf8) }
    }
}
