# Fetches llama.cpp b11461 (the release the iOS app is pinned to) and applies the
# memory patch used by DigUp. Run once before the first Android build.
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$dest = Join-Path $root "app\src\main\cpp\llama.cpp"
$patch = Join-Path $root "patches\llama.cpp-no-logits.patch"

if (-not (Test-Path (Join-Path $dest "CMakeLists.txt"))) {
    if (Test-Path $dest) { Remove-Item -Recurse -Force $dest }
    git -c advice.detachedHead=false -c core.autocrlf=false clone --quiet --depth 1 --branch b11461 `
        https://github.com/ggml-org/llama.cpp $dest
} else {
    $tag = (git -C $dest describe --tags --exact-match 2>$null)
    if ($tag -ne "b11461") {
        git -C $dest fetch --quiet --depth 1 origin tag b11461
        git -C $dest checkout --quiet --force b11461
    }
}

$head = (git -C $dest rev-parse HEAD)
if ($head -ne "4d756bc72bf00a4aacf410ae15a2d315f3db400d") {
    throw "llama.cpp b11461 is not at the expected commit (found $head)"
}

git -C $dest apply --ignore-whitespace --check $patch 2>$null
if ($LASTEXITCODE -eq 0) {
    git -C $dest apply --ignore-whitespace $patch
    Write-Host "Applied llama.cpp-no-logits.patch"
} else {
    git -C $dest apply --reverse --check $patch 2>$null
    if ($LASTEXITCODE -ne 0) { throw "The patch does not apply to this llama.cpp checkout." }
    Write-Host "Patch already applied"
}
Write-Host "llama.cpp is ready at $dest"
