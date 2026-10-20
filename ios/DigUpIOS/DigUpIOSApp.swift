import SwiftUI

@main
struct DigUpIOSApp: App {
    @State private var model = AppModel()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(model)
        }
    }
}

struct RootView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        Group {
            if model.modelReady && model.hasSource {
                SearchView()
            } else {
                OnboardingView()
            }
        }
        // A sync on launch queues whatever changed since the last run; `Indexer` resumes from the stored state.
        .task {
            if model.modelReady && model.hasSource { model.startIndexing() }
        }
    }
}
