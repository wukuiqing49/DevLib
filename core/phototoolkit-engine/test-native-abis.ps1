[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [hashtable]$DeviceByAbi
)

$ErrorActionPreference = 'Stop'
$ModuleRoot = $PSScriptRoot
$ProjectRoot = Resolve-Path (Join-Path $ModuleRoot '..\..')
$Gradle = Join-Path $ProjectRoot 'gradlew.bat'
$RequiredAbis = @('armeabi-v7a', 'arm64-v8a')
$PreviousSerial = $env:ANDROID_SERIAL

try {
    foreach ($Abi in $RequiredAbis) {
        $Serial = $DeviceByAbi[$Abi]
        if ([string]::IsNullOrWhiteSpace($Serial)) {
            throw "A device or emulator serial is required for $Abi."
        }
        $Supported = (& adb -s $Serial shell getprop ro.product.cpu.abilist).Trim().Split(',')
        if ($Supported -notcontains $Abi) {
            throw "Device $Serial does not support $Abi. Reported ABIs: $($Supported -join ', ')"
        }
        $env:ANDROID_SERIAL = $Serial
        & $Gradle ':core:phototoolkit-engine:connectedDebugAndroidTest' "-PphototoolkitTestAbi=$Abi" '--console=plain'
        if ($LASTEXITCODE -ne 0) { throw "Native functional test failed for $Abi on $Serial." }
        & $Gradle ':core:core_qr_poster:connectedDebugAndroidTest' "-PphototoolkitTestAbi=$Abi" '--console=plain'
        if ($LASTEXITCODE -ne 0) { throw "QR poster functional test failed for $Abi on $Serial." }
    }
} finally {
    $env:ANDROID_SERIAL = $PreviousSerial
}

Write-Host 'Native functional tests passed for both supported ARM ABIs.'
