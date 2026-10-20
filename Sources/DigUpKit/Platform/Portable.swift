// What every platform shares of the platform layer: the shapes the engine asks for, and where DigUp keeps its files.
// Platform/Apple and Platform/Portable implement the same names.
import Foundation

/// A file's own facts, as the crawl records them (`FileSystem.info`).
struct FileInfo {
    let isRegular: Bool   // not a link, device or socket
    let size: Int64
    let mtime: Double     // seconds since 1970, with the fraction the file system keeps
    let inode: UInt64
    let device: UInt64
    /// A cloud placeholder whose contents would be downloaded on read (never read).
    let dataless: Bool
}

extension Data {
    /// Writes the file whole, replacing what was there, so a reader sees the old contents or the new, never half.
    public func writeReplacing(_ url: URL) throws {
        try write(to: url, options: .atomic)
    }
}

/// Where DigUp keeps its own files on each OS: the model and the index.
public enum AppPaths {
    /// The app sandbox's Application Support folder.
    public static var applicationSupport: URL {
        // The sandbox's Application Support folder. `homeDirectoryForCurrentUser` on iOS is the app container, but
        // asking Foundation for the directory is what the platform documents (and survives container moves).
        return FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("DigUp", isDirectory: true)
    }

    /// The default index folder.
    public static var defaultIndex: URL {
        return applicationSupport.appendingPathComponent("index.noindex")
    }
}
