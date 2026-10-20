# DigUp for iOS

The first iOS target is a native SwiftUI app over the existing `DigUpKit` engine. It is deliberately small: no helper
process, and no background scheduling yet.

The app links `DigUpKit` as a Swift package product and compiles `Sources/LlamaRuntime` (two files) straight into the
app target together with `ios/CLlamaIOS/module.modulemap`. That keeps llama.cpp's linker flags out of the package,
which SwiftPM would refuse to use as a dependency.

## What this target does

- Picks folders from Files (iCloud Drive and On My iPhone) and keeps security-scoped bookmarks.
- Indexes the Photos library through PhotoKit: pictures, screenshots (by `PHAsset.mediaSubtypes`) and videos. Assets
  are keyed by `localIdentifier`, not by a folder path. iCloud downloads are off by default; turning on "Download
  iCloud originals" in Settings fetches the originals and picks up the assets that were left out.
- Ships the two Q8_0 GGUF files (865 MB) in the app bundle. `scripts/fetch-models.sh` fetches them before the build;
  a first-launch download into Application Support remains as a fallback.
- Indexes pictures, PDFs, plain text/RTF, audio and video with the same `Indexer`. docx/odt need the portable zip
  reader and are not in this first cut.
- Searches with the same hybrid vector + FTS5 `Searcher`, using a text-only `LlamaEmbedder` for the query.
- Opens results in QuickLook (PDFs at the file, media in the system player).

## What is not here yet

- Background indexing (`BGProcessingTask`) and background model downloads (`URLSession` background configuration).
- Share Extension, App Intents / Siri, and a Spotlight-style entry point.
- Code search (iOS can't spawn git).
- An XCFramework / simulator slice: the build script produces an arm64 device archive only.

## Build on a Mac

You need an Apple Silicon Mac with Xcode 26, an iOS 17+ device, CMake (or `uv`), and XcodeGen:

```sh
brew install xcodegen
cd /path/to/Digup_ios

# llama.cpp b11461 for iphoneos/arm64, with the one-line no-logits patch.
# This writes Vendor/llama-ios.
chmod +x scripts/build-llama-ios.sh
scripts/build-llama-ios.sh

# Optional: the Xcode build runs this check itself.
chmod +x scripts/fetch-models.sh
scripts/fetch-models.sh ios/Resources/Models

cd ios
xcodegen generate
open DigUpIOS.xcodeproj
```

In Xcode choose your team under Signing & Capabilities (and set `PRODUCT_BUNDLE_IDENTIFIER` in `project.yml` to an
identifier you own if you plan to install it on more than one device), pick your iPhone, and run. The model is copied
into the bundle at build time; after installing, the app works offline.

## Models

Revision `bfcd298762cc34d0357ece5ebdd31791a3a374d8` of `ggml-org/embeddinggemma-2-GGUF`:

- https://huggingface.co/ggml-org/embeddinggemma-2-GGUF/resolve/bfcd298762cc34d0357ece5ebdd31791a3a374d8/embeddinggemma-2-Q8_0.gguf (309,855,456 bytes)
- https://huggingface.co/ggml-org/embeddinggemma-2-GGUF/resolve/bfcd298762cc34d0357ece5ebdd31791a3a374d8/mmproj-embeddinggemma-2-Q8_0.gguf (554,821,024 bytes)

`ios/project.yml` runs `scripts/fetch-models.sh ios/Resources/Models` before every build. It checks the files by
size, downloads what's missing into `ios/Resources/Models` (resuming `.partial` files), and verifies the SHA-256 from
the root README. The folder is copied into the app as `Models/`; `.gitignore` keeps the weights out of Git.

## Device notes

- Use a physical iPhone. The simulator has no arm64 llama.cpp slice in this first cut, and Metal performance there
  doesn't represent a phone.
- iOS uses the system SQLite (with FTS5).
- 6 GB of RAM is the practical minimum for images/audio; text-only search is much smaller.
- Indexing runs only while the app is in the foreground for now. A large library needs a background-task design
  before it is usable as a daily driver.
