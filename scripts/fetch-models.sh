#!/bin/bash
# Fetches the EmbeddingGemma 2 GGUF files the app bundles. `ios/project.yml` runs it before every Xcode build, and it
# is safe to run by hand: a file that is already there at the right size is left alone.
#
# Usage: scripts/fetch-models.sh [destination]
#        (default: ios/Resources/Models, or $MODELS_DIR)
set -euo pipefail
cd "$(dirname "$0")/.."

DEST="${1:-${MODELS_DIR:-ios/Resources/Models}}"
REVISION=bfcd298762cc34d0357ece5ebdd31791a3a374d8
BASE="https://huggingface.co/ggml-org/embeddinggemma-2-GGUF/resolve/$REVISION"

# name, exact byte count and SHA-256, matching Sources/DigUpKit/ModelFiles.swift.
FILES=(
  "embeddinggemma-2-Q8_0.gguf 309855456 2188ac1deca4b77dffefd603c2776a9d76d9d74ec01841392982ebb840b09135"
  "mmproj-embeddinggemma-2-Q8_0.gguf 554821024 c4a8a52691ecef40618438928bdf9e68379b854e24166f292592353db0aab64f"
)

size() { wc -c < "$1" | tr -d ' '; }

digest() {
  if command -v shasum > /dev/null 2>&1; then
    shasum -a 256 "$1" | awk '{print $1}'
  else
    sha256sum "$1" | awk '{print $1}'
  fi
}

mkdir -p "$DEST"
for record in "${FILES[@]}"; do
  read -r name bytes sha <<< "$record"
  path="$DEST/$name"

  if [[ -f "$path" && "$(size "$path")" == "$bytes" ]]; then
    echo "models: $name is in place"
    continue
  fi

  partial="$path.partial"
  if [[ -f "$partial" && "$(size "$partial")" -gt "$bytes" ]]; then
    rm -f "$partial"
  fi
  if [[ ! -f "$partial" || "$(size "$partial")" -lt "$bytes" ]]; then
    echo "models: downloading $name ($bytes bytes)"
    curl --fail --location --retry 5 --retry-delay 2 --continue-at - --output "$partial" "$BASE/$name"
  fi

  actual_size="$(size "$partial")"
  if [[ "$actual_size" != "$bytes" ]]; then
    rm -f "$partial"
    echo "models: $name has $actual_size bytes, expected $bytes" >&2
    exit 1
  fi
  actual_sha="$(digest "$partial")"
  if [[ "$actual_sha" != "$sha" ]]; then
    rm -f "$partial"
    echo "models: $name failed its SHA-256 check" >&2
    exit 1
  fi
  mv -f "$partial" "$path"
  echo "models: $name ready"
done

echo "models: ready in $DEST"
