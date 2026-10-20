# Fetches the two pinned EmbeddingGemma 2 GGUF files, verifies their SHA-256, and can push
# them to a connected Android device where the app reads them without re-downloading.
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File scripts\fetch-models.ps1
#   powershell -ExecutionPolicy Bypass -File scripts\fetch-models.ps1 -Destination D:\models
#   powershell -ExecutionPolicy Bypass -File scripts\fetch-models.ps1 -Push
param(
    [string]$Destination = "",
    [switch]$Push,
    [string]$Serial = ""
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
if (-not $Destination) { $Destination = Join-Path $root "models" }
$Destination = [System.IO.Path]::GetFullPath($Destination)
New-Item -ItemType Directory -Force -Path $Destination | Out-Null

$revision = "bfcd298762cc34d0357ece5ebdd31791a3a374d8"
$base = "https://huggingface.co/ggml-org/embeddinggemma-2-GGUF/resolve/$revision"
$files = @(
    @{ Name = "embeddinggemma-2-Q8_0.gguf"; Bytes = 309855456L; Sha256 = "2188ac1deca4b77dffefd603c2776a9d76d9d74ec01841392982ebb840b09135" },
    @{ Name = "mmproj-embeddinggemma-2-Q8_0.gguf"; Bytes = 554821024L; Sha256 = "c4a8a52691ecef40618438928bdf9e68379b854e24166f292592353db0aab64f" }
)

function Get-Sha256([string]$Path) {
    $stream = [System.IO.File]::OpenRead($Path)
    try {
        $sha = [System.Security.Cryptography.SHA256]::Create()
        try { return ([System.BitConverter]::ToString($sha.ComputeHash($stream)) -replace '-', '').ToLowerInvariant() }
        finally { $sha.Dispose() }
    } finally { $stream.Dispose() }
}

foreach ($file in $files) {
    $target = Join-Path $Destination $file.Name
    $part = "$target.part"
    if ((Test-Path -LiteralPath $target) -and (Get-Item -LiteralPath $target).Length -eq $file.Bytes) {
        Write-Host "models: $($file.Name) is in place"
        continue
    }
    if (Test-Path -LiteralPath $part) {
        if ((Get-Item -LiteralPath $part).Length -gt $file.Bytes) { Remove-Item -LiteralPath $part -Force }
    }
    Write-Host "models: downloading $($file.Name) ($($file.Bytes) bytes)"
    & curl.exe --fail --location --retry 5 --retry-delay 2 --continue-at - --output $part "$base/$($file.Name)"
    if ($LASTEXITCODE -ne 0) { throw "curl failed for $($file.Name)" }
    $length = (Get-Item -LiteralPath $part).Length
    if ($length -ne $file.Bytes) { throw "$($file.Name) has $length bytes, expected $($file.Bytes)" }
    $actual = Get-Sha256 $part
    if ($actual -ne $file.Sha256) {
        Remove-Item -LiteralPath $part -Force
        throw "$($file.Name) failed its SHA-256 check"
    }
    Move-Item -LiteralPath $part -Destination $target -Force
    Write-Host "models: $($file.Name) ready"
}

if ($Push) {
    $adb = (Get-Command adb -ErrorAction SilentlyContinue | Select-Object -First 1 -ExpandProperty Source)
    if (-not $adb) {
        $sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } elseif ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { "" }
        if ($sdk) {
            $candidate = Join-Path $sdk "platform-tools\adb.exe"
            if (Test-Path -LiteralPath $candidate) { $adb = $candidate }
        }
    }
    if (-not $adb) { throw "adb was not found. Add platform-tools to PATH or set ANDROID_HOME." }
    $adbArgs = @()
    if ($Serial) { $adbArgs += @("-s", $Serial) }
    $devices = & $adb @adbArgs devices
    if (($devices | Select-String -Pattern "\sdevice$").Count -eq 0) { throw "No authorized Android device is connected." }
    $remote = "/sdcard/Android/data/com.digup.android/files/Models"
    & $adb @adbArgs shell mkdir -p $remote
    if ($LASTEXITCODE -ne 0) { throw "Could not create $remote on the device" }
    foreach ($file in $files) {
        Write-Host "models: pushing $($file.Name)"
        & $adb @adbArgs push (Join-Path $Destination $file.Name) "$remote/$($file.Name)"
        if ($LASTEXITCODE -ne 0) { throw "adb push failed for $($file.Name)" }
    }
    Write-Host "models: pushed to $remote"
}

Write-Host "models: ready in $Destination"
