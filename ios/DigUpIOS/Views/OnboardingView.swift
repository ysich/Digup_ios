import DigUpKit
import SwiftUI
import UniformTypeIdentifiers

struct OnboardingView: View {
    @Environment(AppModel.self) private var model
    @State private var importing = false

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 28) {
                    VStack(alignment: .leading, spacing: 8) {
                        Text("DigUp")
                            .font(.largeTitle.bold())
                        Text("Describe it. Dig it up.")
                            .font(.title3)
                            .foregroundStyle(.secondary)
                        Text("Search the files on this iPhone by what's in them: pictures, PDFs, documents, recordings and videos. Everything runs on the device.")
                            .font(.body)
                            .foregroundStyle(.secondary)
                    }

                    modelSection
                    photosSection
                    folderSection
                    indexSection
                }
                .padding(24)
            }
            .navigationTitle("DigUp")
            .navigationBarTitleDisplayMode(.inline)
            .fileImporter(isPresented: $importing, allowedContentTypes: [.folder],
                          allowsMultipleSelection: true) { result in
                switch result {
                case .success(let urls): model.addFolders(urls)
                case .failure(let error): model.errorMessage = error.localizedDescription
                }
            }
            .alert("Something went wrong", isPresented: errorPresented) {
                Button("OK") { model.dismissError() }
            } message: {
                Text(model.errorMessage ?? "")
            }
        }
    }

    private var modelSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("1. The model").font(.headline)
            if model.modelReady {
                Label("Model ready", systemImage: "checkmark.seal.fill")
                    .foregroundStyle(.green)
            } else {
                Text("EmbeddingGemma 2 is 865 MB and downloads once. It stays on this iPhone; no file leaves it.")
                    .foregroundStyle(.secondary)
                Button {
                    model.downloadModel()
                } label: {
                    Label(model.isDownloading ? "Downloading…" : "Download the model", systemImage: "arrow.down.circle")
                }
                .buttonStyle(.borderedProminent)
                .disabled(model.isDownloading)
                if model.isDownloading {
                    ProgressView(value: model.downloadProgress)
                    Text("\(Int(model.downloadProgress * 100))%")
                        .font(.caption.monospacedDigit())
                        .foregroundStyle(.secondary)
                }
            }
        }
    }

    private var folderSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("3. Folders").font(.headline)
            Text("Or pick folders in Files. iCloud Drive and On My iPhone both work; DigUp only reads what you choose.")
                .foregroundStyle(.secondary)
            Button {
                importing = true
            } label: {
                Label("Choose folders to search", systemImage: "folder.badge.plus")
            }
            .buttonStyle(.bordered)
            ForEach(model.folders, id: \.path) { folder in
                Label(folder.lastPathComponent, systemImage: "folder")
                    .font(.subheadline)
            }
        }
    }

    private var photosSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("2. Photos").font(.headline)
            Text("The Pictures library, screenshots and videos included. iOS asks for read access the first time; iCloud originals download as they're indexed.")
                .foregroundStyle(.secondary)
            if model.photosEnabled {
                Label("Photos included", systemImage: "checkmark.circle.fill")
                    .foregroundStyle(.green)
                Button("Stop indexing Photos") { model.setPhotos(false) }
                    .buttonStyle(.bordered)
            } else {
                Button {
                    model.setPhotos(true)
                } label: {
                    Label("Include Photos", systemImage: "photo.on.rectangle")
                }
                .buttonStyle(.borderedProminent)
            }
        }
    }

    @ViewBuilder
    private var indexSection: some View {
        if model.hasSource {
            VStack(alignment: .leading, spacing: 12) {
                Text("4. Index").font(.headline)
                Button {
                    model.startIndexing()
                } label: {
                    Label("Start indexing", systemImage: "sparkles")
                }
                .buttonStyle(.borderedProminent)
                .disabled(!model.modelReady || model.isIndexing)

                if model.isIndexing {
                    if let progress = model.indexProgress {
                        ProgressView(value: progress.fraction)
                        Text("\(progress.phase) \(progress.done)/\(progress.total)")
                            .font(.caption.monospacedDigit())
                            .foregroundStyle(.secondary)
                        if let path = progress.path {
                            Text(PhotoPath.name(path) ?? (path as NSString).lastPathComponent)
                                .font(.caption)
                                .foregroundStyle(.secondary)
                                .lineLimit(1)
                        }
                    } else {
                        ProgressView()
                    }
                }
                Text("The first pass can take a while for a large photo library. You can leave the app; indexing resumes the next time it runs.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        }
    }

    private var errorPresented: Binding<Bool> {
        Binding(get: { model.errorMessage != nil }, set: { if !$0 { model.dismissError() } })
    }
}
