import DigUpKit
import Foundation

enum ModelDownloadError: LocalizedError {
    case failed(String)
    case checksum(String)

    var errorDescription: String? {
        switch self {
        case .failed(let message): message
        case .checksum(let name): "\(name) didn't pass its checksum. Delete it and download again."
        }
    }
}

/// Downloads the two Q8_0 GGUF files (865 MB) with DigUpKit's resumable `FileFetcher`. A file is written to
/// `<name>.partial` and only gets its real name after its SHA-256 matches `ModelFiles`.
struct ModelDownloader {
    let folder: URL

    func download(progress: @escaping @Sendable (Double) -> Void) async throws {
        if ModelFiles.available != nil {
            progress(1)
            return
        }
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        let total = Double(ModelFiles.totalBytes)
        var completed: Int64 = 0

        for file in ModelFiles.all {
            let destination = folder.appendingPathComponent(file.name)
            if FileFetcher.size(of: destination) == file.bytes {
                completed += file.bytes
                progress(min(1, Double(completed) / total))
                continue
            }

            let already = completed
            let partial = try await fetch(file) { written in
                progress(min(0.999, (Double(already) + Double(written)) / total))
            }

            guard (try? ModelFiles.sha256(of: partial)) == file.sha256 else {
                try? FileManager.default.removeItem(at: partial)
                throw ModelDownloadError.checksum(file.name)
            }
            try? FileManager.default.removeItem(at: destination)
            try FileManager.default.moveItem(at: partial, to: destination)
            completed += file.bytes
            progress(min(1, Double(completed) / total))
        }
    }

    private func fetch(_ file: ModelFiles.File,
                       progress: @escaping @Sendable (Int64) -> Void) async throws -> URL {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<URL, Error>) in
            let fetcher = FileFetcher(file: file, folder: folder, progress: progress, waiting: {},
                                      done: { outcome in
                switch outcome {
                case .downloaded(let url):
                    continuation.resume(returning: url)
                case .failed(let message, _):
                    continuation.resume(throwing: ModelDownloadError.failed(message))
                }
            })
            fetcher.start()
        }
    }
}
