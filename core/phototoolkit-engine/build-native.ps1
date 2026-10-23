[CmdletBinding()]
param(
    [ValidateSet('arm64-v8a', 'armeabi-v7a')]
    [string]$Abi = 'arm64-v8a',
    [int]$ApiLevel = 24,
    [string]$NdkPath,
    [switch]$Clean,
    [switch]$Rebuild,
    [switch]$AllAbis
)

$ErrorActionPreference = 'Stop'
$ModuleRoot = $PSScriptRoot
$ProjectRoot = Split-Path -Parent $ModuleRoot
$BuildNative = Join-Path $ProjectRoot 'build-native.ps1'
$SyncEngine = Join-Path $ProjectRoot 'sync-engine-prebuilt.ps1'
$Verify = Join-Path $ModuleRoot 'verify-16k.ps1'
$Abis = if ($AllAbis) { @('arm64-v8a', 'armeabi-v7a') } else { @($Abi) }

foreach ($TargetAbi in $Abis) {
    & $BuildNative -Abi $TargetAbi -ApiLevel $ApiLevel -NdkPath $NdkPath -Clean:($Clean -and $TargetAbi -eq $Abis[0]) -Rebuild:$Rebuild
    if ($LASTEXITCODE -ne 0) { throw "Native dependency build failed for $TargetAbi." }
    & $SyncEngine -Abi $TargetAbi
    if ($LASTEXITCODE -ne 0) { throw "Engine runtime synchronization failed for $TargetAbi." }
}

& $Verify -NdkPath $NdkPath
if ($LASTEXITCODE -ne 0) { throw 'Engine 16 KB validation failed.' }

Write-Host "Engine runtime is ready: $(Join-Path $ModuleRoot 'src\main\jniLibs')"
