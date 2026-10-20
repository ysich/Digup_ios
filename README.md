# DigUp for Android

Describe it. Dig it up. DigUp searches the files on your phone by what is *in* them: photos,
screenshots, videos, PDFs, documents and recordings. Everything runs on the device: the embedding
model, the index and the queries. Release builds can bundle the model in the APK; builds without it
download the model once.

This is an Android port of [`Digup_ios`](https://github.com/ARahim3/DigUp) (SwiftUI + DigUpKit +
llama.cpp). It is a native Kotlin/Compose app; the search engine, index format and prompts follow
the iOS implementation.

## What it does

- **Photos and videos** through MediaStore, including screenshots. Android 14 partial access
  ("selected photos") is supported: DigUp indexes what the system lets it see.
- **Folders** through the Storage Access Framework (Downloads, SD cards, cloud providers). Grants
  are persisted across restarts.
- **Hybrid search** over meaning (EmbeddingGemma 2 vectors via llama.cpp) and words (SQLite FTS5
  with Porter stemming, plus character-spaced CJK/Thai indexing).
- **Results** open in a built-in preview for images, video, audio and PDF pages, or with any app
  that can read the content URI.
- **On-device only**: no account, no server, no upload.

## Requirements

- Android 10 (API 29) or newer, **arm64-v8a**.
- 6 GB of RAM or more recommended. The full multimodal model peaks around 1.1-1.4 GB while
  indexing; `largeHeap` is enabled.
- About 865 MB of app storage for the two Q8_0 GGUF files, plus the APK size when the model is bundled.
- A JDK 17, Android SDK platform 35, build-tools 35.0.0, NDK 27.0.12077973 and CMake 3.22.1 to
  build.

## Build

```powershell
cd F:\Project\Digup_android

# llama.cpp b11461 with the no-logits memory patch (one time).
powershell -ExecutionPolicy Bypass -File scripts\fetch-llama.ps1

# Optional: fetch the two GGUF files. When present, Gradle copies them into the APK.
powershell -ExecutionPolicy Bypass -File scripts\fetch-models.ps1

# Point Gradle at your SDK (or set ANDROID_HOME).
"sdk.dir=C:/path/to/android-sdk" | Set-Content local.properties

.\gradlew.bat assembleDebug -PrequireBundledModels=true
```

The APK is `app/build/outputs/apk/debug/app-debug.apk`. Install it with
`adb install -r app\build\outputs\apk\debug\app-debug.apk`.

The Gradle wrapper is included. If `scripts\fetch-llama.ps1` is not used, copy a llama.cpp b11461
checkout to `app/src/main/cpp/llama.cpp` and apply `patches/llama.cpp-no-logits.patch`.

### Release builds

```powershell
.\gradlew.bat assembleRelease
```

Create a signing config in `app/build.gradle.kts` (or sign the unsigned APK with `apksigner`).
Release builds enable R8; the JNI entry points are kept by `app/proguard-rules.pro`.

## Models

When `models/embeddinggemma-2-Q8_0.gguf` and `models/mmproj-embeddinggemma-2-Q8_0.gguf` are present
with the expected sizes, the `bundleModels` Gradle task copies them to the APK assets. On first launch
the app copies and SHA-256-verifies them into its private `Models` directory. Uninstalling and
reinstalling the same APK therefore needs no network download.

Use `-PrequireBundledModels=true` to make the build fail if either model is missing or incomplete:

```powershell
.\gradlew.bat assembleRelease -PrequireBundledModels=true
```

Builds without the two files still work and fall back to the in-app downloader on first launch. The
models can be fetched ahead of time, or pushed to a connected device:

```powershell
powershell -ExecutionPolicy Bypass -File scripts\fetch-models.ps1 -Push
```

In regions where the Hugging Face CDN is slow, add `-Mirror` to use `hf-mirror.com` or `-ModelScope` to use the ModelScope CDN; file sizes and SHA-256 checks are unchanged.

`-Push` copies them to `/sdcard/Android/data/com.digup.android/files/Models/`, which the app reads
when the internal copy is absent. Without `-Push`, files land in `.\models`.

