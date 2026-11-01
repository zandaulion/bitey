import ImageIO
import PhotosUI
import UIKit
import UniformTypeIdentifiers

/// Opens the camera or the photo picker for the page. As on Android, the bytes
/// stay in app-private cache and the page fetches them by path, so a photo never
/// crosses the bridge. A capture the page never collected is deleted when the
/// next one begins.
final class PhotoCapture: NSObject {
    private static let maxBytes = 5 * 1024 * 1024
    private static let maxEdge = 2560

    static let directory = FileManager.default.temporaryDirectory
        .appendingPathComponent("captures", isDirectory: true)

    private weak var presenter: UIViewController?
    private var finish: ((String?) -> Void)?

    init(presenter: UIViewController?) {
        self.presenter = presenter
    }

    /// Answers exactly once, with a capture id or nil: an unanswered request
    /// would leave the photo button dead until the app restarted.
    func capture(source: String, finish: @escaping (String?) -> Void) {
        guard self.finish == nil, let presenter else {
            finish(nil)
            return
        }
        self.finish = finish
        #if DEBUG
        // Apple's picker runs in a protected process that UI tests cannot touch.
        if let path = ProcessInfo.processInfo.environment["BITEY_TEST_PHOTO"] {
            settle((try? Data(contentsOf: URL(fileURLWithPath: path))).flatMap(Self.stage))
            return
        }
        #endif
        if source == "gallery" {
            var configuration = PHPickerConfiguration()
            configuration.filter = .images
            configuration.selectionLimit = 1
            // HEIC arrives as JPEG, the format the rest of Bitey expects.
            configuration.preferredAssetRepresentationMode = .compatible
            let picker = PHPickerViewController(configuration: configuration)
            picker.delegate = self
            presenter.present(picker, animated: true)
        } else if UIImagePickerController.isSourceTypeAvailable(.camera) {
            let camera = UIImagePickerController()
            camera.sourceType = .camera
            camera.delegate = self
            presenter.present(camera, animated: true)
        } else {
            settle(nil)
        }
    }

    private func settle(_ id: String?) {
        let finish = self.finish
        self.finish = nil
        DispatchQueue.main.async { finish?(id) }
    }

    private static func stage(_ data: Data) -> String? {
        guard let bytes = fitted(data) else { return nil }
        let fileManager = FileManager.default
        try? fileManager.removeItem(at: directory)
        try? fileManager.createDirectory(at: directory, withIntermediateDirectories: true)
        let id = UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased() + ".jpg"
        guard (try? bytes.write(to: directory.appendingPathComponent(id))) != nil else { return nil }
        return id
    }

    /// iPhone photos can pass Bitey's 5 MB limit, where Android's rarely do, so a
    /// large one is shrunk rather than refused. Metadata stays, including the
    /// capture date the diary offers.
    private static func fitted(_ data: Data) -> Data? {
        if !data.isEmpty, data.count <= maxBytes { return data }
        guard let source = CGImageSourceCreateWithData(data as CFData, nil),
              let image = CGImageSourceCreateThumbnailAtIndex(source, 0, [
                  kCGImageSourceCreateThumbnailFromImageAlways: true,
                  kCGImageSourceCreateThumbnailWithTransform: true,
                  kCGImageSourceThumbnailMaxPixelSize: maxEdge,
              ] as CFDictionary) else { return nil }
        let output = NSMutableData()
        guard let target = CGImageDestinationCreateWithData(output, UTType.jpeg.identifier as CFString, 1, nil) else {
            return nil
        }
        var properties = (CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any]) ?? [:]
        // The pixels are already upright, so the old orientation would turn them.
        properties[kCGImagePropertyOrientation] = nil
        properties[kCGImageDestinationLossyCompressionQuality] = 0.82
        CGImageDestinationAddImage(target, image, properties as CFDictionary)
        guard CGImageDestinationFinalize(target), output.length <= maxBytes else { return nil }
        return output as Data
    }
}

extension PhotoCapture: PHPickerViewControllerDelegate {
    func picker(_ picker: PHPickerViewController, didFinishPicking results: [PHPickerResult]) {
        picker.dismiss(animated: true)
        guard let provider = results.first?.itemProvider else {
            settle(nil)
            return
        }
        provider.loadFileRepresentation(forTypeIdentifier: UTType.jpeg.identifier) { [weak self] url, _ in
            // The file only exists until this closure returns.
            let id = url.flatMap { try? Data(contentsOf: $0) }.flatMap(Self.stage)
            self?.settle(id)
        }
    }
}

extension PhotoCapture: UIImagePickerControllerDelegate, UINavigationControllerDelegate {
    func imagePickerController(
        _ camera: UIImagePickerController,
        didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]
    ) {
        camera.dismiss(animated: true)
        let image = info[.originalImage] as? UIImage
        settle(image?.jpegData(compressionQuality: 0.9).flatMap(Self.stage))
    }

    func imagePickerControllerDidCancel(_ camera: UIImagePickerController) {
        camera.dismiss(animated: true)
        settle(nil)
    }
}
