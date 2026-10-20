import DigUpKit
import Foundation
import SwiftUI

struct ResultRow: View {
    let hit: SearchHit

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: icon)
                .font(.title3)
                .foregroundStyle(.tint)
                .frame(width: 28)

            VStack(alignment: .leading, spacing: 3) {
                Text(fileName)
                    .font(.headline)
                    .lineLimit(1)
                Text(folderName)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
                if let excerpt = hit.excerpt, !excerpt.isEmpty {
                    Text(excerpt)
                        .font(.subheadline)
                        .foregroundStyle(.primary)
                        .lineLimit(3)
                }
            }

            Spacer(minLength: 4)

            if let location {
                Text(location)
                    .font(.caption2.monospacedDigit())
                    .foregroundStyle(.secondary)
            }
        }
        .padding(.vertical, 4)
        .contentShape(Rectangle())
    }

    private var fileName: String {
        URL(fileURLWithPath: hit.path).lastPathComponent
    }

    private var folderName: String {
        let folder = URL(fileURLWithPath: hit.path).deletingLastPathComponent()
        return folder.lastPathComponent.isEmpty ? folder.path : folder.lastPathComponent
    }

    private var icon: String {
        switch hit.kind {
        case .screenshot: "camera.viewfinder"
        case .image: "photo"
        case .pdf: "doc.richtext"
        case .doc: "doc.text"
        case .audio: "waveform"
        case .video: "film"
        case .code: "chevron.left.forwardslash.chevron.right"
        }
    }

    private var location: String? {
        switch hit.kind {
        case .pdf:
            hit.loc.map { "p. \(Int($0))" }
        case .audio, .video:
            hit.loc.map(time)
        default:
            nil
        }
    }

    private func time(_ seconds: Double) -> String {
        let total = Int(seconds.rounded())
        return String(format: "%d:%02d", total / 60, total % 60)
    }
}
