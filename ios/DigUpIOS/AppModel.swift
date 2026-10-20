import DigUpKit
import Foundation
import Observation

struct PreviewTarget: Identifiable {
    let id: Int64
    let hit: SearchHit
}

@MainActor
@Observable
final class AppModel {
    var folders: [URL]
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

    init() {
        folders = bookmarks.restore()
        modelReady = ModelFiles.missing(in: ModelFiles.installed).isEmpty
        // Keep the folder grants for the whole session, so QuickLook and the media players can read a result after the
        // indexer stopped. The engine starts and stops its own balanced grants around each run.
        for folder in folders { _ = folder.startAccessingSecurityScopedResource() }
    }

    var indexSizeText: String {
        let bytes = engine.indexSize
        guard bytes > 0 else { return "No index yet" }
        return ByteCountFormatter.string(fromByteCount: bytes, countStyle: .file)
    }

    func downloadModel() {
        guard !isDownloading else { return }
        isDownloading = true
        downloadProgress = 0
        errorMessage = nil

        Task {
            do {
                try await ModelDownloader(folder: ModelFiles.installed).download { [weak self] fraction in
                    Task { @MainActor in self?.downloadProgress = fraction }
                }
                modelReady = ModelFiles.missing(in: ModelFiles.installed).isEmpty
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

    func startIndexing() {
        guard modelReady, !folders.isEmpty, !isIndexing else { return }
        isIndexing = true
        errorMessage = nil
        indexProgress = IndexProgress(phase: "Scanning", done: 0, total: 0, path: nil)

        engine.index(folders: folders) { [weak self] progress in
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
            engine.search(query, roots: folders) { [weak self] result in
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
        previewTarget = PreviewTarget(id: hit.segmentID, hit: hit)
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
