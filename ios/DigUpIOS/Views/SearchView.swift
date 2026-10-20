import DigUpKit
import SwiftUI
import UniformTypeIdentifiers

struct SearchView: View {
    @Environment(AppModel.self) private var model
    @State private var query = ""

    var body: some View {
        @Bindable var model = model
        NavigationStack {
            List {
                if let progress = model.indexProgress {
                    Section {
                        IndexStatusRow(progress: progress)
                    }
                }

                if model.results.isEmpty {
                    Section {
                        ContentUnavailableView(
                            query.isEmpty ? "Search your files" : "No matches",
                            systemImage: "magnifyingglass",
                            description: Text(query.isEmpty
                                ? "Describe a picture, a page, a moment in a recording, or a phrase."
                                : "Try describing the content differently, or a word that appears in it.")
                        )
                    }
                } else {
                    Section {
                        ForEach(model.results, id: \.segmentID) { hit in
                            Button {
                                model.showPreview(hit)
                            } label: {
                                ResultRow(hit: hit)
                            }
                            .buttonStyle(.plain)
                        }
                    }
                }
            }
            .listStyle(.plain)
            .navigationTitle("DigUp")
            .searchable(text: $query, placement: .navigationBarDrawer(displayMode: .always),
                        prompt: "Describe what you're looking for")
            .onChange(of: query) { _, value in model.search(value) }
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button { model.showFolderPicker = true } label: {
                        Image(systemName: "folder.badge.plus")
                    }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button { model.showSettings = true } label: {
                        Image(systemName: "gearshape")
                    }
                }
            }
            .fileImporter(isPresented: $model.showFolderPicker, allowedContentTypes: [.folder],
                          allowsMultipleSelection: true) { result in
                switch result {
                case .success(let urls): model.addFolders(urls)
                case .failure(let error): model.errorMessage = error.localizedDescription
                }
            }
            .sheet(item: $model.previewTarget) { target in
                PreviewSheet(target: target)
            }
            .sheet(isPresented: $model.showSettings) {
                SettingsView()
            }
            .alert("Something went wrong", isPresented: errorPresented) {
                Button("OK") { model.dismissError() }
            } message: {
                Text(model.errorMessage ?? "")
            }
        }
    }

    private var errorPresented: Binding<Bool> {
        Binding(get: { model.errorMessage != nil }, set: { if !$0 { model.dismissError() } })
    }
}

struct IndexStatusRow: View {
    let progress: IndexProgress

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text(progress.phase)
                Spacer()
                Text("\(progress.done)/\(progress.total)")
                    .font(.caption.monospacedDigit())
                    .foregroundStyle(.secondary)
            }
            ProgressView(value: progress.fraction)
            if let path = progress.path {
                Text(PhotoPath.name(path) ?? (path as NSString).lastPathComponent)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
        }
        .padding(.vertical, 4)
    }
}
