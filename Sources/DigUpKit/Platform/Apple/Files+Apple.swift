// File kinds, the file system and metadata on iOS: Uniform Type Identifiers and iCloud placeholders.
#if canImport(UniformTypeIdentifiers)
import CoreGraphics
import Foundation
import UniformTypeIdentifiers

extension Rules {
    public static func classify(_ url: URL, type: UTType?, options: IndexOptions) -> Classification {
        let ext = url.pathExtension.lowercased()
        if options.excludedExtensions.contains(ext) { return .skip("excluded type") }
        // .ts / .mts are usually TypeScript, which Spotlight calls MPEG-2 video. Only a real transport stream is video.
        if ext == "ts" || ext == "mts" || ext == "m2ts" {
            if isMPEGTransportStream(url) { return .index(.video) }
            return options.includeCode ? .index(.doc) : .skip("code or data file")
        }
        if docExtensions.contains(ext) { return .index(.doc) }
        if codeExtensions.contains(ext) { return options.includeCode ? .index(.doc) : .skip("code or data file") }
        guard let type else { return .ignore }
        if type.conforms(to: .sourceCode) || type.conforms(to: .script) {
            return options.includeCode ? .index(.doc) : .skip("code or data file")
        }
        if type.conforms(to: .pdf) { return .index(.pdf) }
        if type.conforms(to: .image) { return .index(isScreenshot(url) ? .screenshot : .image) }
        if type.conforms(to: .movie) { return .index(.video) }
        if type.conforms(to: .audio) { return .index(.audio) }
        return .ignore
    }

    /// What the crawl asks, with the type among the file's resource values.
    static func classify(_ url: URL, values: URLResourceValues?, options: IndexOptions) -> Classification {
        classify(url, type: values?.contentType, options: options)
    }

    /// iOS screenshots are identified by Photos (PHAsset.mediaSubtypes), not by an xattr on a file.
    static func hasScreenshotMarker(_ url: URL) -> Bool {
        return false
    }
}

enum FileSystem {
    /// What the crawl asks of each URL.
    static let crawlKeys: [URLResourceKey] = [.isDirectoryKey, .isPackageKey, .contentTypeKey, .isUbiquitousItemKey,
                                              .ubiquitousItemDownloadingStatusKey]

    /// An app, a framework, a library bundle: one thing to the Finder, skipped whole.
    static func isPackage(_ url: URL, _ values: URLResourceValues?) -> Bool {
        values?.isPackage == true
    }

    /// An iCloud file that isn't downloaded (reading it would download it).
    static func isInCloud(_ values: URLResourceValues?) -> Bool {
        values?.isUbiquitousItem == true && values?.ubiquitousItemDownloadingStatus != .current
    }

    /// The file's own facts, without following a link. `dataless`: a placeholder ("Optimize Mac Storage", File Providers)
    /// whose contents would be downloaded on read.
    static func info(of path: String) -> FileInfo? {
        var info = stat()
        guard lstat(path, &info) == 0 else { return nil }
        return FileInfo(isRegular: (info.st_mode & S_IFMT) == S_IFREG, size: Int64(info.st_size),
                        mtime: Double(info.st_mtimespec.tv_sec) + Double(info.st_mtimespec.tv_nsec) / 1e9,
                        inode: UInt64(info.st_ino), device: UInt64(bitPattern: Int64(info.st_dev)),
                        dataless: false)
    }
}

extension Estimator {
    static func pages(of path: String) -> Int {
        return CGPDFDocument(URL(fileURLWithPath: path) as CFURL)?.numberOfPages ?? 1
    }

    static func duration(of path: String) -> Double {
        return MediaInfo.duration(of: URL(fileURLWithPath: path)) ?? 0
    }
}
#endif
