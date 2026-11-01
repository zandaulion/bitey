import UIKit
import UniformTypeIdentifiers

/// Lets the user choose where a backup goes, or which one to restore, through
/// Files. The page never sees a path, as on Android.
final class BackupPicker: NSObject, UIDocumentPickerDelegate {
    private weak var presenter: UIViewController?
    private var finish: ((URL?) -> Void)?

    init(presenter: UIViewController?) {
        self.presenter = presenter
    }

    func save(_ file: URL, finish: @escaping (URL?) -> Void) {
        show(UIDocumentPickerViewController(forExporting: [file], asCopy: true), finish)
    }

    func open(finish: @escaping (URL?) -> Void) {
        show(UIDocumentPickerViewController(forOpeningContentTypes: [.zip], asCopy: true), finish)
    }

    private func show(_ picker: UIDocumentPickerViewController, _ finish: @escaping (URL?) -> Void) {
        guard self.finish == nil, let presenter else {
            finish(nil)
            return
        }
        self.finish = finish
        picker.delegate = self
        presenter.present(picker, animated: true)
    }

    private func settle(_ url: URL?) {
        let finish = self.finish
        self.finish = nil
        finish?(url)
    }

    func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
        settle(urls.first)
    }

    func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) {
        settle(nil)
    }
}
