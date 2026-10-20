# DigUp for iOS

The first iOS target is a native SwiftUI app over the existing `DigUpKit` engine. It is deliberately small: no helper
process, and no background scheduling yet.

The app links `DigUpKit` as a Swift package product and compiles `Sources/LlamaRuntime` (two files) straight into the
app target together with `ios/CLlamaIOS/module.modulemap`. That keeps llama.cpp's linker flags out of the package,
which SwiftPM would refuse to use as a dependency.

## What this target does

- Picks folders from Files (iCloud Drive and On My iPhone) and keeps security-scoped bookmarks.
- Indexes the Photos library through PhotoKit: pictures, screenshots (by `PHAsset.mediaSubtypes`) and videos, with
  iCloud originals downloaded on demand. Assets are keyed by `localIdentifier`, not by a folder path.
- Downloads the two Q8_0 GGUF files (865 MB) into Application Support and verifies their SHA-256.
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

cd ios
xcodegen generate
open DigUpIOS.xcodeproj
```

In Xcode choose your team under Signing & Capabilities (and set `PRODUCT_BUNDLE_IDENTIFIER` in `project.yml` to an
identifier you own if you plan to install it on more than one device), pick your iPhone, and run. The first launch
downloads the model; after that the app works offline.

## Device notes

- Use a physical iPhone. The simulator has no arm64 llama.cpp slice in this first cut, and Metal performance there
  doesn't represent a phone.
- iOS uses the system SQLite (with FTS5).
- 6 GB of RAM is the practical minimum for images/audio; text-only search is much smaller.
- Indexing runs only while the app is in the foreground for now. A large library needs a background-task design
  before it is usable as a daily driver.
