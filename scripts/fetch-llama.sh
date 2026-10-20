#!/bin/sh
# Fetches llama.cpp b11461 and applies the DigUp memory patch. Run once before the first build.
set -eu
root="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
dest="$root/app/src/main/cpp/llama.cpp"
patch="$root/patches/llama.cpp-no-logits.patch"

if [ ! -f "$dest/CMakeLists.txt" ]; then
  rm -rf "$dest"
  git -c advice.detachedHead=false -c core.autocrlf=false clone --quiet --depth 1 --branch b11461 \
    https://github.com/ggml-org/llama.cpp "$dest"
else
  tag="$(git -C "$dest" describe --tags --exact-match 2>/dev/null || true)"
  if [ "$tag" != "b11461" ]; then
    git -C "$dest" fetch --quiet --depth 1 origin tag b11461
    git -C "$dest" checkout --quiet --force b11461
  fi
fi

head="$(git -C "$dest" rev-parse HEAD)"
[ "$head" = "4d756bc72bf00a4aacf410ae15a2d315f3db400d" ] || {
  echo "llama.cpp b11461 is not at the expected commit (found $head)" >&2; exit 1; }

if git -C "$dest" apply --check "$patch" 2>/dev/null; then
  git -C "$dest" apply "$patch"; echo "Applied llama.cpp-no-logits.patch"
elif git -C "$dest" apply --reverse --check "$patch" 2>/dev/null; then
  echo "Patch already applied"
else
  echo "The patch does not apply to this llama.cpp checkout." >&2; exit 1
fi
echo "llama.cpp is ready at $dest"
