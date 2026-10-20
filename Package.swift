// swift-tools-version: 6.2
import PackageDescription

// DigUp for iOS. `DigUpKit` is the engine: crawling, extraction, the index, search. The app target (ios/project.yml)
// compiles `Sources/LlamaRuntime` and llama.cpp (ios/CLlamaIOS/module.modulemap + scripts/build-llama-ios.sh) itself,
// so no SwiftPM product needs the static library's search path.
let package = Package(
    name: "DigUp",
    platforms: [.iOS(.v17)],
    products: [
        .library(name: "DigUpKit", targets: ["DigUpKit"]),
    ],
    targets: [
        .target(name: "DigUpKit", linkerSettings: [.linkedLibrary("sqlite3")]),
    ]
)
