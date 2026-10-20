# DigUp for iOS

Describe it. Dig it up. DigUp searches the files on your iPhone by what's *in* them: the Photos library, screenshots,
videos, PDFs and documents. Everything runs on the device — the embedding model, the index and the queries. The
network is used to fetch the model once at build time and, when you turn it on, to fetch iCloud originals.

This repository is the iOS-only fork of [ARahim3/DigUp](https://github.com/ARahim3/DigUp). The macOS app, the CLI, the
desktop shell and the Windows/Linux engine were removed; `DigUpKit` and `Sources/LlamaRuntime` are what remain, plus
this app.

## What it does

- **Photos** through PhotoKit: pictures, screenshots and videos, indexed incrementally by asset identifier. iCloud
  downloads are off by default; the switch in Settings fetches originals that aren't on the iPhone yet.
- **Folders** from Files (iCloud Drive, On My iPhone) through security-scoped bookmarks.
- **One hybrid search** over meaning (EmbeddingGemma 2 vectors) and words (SQLite FTS5): describe a picture, a page or
  a moment, or type a word that appears in it.
- **QuickLook** for every result, PDFs and videos included.
- **On-device only**: no upload, no account, no server.

## Requirements

- iPhone with iOS 17+ and **6 GB of RAM minimum** (8 GB comfortable); the full multimodal model peaks around
  1.1–1.4 GB while indexing.
- An Apple Silicon Mac with **Xcode 26** (the package manifest is Swift tools 6.2), XcodeGen, and CMake (or `uv`).
- About 865 MB of disk for the two Q8_0 model files (`embeddinggemma-2-Q8_0.gguf`,
  `mmproj-embeddinggemma-2-Q8_0.gguf`), both in the build folder and in the installed app.

## Build

```sh
brew install xcodegen
cd /path/to/Digup_ios

# llama.cpp b11461 for iphoneos/arm64, with scripts/llama.cpp-no-logits.patch.
chmod +x scripts/build-llama-ios.sh
scripts/build-llama-ios.sh

# Optional: fetch the model by hand. The Xcode build runs this check itself.
chmod +x scripts/fetch-models.sh
scripts/fetch-models.sh ios/Resources/Models

cd ios
xcodegen generate
open DigUpIOS.xcodeproj
```

Pick your team in Signing & Capabilities (and change `PRODUCT_BUNDLE_IDENTIFIER` in `ios/project.yml` if you plan to
install on more than one device), choose your iPhone and run. The model is already in the app bundle, so the first
launch goes straight to choosing Photos and folders.

Use a physical iPhone: the build script produces an arm64 device slice, not a simulator one.

## Models

The app bundles two GGUF files from
[`ggml-org/embeddinggemma-2-GGUF`](https://huggingface.co/ggml-org/embeddinggemma-2-GGUF) at revision
`bfcd298762cc34d0357ece5ebdd31791a3a374d8`:

| File | Bytes | SHA-256 |
| --- | ---: | --- |
| `embeddinggemma-2-Q8_0.gguf` | 309,855,456 | `2188ac1deca4b77dffefd603c2776a9d76d9d74ec01841392982ebb840b09135` |
| `mmproj-embeddinggemma-2-Q8_0.gguf` | 554,821,024 | `c4a8a52691ecef40618438928bdf9e68379b854e24166f292592353db0aab64f` |

Download URLs:

- https://huggingface.co/ggml-org/embeddinggemma-2-GGUF/resolve/bfcd298762cc34d0357ece5ebdd31791a3a374d8/embeddinggemma-2-Q8_0.gguf
- https://huggingface.co/ggml-org/embeddinggemma-2-GGUF/resolve/bfcd298762cc34d0357ece5ebdd31791a3a374d8/mmproj-embeddinggemma-2-Q8_0.gguf

`ios/project.yml` runs this before every Xcode build:

```sh
scripts/fetch-models.sh ios/Resources/Models
```

The script checks the two files in `ios/Resources/Models`. A missing or wrong-sized file is downloaded with
`curl --continue-at -` (a `.partial` file resumes), then checked against the byte count and SHA-256 above; a mismatch
stops the build. Files that are already there at the right size are not downloaded or re-hashed, so the check stays
quick. The folder is copied into the app bundle as `Models/`, and `LlamaModels` looks there first. The older
first-launch download into Application Support remains as a fallback.

The weights are not committed; `.gitignore` leaves them out.
They add about 865 MB to the app bundle, so install and update over Wi-Fi.

## How it fits together

- `Sources/DigUpKit` — the engine: crawler, extractors (ImageIO, Vision, PDFKit, AVFoundation), the SQLite index,
  hybrid search, and the PhotoKit source (`Platform/Apple/Photos+Apple.swift`).
- `Sources/LlamaRuntime` — llama.cpp + mtmd, embedding text, images and audio in process.
- `ios/DigUpIOS` — the SwiftUI app; `ios/CLlamaIOS/module.modulemap` and `Vendor/llama-ios` come from the build script.
  `ios/Resources/Models` is copied into the bundle (`scripts/fetch-models.sh`).
- A Photos asset is stored as `ph://<localIdentifier>#<name>`: PhotoKit exports the original into the indexer's scratch
  folder just before it's read, and that folder is removed with the job.

## Not here yet

- docx/odt (iOS reads RTF only); legacy .doc.
- Background indexing (`BGProcessingTask`) and background model downloads.
- Share Extension, App Intents / Siri.
- A simulator slice / XCFramework.
- Code search (it was a desktop feature).
