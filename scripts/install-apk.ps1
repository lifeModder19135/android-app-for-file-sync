param(
    [Parameter(Mandatory = $true)][string]$ApkPath,
    [string]$Avd = "Pixel_API_35"
)

$ErrorActionPreference = "Stop"
$sdk = "C:\Users\ntolb\.tools\android-sdk"
$adb = Join-Path $sdk "platform-tools\adb.exe"
$emulator = Join-Path $sdk "emulator\emulator.exe"

$ApkPath = $ApkPath.Trim('"')
if (-not [System.IO.Path]::IsPathRooted($ApkPath)) {
    $projectRoot = Split-Path -Parent $PSScriptRoot
    $ApkPath = Join-Path $projectRoot $ApkPath
}
if (-not (Test-Path -LiteralPath $ApkPath)) { throw "APK not found: $ApkPath" }

function Get-ReadyDevice {
    (& $adb devices) | Where-Object { $_ -match "^emulator-\d+\s+device$" }
}

if (-not (Get-ReadyDevice)) {
    Write-Host "Starting emulator $Avd..."
    Start-Process $emulator -ArgumentList "-avd", $Avd
}

Write-Host "Waiting for emulator to boot..."
& $adb wait-for-device
$deadline = (Get-Date).AddMinutes(5)
while ((& $adb shell getprop sys.boot_completed 2>$null) -notmatch "1") {
    if ((Get-Date) -gt $deadline) { throw "Timed out waiting for boot." }
    Start-Sleep -Seconds 3
}

Write-Host "Installing $ApkPath..."
& $adb install -r $ApkPath
if ($LASTEXITCODE -ne 0) { throw "adb install failed." }
Write-Host "Installed."
