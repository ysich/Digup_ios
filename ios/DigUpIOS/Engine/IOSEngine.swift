import DigUpKit
import Foundation
import os

/// Progress the UI can show while the indexer runs. `Indexer.Event` is confined to the indexing queue, so only the
/// values the view needs cross back to the main actor.
struct IndexProgress: Sendable {
    var phase: String
    var done: Int
    var total: Int
    var path: String?

    var fraction: Double {
        guard total > 0 else { return 0 }
        return min(1, max(0, Double(done) / Double(total)))
    }
}

/// The iOS host for DigUpKit. iOS doesn't let an app spawn a helper process, so the model is linked into this process
/// through `LlamaRuntime`.
///
/// One serial queue owns indexing and one owns search. SQLite runs in WAL mode, so the search queue can read while the
/// indexer writes. Each queue owns its own `IndexStore` connection.
final class IOSEngine: @unchecked Sendable {
    let indexDirectory: URL
    let modelsDirectory: URL

    private let indexQueue = DispatchQueue(label: "com.abdurrahim.DigUp.index", qos: .utility)
    private let searchQueue = DispatchQueue(label: "com.abdurrahim.DigUp.search", qos: .userInitiated)
    private var queryEmbedder: LlamaEmbedder?
    private let generation = OSAllocatedUnfairLock(initialState: 0)

    init() {
        modelsDirectory = ModelFiles.installed
        indexDirectory = AppPaths.defaultIndex
    }

    /// Reconciles the index with `folders` and, when `photos` is on, the Photos library, then embeds whatever is
    /// pending. Security-scoped access is started here for the duration of the run; the app also keeps a session-wide
    /// access for previews.
    func index(folders: [URL], photos: Bool, downloadsICloud: Bool,
               progress: @escaping @Sendable (IndexProgress) -> Void,
               completion: @escaping @Sendable (Result<Void, Error>) -> Void) {
        indexQueue.async {
            let scoped = folders.filter { $0.startAccessingSecurityScopedResource() }
            defer { scoped.forEach { $0.stopAccessingSecurityScopedResource() } }

            do {
                let store = try IndexStore(directory: self.indexDirectory)
                let photosInScope = photos && PhotoLibrary.authorized
                store.scope = folders.map { $0.standardizedFileURL.path }
                    + (photosInScope ? [PhotoPath.prefix] : [])

                var options = IndexOptions()
                options.includeCode = false
                options.downloadICloudPhotos = downloadsICloud
                let models = try LlamaModels.locate()
                let indexer = try Indexer(store: store, options: options) {
                    try LlamaEmbedder(models: models, textOnly: false)
                }
                indexer.embedderSource = LlamaEmbedder.modelInfo.vectorSource
                // A model or settings change is a new index, not an error: this is a fresh iOS install, and the model
                // is pinned in ModelFiles.
                indexer.resetOnModelChange = { _, _ in }

                var summary = try indexer.sync(folders)
                if photosInScope {
                    progress(IndexProgress(phase: "Reading the Photos library", done: 0, total: 0, path: nil))
                    let photoSummary = try indexer.syncPhotos(PhotoLibrary.candidates())
                    summary.added += photoSummary.added
                    summary.changed += photoSummary.changed
                    summary.removed += photoSummary.removed
                    summary.unchanged += photoSummary.unchanged
                    summary.queued += photoSummary.queued
                }
                progress(IndexProgress(phase: "Indexing", done: 0, total: summary.queued.count, path: nil))
                _ = try indexer.run { event in
                    progress(IndexProgress(phase: "Indexing", done: event.done, total: event.total, path: event.path))
                }
                _ = try indexer.finishLongFiles { step in
                    progress(IndexProgress(phase: "Finishing long files", done: step.done, total: step.total,
                                           path: step.path))
                }
                indexer.finish()
                completion(.success(()))
            } catch {
                completion(.failure(error))
            }
        }
    }

    /// Embeds the query with the text-only model and asks the hybrid searcher for the best segments. The text model is
    /// kept alive between searches (about 310 MB) and can be freed with `closeSearchModel()`.
    func search(_ text: String, roots: [URL], photos: Bool,
                completion: @escaping @Sendable (Result<[SearchHit], Error>) -> Void) {
        let query = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !query.isEmpty else { completion(.success([])); return }

        let thisGeneration = generation.withLock { value in
            value += 1
            return value
        }
        searchQueue.async {
            let scoped = roots.filter { $0.startAccessingSecurityScopedResource() }
            defer { scoped.forEach { $0.stopAccessingSecurityScopedResource() } }

            do {
                let store = try IndexStore(directory: self.indexDirectory)
                store.scope = roots.map { $0.standardizedFileURL.path } + (photos ? [PhotoPath.prefix] : [])
                let searcher = try Searcher(store: store, loadVectors: false)

                let embedder: LlamaEmbedder
                if let existing = self.queryEmbedder {
                    embedder = existing
                } else {
                    let made = try LlamaEmbedder(models: try LlamaModels.locate(), textOnly: true)
                    self.queryEmbedder = made
                    embedder = made
                }
                let vector = try embedder.embedTexts([Prompts.query(query)]).first
                let hits = try searcher.search(query, queryVector: vector, limit: 30)
                // A newer query already took over: don't show this answer.
                guard thisGeneration == self.generation.withLock({ $0 }) else { return }
                completion(.success(hits))
            } catch {
                guard thisGeneration == self.generation.withLock({ $0 }) else { return }
                completion(.failure(error))
            }
        }
    }

    func closeSearchModel() {
        searchQueue.async {
            self.queryEmbedder?.close()
            self.queryEmbedder = nil
        }
    }

    /// Makes any search already running drop its result (the query was cleared, or a newer one replaced it).
    func cancelSearches() {
        generation.withLock { $0 += 1 }
    }

    func resetIndex() {
        generation.withLock { $0 += 1 }
        indexQueue.async {
            try? FileManager.default.removeItem(at: self.indexDirectory)
            self.searchQueue.async {
                self.queryEmbedder?.close()
                self.queryEmbedder = nil
            }
        }
    }

    /// Drops the Photos part of the index (the switch was turned off), and the query model's vectors with it.
    func deletePhotos() {
        generation.withLock { $0 += 1 }
        indexQueue.async {
            do {
                let store = try IndexStore(directory: self.indexDirectory)
                try store.deletePhotoFiles()
            } catch {
                // Nothing to report: the next sync rebuilds whatever is missing, and the user's switch stays off.
            }
            self.searchQueue.async {
                self.queryEmbedder?.close()
                self.queryEmbedder = nil
            }
        }
    }

    var indexSize: Int64 {
        IndexStore.size(of: indexDirectory.appendingPathComponent(IndexStore.mainFile))
    }
}
