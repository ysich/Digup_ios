import DigUpKit
import Foundation
import Observation

struct PreviewTarget: Identifiable {
    let id: Int64
    let hit: SearchHit
    let fileURL: URL
}

@MainActor
@Observable
final class AppModel {
    var folders: [URL]
    var photosEnabled: Bool {
        didSet { UserDefaults.standard.set(photosEnabled, forKey: Self.photosKey) }
    }
    /// Whether Photos may fetch originals from iCloud while indexing. Off: the library is indexed from what's on the
    /// iPhone, and iCloud-only items are left out.
    var downloadsICloudPhotos: Bool {
        didSet { UserDefaults.standard.set(downloadsICloudPhotos, forKey: Self.iCloudKey) }
    }
    var modelReady: Bool
    var isDownloading = false
    var downloadProgress = 0.0
    var isIndexing = false
    var indexProgress: IndexProgress?
    var results: [SearchHit] = []
    var isSearching = false
    var errorMessage: String?
    var previewTarget: PreviewTarget?
    var showSettings = false
    var showFolderPicker = false

    private let engine = IOSEngine()
    private let bookmarks = FolderBookmarks()
    private var searchTask: Task<Void, Never>?
    private static let photosKey = "DigUp.photosEnabled"
    private static let iCloudKey = "DigUp.downloadsICloudPhotos"

    init() {
        folders = bookmarks.restore()
        let wantedPhotos = UserDefaults.standard.bool(forKey: Self.photosKey)
        photosEnabled = wantedPhotos && PhotoLibrary.authorized
        downloadsICloudPhotos = UserDefaults.standard.bool(forKey: Self.iCloudKey)
        modelReady = ModelFiles.available != nil
        // Access can be taken away while DigUp isn't running; don't leave the old library rows searchable.
        if wantedPhotos && !photosEnabled {
            UserDefaults.standard.set(false, forKey: Self.photosKey)
            engine.deletePhotos()
        }
        // Keep the folder grants for the whole session, so QuickLook and the media players can read a result after the
        // indexer stopped. The engine starts and stops its own balanced grants around each run.
        for folder in folders { _ = folder.startAccessingSecurityScopedResource() }
    }

    var indexSizeText: String {
        let bytes = engine.indexSize
        guard bytes > 0 else { return "No index yet" }
        return ByteCountFormatter.string(fromByteCount: bytes, countStyle: .file)
    }

    /// Anything to search: chosen folders, the Photos library, or both.
    var hasSource: Bool {
        !folders.isEmpty || photosEnabled
    }

    func downloadModel() {
        guard !isDownloading, !modelReady else { return }
        isDownloading = true
        downloadProgress = 0
        errorMessage = nil

        Task {
            do {
                try await ModelDownloader(folder: ModelFiles.installed).download { [weak self] fraction in
                    Task { @MainActor in self?.downloadProgress = fraction }
                }
                modelReady = ModelFiles.available != nil
                if modelReady { startIndexing() }
            } catch {
                errorMessage = error.localizedDescription
            }
            isDownloading = false
        }
    }

    func addFolders(_ urls: [URL]) {
        for url in urls {
            _ = url.startAccessingSecurityScopedResource()
            bookmarks.save(url)
            if !folders.contains(where: { $0.path == url.path }) {
                folders.append(url)
            }
        }
        startIndexing()
    }

    /// Turns the Photos library on (asking for read access the first time) or off, and reindexes either way.
    func setPhotos(_ on: Bool) {
        guard on != photosEnabled else { return }
        guard on else {
            photosEnabled = false
            engine.deletePhotos()
            results = []
            return
        }
        Task {
            guard await PhotoLibrary.requestAccess() else {
                errorMessage = "DigUp can read Photos only with permission. Allow it in Settings › Privacy & Security › Photos."
                photosEnabled = false
                return
            }
            photosEnabled = true
            startIndexing()
        }
    }

    /// Lets PhotoKit fetch iCloud originals while indexing. Turning it on reindexes, so the items skipped while it was
    /// off are picked up.
    func setDownloadsICloud(_ on: Bool) {
        guard on != downloadsICloudPhotos else { return }
        downloadsICloudPhotos = on
        if on, photosEnabled { startIndexing() }
    }

    func startIndexing() {
        guard modelReady, hasSource, !isIndexing else { return }
        isIndexing = true
        errorMessage = nil
        indexProgress = IndexProgress(phase: "Scanning", done: 0, total: 0, path: nil)

        engine.index(folders: folders, photos: photosEnabled,
                     downloadsICloud: downloadsICloudPhotos) { [weak self] progress in
            Task { @MainActor in self?.indexProgress = progress }
        } completion: { [weak self] result in
            Task { @MainActor in
                guard let self else { return }
                self.isIndexing = false
                switch result {
                case .success:
                    self.indexProgress = nil
                case .failure(let error):
                    self.errorMessage = error.localizedDescription
                }
            }
        }
    }

    func search(_ text: String) {
        searchTask?.cancel()
        let query = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !query.isEmpty else {
            results = []
            isSearching = false
            engine.cancelSearches()
            return
        }

        searchTask = Task {
            try? await Task.sleep(for: .milliseconds(250))
            guard !Task.isCancelled else { return }
            isSearching = true
            engine.search(query, roots: folders, photos: photosEnabled) { [weak self] result in
                Task { @MainActor in
                    guard let self else { return }
                    self.isSearching = false
                    switch result {
                    case .success(let hits): self.results = hits
                    case .failure(let error): self.errorMessage = error.localizedDescription
                    }
                }
            }
        }
    }

    func showPreview(_ hit: SearchHit) {
        guard PhotoPath.owns(hit.path) else {
            previewTarget = PreviewTarget(id: hit.segmentID, hit: hit, fileURL: URL(fileURLWithPath: hit.path))
            return
        }
        Task {
            do {
                let allowNetwork = downloadsICloudPhotos
                let file = try await Task.detached {
                    try PhotoLibrary.previewFile(for: hit.path, allowNetwork: allowNetwork)
                }.value
                previewTarget = PreviewTarget(id: hit.segmentID, hit: hit, fileURL: file)
            } catch {
                errorMessage = "That item can't be opened: \(error)"
            }
        }
    }

    func clearIndex() {
        engine.resetIndex()
        results = []
        indexProgress = nil
    }

    func rebuildIndex() {
        clearIndex()
        startIndexing()
    }

    func dismissError() {
        errorMessage = nil
    }
}
