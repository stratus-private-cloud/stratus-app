import UIKit
import UniformTypeIdentifiers

/// Stratus in the share sheet (stratus-app#128).
///
/// Another process, with a memory limit of its own and no way to call into
/// the app, so all it does is copy what was shared into the App Group and say
/// so. The app takes it in on its next pass -- the moment it is opened, or the
/// system's next background task -- and from there it is a hand upload like
/// any other, decided in shared Kotlin. Swift only, so the extension carries
/// no Kotlin framework at all.
class ShareViewController: UIViewController {

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        Task { await take() }
    }

    private func take() async {
        let providers = (extensionContext?.inputItems as? [NSExtensionItem] ?? [])
            .flatMap { $0.attachments ?? [] }
        var kept = 0
        if let inbox = FileManager.default
            .containerURL(forSecurityApplicationGroupIdentifier: "group.dev.stratus")?
            .appendingPathComponent("Shares") {
            // Renamed only once every file is in it, so the app never takes in
            // half a share.
            let part = inbox.appendingPathComponent(UUID().uuidString + ".part")
            try? FileManager.default.createDirectory(at: part, withIntermediateDirectories: true)
            // A folder per file, since two photographs shared at once are
            // often both called image.jpg.
            for (n, provider) in providers.enumerated() {
                guard let type = fileType(of: provider) else { continue }
                let folder = part.appendingPathComponent(String(n))
                try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
                if await copy(provider, type: type, into: folder) { kept += 1 }
            }
            if kept > 0 {
                try? FileManager.default.moveItem(at: part, to: part.deletingPathExtension())
            } else {
                try? FileManager.default.removeItem(at: part)
            }
        }
        await MainActor.run { say(kept) }
    }

    /// The type to ask a file for, or nil for a link or a piece of text: this
    /// door is for files, as the web's is. A text file has a name of its own,
    /// and that is what tells it from text typed into the sheet.
    private func fileType(of provider: NSItemProvider) -> String? {
        for identifier in provider.registeredTypeIdentifiers {
            guard let type = UTType(identifier) else { continue }
            if type.conforms(to: .url) { return nil }
            if type.conforms(to: .text) && provider.suggestedName == nil { return nil }
            if type.conforms(to: .data) { return identifier }
        }
        return nil
    }

    /// Copied inside the handler, because the file it is handed is deleted
    /// once that returns.
    private func copy(_ provider: NSItemProvider, type: String, into folder: URL) async -> Bool {
        await withCheckedContinuation { done in
            _ = provider.loadFileRepresentation(forTypeIdentifier: type) { url, _ in
                guard let url else { return done.resume(returning: false) }
                let to = folder.appendingPathComponent(url.lastPathComponent)
                done.resume(returning: (try? FileManager.default.copyItem(at: url, to: to)) != nil)
            }
        }
    }

    private func say(_ kept: Int) {
        let message = kept == 0
            ? "Stratus uploads files, not text or links."
            : "Stratus will upload \(kept == 1 ? "it" : "\(kept) files") to shared/ the next time it opens."
        let alert = UIAlertController(title: nil, message: message, preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: "OK", style: .default) { [weak self] _ in
            self?.extensionContext?.completeRequest(returningItems: nil)
        })
        present(alert, animated: true)
    }
}
