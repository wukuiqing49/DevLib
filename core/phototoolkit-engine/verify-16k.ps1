[CmdletBinding()]
param(
    [string]$NdkPath,
    [string]$LibraryRoot = (Join-Path $PSScriptRoot 'src\main\jniLibs')
)

$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($NdkPath)) { $NdkPath = $env:ANDROID_NDK_HOME }
if ([string]::IsNullOrWhiteSpace($NdkPath)) { $NdkPath = $env:ANDROID_NDK_ROOT }
if ([string]::IsNullOrWhiteSpace($NdkPath)) { $NdkPath = $env:ANDROID_NDK }
if ([string]::IsNullOrWhiteSpace($NdkPath) -and $env:LOCALAPPDATA) {
    $NdkDirectory = Join-Path $env:LOCALAPPDATA 'Android\Sdk\ndk'
    if (Test-Path -LiteralPath $NdkDirectory) {
        $NdkPath = Get-ChildItem -LiteralPath $NdkDirectory -Directory | Sort-Object Name -Descending | Select-Object -First 1 -ExpandProperty FullName
    }
}
if ([string]::IsNullOrWhiteSpace($NdkPath)) { throw 'Android NDK r27+ was not found.' }

$ReadElf = Join-Path (Resolve-Path -LiteralPath $NdkPath).Path 'toolchains\llvm\prebuilt\windows-x86_64\bin\llvm-readelf.exe'
if (-not (Test-Path -LiteralPath $ReadElf)) { throw "llvm-readelf was not found: $ReadElf" }
$Libraries = Get-ChildItem -LiteralPath $LibraryRoot -Recurse -File -Filter '*.so'
if ($Libraries.Count -eq 0) { throw "No native libraries found: $LibraryRoot" }

$Validated = 0
$Skipped32Bit = 0
foreach ($Library in $Libraries) {
    $ElfClass = & $ReadElf -h $Library.FullName |
        Where-Object { $_ -match '^\s*Class:\s+' } |
        Select-Object -First 1
    if ($ElfClass -notmatch 'ELF64') {
        $Skipped32Bit++
        continue
    }
    $LoadSegments = & $ReadElf -lW $Library.FullName | Where-Object { $_ -match '^\s*LOAD\s+' }
    if ($LoadSegments.Count -eq 0) { throw "No LOAD segments found: $($Library.FullName)" }
    foreach ($Segment in $LoadSegments) {
        $Alignment = [Convert]::ToInt32((($Segment -split '\s+')[-1] -replace '^0x', ''), 16)
        if ($Alignment -lt 0x4000 -or $Alignment % 0x4000 -ne 0) {
            throw "16 KB alignment check failed: $($Library.FullName)"
        }
    }
    $Validated++
}

Write-Host "16 KB ELF64 validation passed: $Validated libraries; skipped $Skipped32Bit 32-bit libraries"
