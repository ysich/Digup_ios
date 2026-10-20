// swift-tools-version: 6.2
import PackageDescription

// DigUp builds on macOS (the app, the CLI and its helpers), on Linux and Windows (the engine and CLI), and, since the
// iOS port, the engine for iOS. The manifest picks each *build host's* pieces. An iOS build is made on a Mac, so it
// uses the macOS branch: the engine uses Apple's frameworks instead of the Linux/Windows C libraries. The iOS app
// compiles `LlamaRuntime` into its own target (see ios/project.yml), so the package's llama.cpp link flags stay out of
// a SwiftPM dependency, where `unsafeFlags` aren't allowed.
let vendor = "\(Context.packageDirectory)/Vendor"

#if os(macOS)
let llamaModule = "Sources/CLlama"                        // static llama.cpp with Metal (scripts/build-llama.sh)
let llamaLink: [LinkerSetting] = [.unsafeFlags(["-L\(vendor)/llama/lib"])]
let engineDependencies: [Target.Dependency] = []
let testDependencies: [Target.Dependency] = []
let engineLink: [LinkerSetting] = [.linkedLibrary("sqlite3")]   // the system SQLite (macOS and iOS)
let portableTargets: [Target] = []
#else
// llama.cpp as shared libraries: ggml's backends (CPU, and CUDA or Vulkan when the machine has them) load at run time
// from beside the helper (`LlamaEmbedder`), so one build runs anywhere. Dev builds find them in Vendor/.
#if os(Windows)
// No rpath on Windows: the DLLs go beside digup.exe, or on PATH in dev runs. MSVC's C++ runtime needs no
// link line of its own, so the module map differs from Linux's by that.
let runpath: [String] = []
let llamaModule = "Sources/CLlamaWindows"
#else
let runpath = ["-Xlinker", "-rpath", "-Xlinker", "$ORIGIN",
               "-Xlinker", "-rpath", "-Xlinker", "\(vendor)/llama/lib",
               "-Xlinker", "-rpath", "-Xlinker", "\(vendor)/pdfium/lib"]
let llamaModule = "Sources/CLlamaShared"
#endif
let llamaLink: [LinkerSetting] = [.unsafeFlags(["-L\(vendor)/llama/lib"] + runpath)]
let engineDependencies: [Target.Dependency] = ["CSQLite", "CStb", "CMiniz", "CPDFium"]
let testDependencies: [Target.Dependency] = ["CMiniz"]   // the portable readers' tests write zips with it
let engineLink: [LinkerSetting] = [.unsafeFlags(["-L\(vendor)/pdfium/lib"] + runpath)]
let portableTargets: [Target] = [
    // SQLite with FTS5 (Windows has no system SQLite, Linux's may lack FTS5 or its headers).
    .target(name: "CSQLite", path: "Vendor/sqlite", cSettings: [
        .define("SQLITE_ENABLE_FTS5"), .define("SQLITE_THREADSAFE", to: "1"), .define("SQLITE_DQS", to: "0"),
        .define("SQLITE_OMIT_LOAD_EXTENSION"), .unsafeFlags(["-w"]),
    ]),
    // Image decoding, resizing and PNG/JPEG writing (stb_image, stb_image_resize2, stb_image_write). On Windows its file
    // names are UTF-8, opened through the wide-character calls (else a Bengali or Arabic name won't open).
    .target(name: "CStb", path: "Vendor/stb", cSettings: [
        .define("STBI_WINDOWS_UTF8", .when(platforms: [.windows])),
        .define("STBIW_WINDOWS_UTF8", .when(platforms: [.windows])), .unsafeFlags(["-w"]),
    ]),
    // Zip reading, for docx and odt.
    .target(name: "CMiniz", path: "Vendor/miniz", cSettings: [.unsafeFlags(["-w"])]),
    // PDF text and page rendering (Chromium's PDFium, prebuilt).
    .systemLibrary(name: "CPDFium", path: "Sources/CPDFium"),
]
#endif

#if os(macOS)
let appProducts: [Product] = [.executable(name: "DigUpApp", targets: ["DigUpApp"])]
let appDependencies: [Package.Dependency] = [
    // In-app updates (the app only). build.sh embeds the framework in Contents/Frameworks and signs it.
    .package(url: "https://github.com/sparkle-project/Sparkle", exact: "2.10.0"),
]
let appTargets: [Target] = [
    // The menubar + hotkey app. build.sh wraps it into build.noindex/DigUp.app. (The product can't be called
    // "DigUp": on a case-insensitive disk it would collide with the `digup` CLI binary.)
    .executableTarget(
        name: "DigUpApp",
        dependencies: ["DigUpKit", .product(name: "Sparkle", package: "Sparkle")],
        swiftSettings: [.defaultIsolation(MainActor.self)],
        linkerSettings: [.unsafeFlags(["-Xlinker", "-rpath", "-Xlinker", "@executable_path/../Frameworks"])]
    ),
]
#else
let appProducts: [Product] = []
let appDependencies: [Package.Dependency] = []
let appTargets: [Target] = []
#endif

let package = Package(
    name: "DigUp",
    platforms: [.macOS(.v14), .iOS(.v17)],
    products: [
        .executable(name: "digup", targets: ["digup"]),
        .library(name: "DigUpKit", targets: ["DigUpKit"]),
    ] + appProducts,
    dependencies: appDependencies,
    targets: portableTargets + [
        // The engine: crawler, extractors, index store, the embedder interface and worker client, search. No UI.
        // Platform/ holds what differs by OS (Apple's frameworks on macOS, the C libraries above elsewhere).
        .target(name: "DigUpKit", dependencies: engineDependencies, linkerSettings: engineLink),
        // llama.cpp (scripts/build-llama.sh builds it into Vendor/llama) and EmbeddingGemma 2 on it. The search path to
        // that library is a linker setting of the executables below, not of this target: unsafe flags would make the
        // product unusable as a SwiftPM dependency (the iOS app depends on DigUpKit from Xcode).
        .systemLibrary(name: "CLlama", path: llamaModule),
        .target(name: "LlamaRuntime", dependencies: ["CLlama", "DigUpKit"]),
        // Developer CLI: estimate / index / search / status / eval. Doubles as the eval harness, and the app runs it as
        // its indexing helper and its query encoder.
        .executableTarget(name: "digup", dependencies: ["DigUpKit", "LlamaRuntime"], linkerSettings: llamaLink),
        // Pictures/ holds ImageTests' pictures (scripts/testbed/make_test_pictures.swift) and Videos/ VideoTests' video,
        // read by path.
        .testTarget(name: "DigUpKitTests", dependencies: ["DigUpKit"] + testDependencies, exclude: ["Pictures", "Videos"]),
        // Reference/ holds the fixed inputs and vectors of ReferenceVectorTests, read by path.
        .testTarget(name: "LlamaRuntimeTests", dependencies: ["LlamaRuntime"], exclude: ["Reference"],
                    linkerSettings: llamaLink),
    ] + appTargets
)
