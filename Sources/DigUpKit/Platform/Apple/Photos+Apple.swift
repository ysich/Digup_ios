// The Photos library as an index source. iOS has no folder that lists the camera roll, so an asset is fetched through
// PhotoKit and exported into the indexer's scratch folder just before it's read; that folder is removed with the job.
#if os(iOS) && canImport(Photos)
@preconcurrency import Photos
import Foundation
import UniformTypeIdentifiers

public enum PhotoLibrary {
    /// Whether the user has already let DigUp read the library (full or limited).
    public static var authorized: Bool {
        switch PHPhotoLibrary.authorizationStatus(for: .readOnly) {
        case .authorized, .limited: true
        default: false
        }
    }

    /// Asks for read access once; the system remembers a "no". DigUp never writes to the library.
    public static func requestAccess() async -> Bool {
        let status = await withCheckedContinuation { continuation in
            PHPhotoLibrary.requestAuthorization(for: .readOnly) { continuation.resume(returning: $0) }
        }
        return status == .authorized || status == .limited
    }

    /// Every picture and video in the library, newest first. Metadata only: nothing is exported or downloaded here.
    public static func candidates() -> [Candidate] {
        guard authorized else { return [] }
        let options = PHFetchOptions()
        options.sortDescriptors = [NSSortDescriptor(key: "creationDate", ascending: false)]
        options.includeHiddenAssets = false
        let assets = PHAsset.fetchAssets(with: options)
        var out: [Candidate] = []
        out.reserveCapacity(assets.count)
        assets.enumerateObjects { asset, _, _ in
            guard asset.mediaType == .image || asset.mediaType == .video else { return }
            let kind: FileKind = asset.mediaType == .video
                ? .video
                : (asset.mediaSubtypes.contains(.photoScreenshot) ? .screenshot : .image)
            let path = PhotoPath.path(identifier: asset.localIdentifier, name: name(of: asset))
            out.append(Candidate(path: path, kind: kind, size: 0,
                                 mtime: (asset.modificationDate ?? asset.creationDate)?.timeIntervalSince1970 ?? 0,
                                 inode: hash(path), device: device))
        }
        return out
    }

    /// The item's bytes, written into `folder` for the indexer to read (the folder goes with the job).
    public static func localFile(for path: String, in folder: URL, allowNetwork: Bool) throws -> URL {
        try export(path, into: folder, name: UUID().uuidString, allowNetwork: allowNetwork)
    }

    /// A copy for QuickLook, kept in the caches folder so reopening a result doesn't export it again.
    public static func previewFile(for path: String, allowNetwork: Bool) throws -> URL {
        let folder = FileManager.default.temporaryDirectory
            .appendingPathComponent("DigUp-Photos", isDirectory: true)
            .appendingPathComponent(String(hash(path)), isDirectory: true)
        if let existing = try? FileManager.default.contentsOfDirectory(at: folder, includingPropertiesForKeys: nil).first {
            return existing
        }
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        return try export(path, into: folder, name: "preview", allowNetwork: allowNetwork)
    }

    // MARK: Export

    /// Photos has no file to point at: PhotoKit writes a rendition out, edits and all. The full one comes first; with
    /// `allowNetwork` off, a resource that isn't on the iPhone fails and the next resource is tried.
    private static func export(_ path: String, into folder: URL, name: String, allowNetwork: Bool) throws -> URL {
        guard let identifier = PhotoPath.identifier(path),
              let asset = PHAsset.fetchAssets(withLocalIdentifiers: [identifier], options: nil).firstObject
        else { throw ExtractError.unreadable("that item isn't in the Photos library any more") }
        let candidates = resources(for: asset)
        guard !candidates.isEmpty else {
            throw ExtractError.unreadable("Photos has no readable original for this item")
        }
        var failure: Error = ExtractError.unreadable("Photos has no readable original for this item")
        for resource in candidates {
            let ext = UTType(resource.uniformTypeIdentifier)?.preferredFilenameExtension
                ?? (asset.mediaType == .video ? "mov" : "jpg")
            let file = folder.appendingPathComponent(name).appendingPathExtension(ext)
            try? FileManager.default.removeItem(at: file)
            do {
                try write(resource, to: file, allowNetwork: allowNetwork)
                return file
            } catch {
                failure = error
            }
        }
        throw failure
    }

    /// The full rendition first (downloaded from iCloud when that's allowed), then what the iPhone already has.
    private static func resources(for asset: PHAsset) -> [PHAssetResource] {
        let all = PHAssetResource.assetResources(for: asset)
        let order: [PHAssetResourceType] = asset.mediaType == .video
            ? [.fullSizeVideo, .video]
            : [.fullSizePhoto, .photo, .alternatePhoto]
        var out: [PHAssetResource] = []
        for type in order {
            out += all.filter { $0.type == type }
        }
        out += all.filter { !order.contains($0.type) }
        return out
    }

    /// PhotoKit reports on its own queue. Five minutes is the iCloud patience: a video that hasn't started arriving by
    /// then is marked failed, and the next run tries it again.
    private static func write(_ resource: PHAssetResource, to file: URL, allowNetwork: Bool) throws {
        let options = PHAssetResourceRequestOptions()
        options.isNetworkAccessAllowed = allowNetwork
        let result = ExportResult()
        let done = DispatchSemaphore(value: 0)
        PHAssetResourceManager.default().writeData(for: resource, toFile: file, options: options) { error in
            result.error = error
            done.signal()
        }
        guard done.wait(timeout: .now() + 300) == .success else {
            try? FileManager.default.removeItem(at: file)
            throw ExtractError.unreadable("the original didn't arrive from iCloud in five minutes")
        }
        if let error = result.error {
            try? FileManager.default.removeItem(at: file)
            if !allowNetwork { throw ExtractError.inCloud }
            throw ExtractError.unreadable(error.localizedDescription)
        }
    }

    private final class ExportResult: @unchecked Sendable {
        var error: Error?
    }

    // MARK: Metadata

    private static func name(of asset: PHAsset) -> String {
        PHAssetResource.assetResources(for: asset).first?.originalFilename ?? "Photo"
    }

    /// FNV-1a: a stable number for the index's inode column (and the preview folder), where a real file has one.
    private static func hash(_ text: String) -> UInt64 {
        var value: UInt64 = 0xcbf2_9ce4_8422_2325
        for byte in text.utf8 {
            value = (value ^ UInt64(byte)) &* 0x0000_0100_0000_01b3
        }
        return value
    }

    /// All Photos assets share one "device", so an inode collision can't pair two unrelated entries.
    private static let device: UInt64 = 0x5048_4f54_4f53   // "PHOTOS"
}
#endif
