import AVFoundation
import UIKit

/// Reads a barcode on the device, with nothing sent anywhere. The same symbologies
/// as Android: what food packaging carries, plus Code 128 for shop-printed labels.
/// An empty answer means dismissed, which the page treats as nothing to report.
final class BarcodeScannerViewController: UIViewController, AVCaptureMetadataOutputObjectsDelegate {
    private static let formats: [AVMetadataObject.ObjectType] = [.ean13, .ean8, .upce, .code128]

    private let labels: [String: String]
    private let finish: (String) -> Void
    private let session = AVCaptureSession()
    private let queue = DispatchQueue(label: "bitey.barcode")
    private var preview: AVCaptureVideoPreviewLayer?
    private var device: AVCaptureDevice?
    private var finished = false
    private let lightButton = UIButton(type: .system)

    init(labels: [String: String], finish: @escaping (String) -> Void) {
        self.labels = labels
        self.finish = finish
        super.init(nibName: nil, bundle: nil)
        modalPresentationStyle = .fullScreen
    }

    required init?(coder: NSCoder) { nil }

    private func label(_ key: String, _ fallback: String) -> String {
        let value = labels[key] ?? ""
        return value.isEmpty ? fallback : value
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        layOut()
        AVCaptureDevice.requestAccess(for: .video) { [weak self] granted in
            DispatchQueue.main.async {
                guard let self else { return }
                if granted { self.startCamera() } else { self.done("") }
            }
        }
    }

    private func startCamera() {
        guard let camera = AVCaptureDevice.default(for: .video),
              let input = try? AVCaptureDeviceInput(device: camera),
              session.canAddInput(input) else {
            done("")
            return
        }
        device = camera
        session.addInput(input)
        let output = AVCaptureMetadataOutput()
        guard session.canAddOutput(output) else {
            done("")
            return
        }
        session.addOutput(output)
        output.setMetadataObjectsDelegate(self, queue: .main)
        output.metadataObjectTypes = Self.formats.filter(output.availableMetadataObjectTypes.contains)

        let layer = AVCaptureVideoPreviewLayer(session: session)
        layer.videoGravity = .resizeAspectFill
        layer.frame = view.bounds
        view.layer.insertSublayer(layer, at: 0)
        preview = layer
        lightButton.isHidden = !camera.hasTorch
        queue.async { self.session.startRunning() }
    }

    private func layOut() {
        let hint = UILabel()
        hint.text = label("hint", "Hold the barcode about 15 cm away. Tap to focus.")
        hint.textColor = .white
        hint.textAlignment = .center
        hint.numberOfLines = 0
        hint.font = .preferredFont(forTextStyle: .body)

        let cancel = UIButton(type: .system)
        cancel.setTitle(label("cancel", "Cancel"), for: .normal)
        cancel.tintColor = .white
        cancel.addAction(UIAction { [weak self] _ in self?.done("") }, for: .touchUpInside)

        lightButton.setTitle(label("lightOn", "Light"), for: .normal)
        lightButton.tintColor = .white
        lightButton.isHidden = true
        lightButton.addAction(UIAction { [weak self] _ in self?.toggleLight() }, for: .touchUpInside)

        let buttons = UIStackView(arrangedSubviews: [cancel, lightButton])
        buttons.distribution = .fillEqually
        let stack = UIStackView(arrangedSubviews: [hint, buttons])
        stack.axis = .vertical
        stack.spacing = 16
        stack.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(stack)
        NSLayoutConstraint.activate([
            stack.leadingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.leadingAnchor, constant: 20),
            stack.trailingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.trailingAnchor, constant: -20),
            stack.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor, constant: -20),
        ])
        view.addGestureRecognizer(UITapGestureRecognizer(target: self, action: #selector(focus(_:))))
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        preview?.frame = view.bounds
    }

    private func toggleLight() {
        guard let device, device.hasTorch, (try? device.lockForConfiguration()) != nil else { return }
        let on = device.torchMode != .on
        device.torchMode = on ? .on : .off
        device.unlockForConfiguration()
        lightButton.setTitle(on ? label("lightOff", "Light off") : label("lightOn", "Light"), for: .normal)
    }

    @objc private func focus(_ tap: UITapGestureRecognizer) {
        guard let device, let preview, device.isFocusPointOfInterestSupported,
              (try? device.lockForConfiguration()) != nil else { return }
        device.focusPointOfInterest = preview.captureDevicePointConverted(fromLayerPoint: tap.location(in: view))
        device.focusMode = .autoFocus
        device.unlockForConfiguration()
    }

    func metadataOutput(
        _ output: AVCaptureMetadataOutput,
        didOutput objects: [AVMetadataObject],
        from connection: AVCaptureConnection
    ) {
        guard let code = objects.compactMap({ $0 as? AVMetadataMachineReadableCodeObject }).first?.stringValue else { return }
        UINotificationFeedbackGenerator().notificationOccurred(.success)
        done(code)
    }

    private func done(_ code: String) {
        guard !finished else { return }
        finished = true
        queue.async { self.session.stopRunning() }
        dismiss(animated: true) { self.finish(code) }
    }
}
