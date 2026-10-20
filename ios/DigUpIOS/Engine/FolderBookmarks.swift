import Foundation

/// Security-scoped folder bookmarks. iOS gives an app access to a folder only through the document picker; the bookmark
/// is what lets that access survive a relaunch.
struct FolderBookmarks {
    private let key = "DigUp.indexedFolders"

    func save(_ url: URL) {
        guard let data = try? url.bookmarkData(options: .minimalBookmark,
                                               includingResourceValuesForKeys: nil, relativeTo: nil) else { return }
        var all = UserDefaults.standard.array(forKey: key) as? [Data] ?? []
        if !all.contains(data) {
            all.append(data)
            UserDefaults.standard.set(all, forKey: key)
        }
    }

    func restore() -> [URL] {
        let all = UserDefaults.standard.array(forKey: key) as? [Data] ?? []
        var urls: [URL] = []
        for data in all {
            var stale = false
            guard let url = try? URL(resolvingBookmarkData: data, options: [.withoutUI],
                                     relativeTo: nil, bookmarkDataIsStale: &stale) else { continue }
            if !urls.contains(where: { $0.path == url.path }) { urls.append(url) }
            if stale { save(url) }
        }
        return urls
    }

    func clear() {
        UserDefaults.standard.removeObject(forKey: key)
    }
}
