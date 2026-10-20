#!/bin/bash
# Builds llama.cpp (MIT) for the iOS device SDK: the pinned tag plus scripts/llama.cpp-no-logits.patch, as one static
# library (Vendor/llama-ios/lib/libllamacpp.a) with its headers in Vendor/llama-ios/include. It shares the source clone
# with scripts/build-llama.sh but not its output folder, so a Mac build and an iOS build can sit side by side.
#
# Run this on a Mac with Xcode and CMake (or uv, as scripts/build-llama.sh does). It builds arm64 only: the simulator
# needs a separate slice or an XCFramework, which the first iOS milestone doesn't need yet.
set -euo pipefail
cd "$(dirname "$0")/.."
TAG=b11461
COMMIT=4d756bc72bf00a4aacf410ae15a2d315f3db400d
PATCH=scripts/llama.cpp-no-logits.patch
SOURCE="${LLAMA_SOURCE:-Vendor/llama.cpp}"
BUILD="${LLAMA_BUILD:-Vendor/llama-ios-build}"
OUT=Vendor/llama-ios
SDK="${IOS_SDK:-iphoneos}"
ARCH="${IOS_ARCH:-arm64}"
DEPLOYMENT="${IOS_DEPLOYMENT_TARGET:-17.0}"

if [[ -z "${DEVELOPER_DIR:-}" && -d /Applications/Xcode.app ]]; then
  export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
fi
if ! command -v xcrun > /dev/null; then
  echo "xcrun not found: run this on a Mac with Xcode" >&2
  exit 1
fi

if [[ ! -d "$SOURCE" ]]; then
  git -c advice.detachedHead=false -c core.autocrlf=false clone --quiet --depth 1 --branch "$TAG" \
    https://github.com/ggml-org/llama.cpp "$SOURCE"
elif [[ "$(git -C "$SOURCE" describe --tags --exact-match 2>/dev/null)" != "$TAG" ]]; then
  git -C "$SOURCE" fetch --quiet --depth 1 origin tag "$TAG"
  git -C "$SOURCE" checkout --quiet --force "$TAG"
fi
if [[ "$(git -C "$SOURCE" rev-parse HEAD)" != "$COMMIT" ]]; then
  echo "llama.cpp's $TAG isn't commit $COMMIT here: not building it" >&2
  exit 1
fi
if git -C "$SOURCE" apply --check "$PWD/$PATCH" 2>/dev/null; then
  git -C "$SOURCE" apply "$PWD/$PATCH"
elif ! git -C "$SOURCE" apply --reverse --check "$PWD/$PATCH" 2>/dev/null; then
  echo "$PATCH doesn't apply to $SOURCE, and isn't applied already: not building it" >&2
  exit 1
fi

if ! command -v cmake > /dev/null; then
  if [[ ! -x Vendor/cmake-venv/bin/cmake ]]; then
    uv venv --quiet Vendor/cmake-venv --python 3.12
    uv pip install --quiet --python Vendor/cmake-venv/bin/python cmake==4.4.4 ninja==1.13.2
  fi
  export PATH="$PWD/Vendor/cmake-venv/bin:$PATH"
fi

GENERATOR=$(command -v ninja > /dev/null && echo Ninja || echo "Unix Makefiles")
cmake -S "$SOURCE" -B "$BUILD" -G "$GENERATOR" \
  -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_SYSTEM_NAME=iOS \
  -DCMAKE_OSX_SYSROOT="$SDK" \
  -DCMAKE_OSX_ARCHITECTURES="$ARCH" \
  -DCMAKE_OSX_DEPLOYMENT_TARGET="$DEPLOYMENT" \
  -DBUILD_SHARED_LIBS=OFF \
  -DGGML_METAL=ON -DGGML_METAL_EMBED_LIBRARY=ON -DGGML_NATIVE=OFF \
  -DLLAMA_BUILD_MTMD=ON \
  -DLLAMA_BUILD_COMMON=OFF -DLLAMA_BUILD_TOOLS=OFF -DLLAMA_BUILD_SERVER=OFF \
  -DLLAMA_BUILD_EXAMPLES=OFF -DLLAMA_BUILD_TESTS=OFF -DLLAMA_BUILD_APP=OFF -DLLAMA_BUILD_UI=OFF \
  -DLLAMA_OPENSSL=OFF -DLLAMA_CURL=OFF
cmake --build "$BUILD" --config Release -j "$(sysctl -n hw.ncpu)" --target llama mtmd

B="$BUILD"
mkdir -p "$OUT/lib" "$OUT/include"
# Metal, CPU and the text model are always there; BLAS (Accelerate) and the hash helper only when CMake built them.
ARCHIVES=("$B/src/libllama.a" "$B/tools/mtmd/libmtmd.a" "$B/ggml/src/libggml.a"
          "$B/ggml/src/libggml-base.a" "$B/ggml/src/libggml-cpu.a"
          "$B/ggml/src/ggml-metal/libggml-metal.a")
for optional in "$B/ggml/src/ggml-blas/libggml-blas.a" "$B/vendor/hash/libvendor-hash.a"; do
  if [[ -f "$optional" ]]; then ARCHIVES+=("$optional"); fi
done
xcrun libtool -static -no_warning_for_no_symbols -o "$OUT/lib/libllamacpp.a" "${ARCHIVES[@]}"
cp "$SOURCE/include/llama.h" "$SOURCE"/ggml/include/*.h "$SOURCE/tools/mtmd/mtmd.h" \
  "$SOURCE/tools/mtmd/mtmd-helper.h" "$OUT/include/"
printf '// Written by scripts/build-llama-ios.sh.\n#define LLAMA_BUILD_TAG "%s"\n' "$TAG" > "$OUT/include/llama-build.h"
printf '%s ios %s %s\n' "$TAG" "$ARCH" "$SDK" > "$OUT/BUILD"

echo "Built llama.cpp $TAG for $SDK/$ARCH into $OUT"
