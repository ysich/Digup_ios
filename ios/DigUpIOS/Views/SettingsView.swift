import SwiftUI

struct SettingsView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var confirmClear = false

    var body: some View {
        NavigationStack {
            List {
                Section("Folders") {
                    ForEach(model.folders, id: \.path) { folder in
                        Label(folder.lastPathComponent, systemImage: "folder")
                    }
                    if model.folders.isEmpty {
                        Text("No folders chosen").foregroundStyle(.secondary)
                    }
                }

                Section("Photos") {
                    Toggle("Index the Photos library", isOn: Binding(get: { model.photosEnabled },
                                                                     set: { model.setPhotos($0) }))
                    Text("Screenshots and videos are included. iCloud originals download while indexing.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }

                Section("Index") {
                    LabeledContent("Size", value: model.indexSizeText)
                    Button("Update index") { model.startIndexing() }
                        .disabled(!model.modelReady || !model.hasSource || model.isIndexing)
                    Button("Rebuild index") { model.rebuildIndex() }
                        .disabled(!model.modelReady || !model.hasSource)
                    Button("Clear index", role: .destructive) { confirmClear = true }
                }

                Section("Model") {
                    LabeledContent("Status", value: model.modelReady ? "Ready" : "Not downloaded")
                    Text("EmbeddingGemma 2 · Q8_0 · 865 MB")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }

                Section("Privacy") {
                    Text("Files and queries stay on this device. The only network use is the model download.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            }
            .navigationTitle("Settings")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Done") { dismiss() }
                }
            }
            .confirmationDialog("Clear the index?", isPresented: $confirmClear, titleVisibility: .visible) {
                Button("Clear index", role: .destructive) { model.clearIndex() }
                Button("Cancel", role: .cancel) {}
            } message: {
                Text("The files stay where they are. They'll be read again the next time you index.")
            }
        }
    }
}
