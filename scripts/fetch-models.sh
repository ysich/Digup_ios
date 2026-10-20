#!/bin/sh
# Fetches the two pinned EmbeddingGemma 2 GGUF files into ./models.
# Usage: scripts/fetch-models.sh [destination]
set -eu
root="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
dest="${1:-${MODELS_DIR:-$root/models}}"
revision=bfcd298762cc34d0357ece5ebdd31791a3a374d8
base="https://huggingface.co/ggml-org/embeddinggemma-2-GGUF/resolve/$revision"

mkdir -p "$dest"
fetch() {
  name="$1"; bytes="$2"; sha="$3"; path="$dest/$name"; part="$path.part"
  if [ -f "$path" ] && [ "$(wc -c < "$path" | tr -d ' ')" = "$bytes" ]; then
    echo "models: $name is in place"
    return
  fi
  if [ -f "$part" ] && [ "$(wc -c < "$part" | tr -d ' ')" -gt "$bytes" ]; then rm -f "$part"; fi
  curl --fail --location --retry 5 --retry-delay 2 --continue-at - --output "$part" "$base/$name"
  actual_size="$(wc -c < "$part" | tr -d ' ')"
  if [ "$actual_size" != "$bytes" ]; then rm -f "$part"; echo "models: $name has $actual_size bytes, expected $bytes" >&2; exit 1; fi
  if command -v shasum >/dev/null 2>&1; then actual_sha="$(shasum -a 256 "$part" | awk '{print $1}')";
  else actual_sha="$(sha256sum "$part" | awk '{print $1}')"; fi
  if [ "$actual_sha" != "$sha" ]; then rm -f "$part"; echo "models: $name failed its SHA-256 check" >&2; exit 1; fi
  mv -f "$part" "$path"
  echo "models: $name ready"
}

fetch embeddinggemma-2-Q8_0.gguf 309855456 2188ac1deca4b77dffefd603c2776a9d76d9d74ec01841392982ebb840b09135
fetch mmproj-embeddinggemma-2-Q8_0.gguf 554821024 c4a8a52691ecef40618438928bdf9e68379b854e24166f292592353db0aab64f
echo "models: ready in $dest"