| File | Bytes | SHA-256 |
| --- | ---: | --- |
| `embeddinggemma-2-Q8_0.gguf` | 309,855,456 | `2188ac1deca4b77dffefd603c2776a9d76d9d74ec01841392982ebb840b09135` |
| `mmproj-embeddinggemma-2-Q8_0.gguf` | 554,821,024 | `c4a8a52691ecef40618438928bdf9e68379b854e24166f292592353db0aab64f` |

Source: [`ggml-org/embeddinggemma-2-GGUF`](https://huggingface.co/ggml-org/embeddinggemma-2-GGUF)
at revision `bfcd298762cc34d0357ece5ebdd31791a3a374d8`.

## How it works

| Piece | Android |
| --- | --- |
| UI | Jetpack Compose, Material 3 |
| Photos | `MediaStore.Images` + `MediaStore.Video` |
| Folders | SAF tree URIs with persisted read grants |
| Index | bundled SQLite 3.50, FTS5, WAL |
| Vectors | 768-d float32, stored as float16 blobs |
| Model runtime | llama.cpp b11461 in-process, JNI (`digup_llama`) |
| Images / audio | `mtmd` image and audio encoders in the same llama.cpp process |
| OCR | ML Kit text recognition (Latin + Chinese) |
| PDF text | PDFBox-Android; scanned pages rendered with `PdfRenderer` |
| Video | `MediaMetadataRetriever` keyframes + `MediaCodec` audio windows |

The index stores one row per file and one per segment (image, OCR text, PDF page, document chunk,
audio window, video frame) with its vector beside it. Search combines:

- cosine similarity per modality, turned into a z-score; only segments at `z >= 2` count as
  semantic matches;
- FTS5 coverage of the query words (a word must be in at least half the query terms);
- a small boost when the query names a file kind ("screenshot", "PDF", "video"...).

Prompts match iOS: `task: search result | query: …` for queries and
`title: … | text: …` for documents.

## Permissions

- `READ_MEDIA_IMAGES` / `READ_MEDIA_VIDEO` (Android 13+) or `READ_EXTERNAL_STORAGE` (Android 10-12)
  for the photo library.
- `READ_MEDIA_VISUAL_USER_SELECTED` on Android 14+ for partial photo access.
- No storage permission is needed for SAF folders; the system grants per-tree access.
- `INTERNET` is used only by the model downloader.

## Limitations

- **arm64 only.** The build filters to `arm64-v8a`; x86 emulators cannot run the native library.
- **No background service.** Indexing runs while the app is alive and resumes from the stored
  per-file progress next launch. A very large library can take a long time on first index.
- **Bundled builds are large.** The model cannot be committed to a normal GitHub repository, so it
  is a build input. A bundled APK is several hundred MB and is intended for sideloading.
- **ML Kit has no Bengali or Arabic OCR model.** Latin and Chinese are always run; other scripts
  depend on ML Kit coverage. This is narrower than iOS Vision.
- Legacy `.doc` files are skipped (`.docx`, `.odt`, `.rtf`, `.html`, `.txt`, `.md` work).
- Code files are classified but not indexed on Android.
- Audio indexing is driven by SAF files and video soundtracks; the standalone music library is not
  a source.

## Layout

```
app/src/main/java/com/digup/android/
  DigUpApp.kt, MainActivity.kt, MainViewModel.kt, DigUpEngine.kt
  data/      Core, Database, IndexStore, Crawler, Extractors, Indexer, Searcher, SearchText,
             ModelFiles, ModelDownloader, BundledModels
  ml/        LlamaBridge (JNI), Embedder
  ui/        Compose screens and theme
app/src/main/cpp/
  digup_llama.cpp, CMakeLists.txt, llama.cpp/ (fetched)
```

## Credits

DigUp is MIT licensed. See `LICENSE`. llama.cpp, EmbeddingGemma 2, PDFBox-Android, ML Kit,
Media3 and Coil keep their own licenses.
