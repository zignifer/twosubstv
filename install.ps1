[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][ValidateNotNullOrEmpty()][string]$Serial,
    [string]$AdbPath = 'adb',
    [string]$ManifestPath = (Join-Path $PSScriptRoot 'release.json'),
    [string]$ApkPath,
    [switch]$ValidateOnly
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

function Invoke-SelectedAdb {
    param([string[]]$Arguments)
    $taskResult = & $AdbPath -s $Serial @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) { throw "ADB failed: $($taskResult -join [Environment]::NewLine)" }
    return ($taskResult -join [Environment]::NewLine).Trim()
}

if (-not (Get-Command $AdbPath -ErrorAction SilentlyContinue)) {
    throw 'ADB not found. Install official Android SDK Platform-Tools and pass -AdbPath.'
}
$taskManifest = Get-Content -LiteralPath $ManifestPath -Raw -Encoding UTF8 | ConvertFrom-Json
if ($taskManifest.packageId -ne 'app.twosub.tv' -or $taskManifest.minSdk -lt 26 -or
    $taskManifest.sha256 -notmatch '^[A-Fa-f0-9]{64}$' -or
    $taskManifest.downloadUrl -notmatch '^https://github\.com/zignifer/twosubstv/releases/download/v[0-9]+\.[0-9]+\.[0-9]+/2sub-tv\.apk$') {
    throw 'Unexpected release manifest. Use release.json from zignifer/twosubstv.'
}
if ((Invoke-SelectedAdb -Arguments @('get-state')) -ne 'device') {
    throw 'The selected TV is not ready. Confirm pairing/debugging on the TV.'
}
$taskModel = Invoke-SelectedAdb -Arguments @('shell', 'getprop', 'ro.product.model')
$taskSdkText = Invoke-SelectedAdb -Arguments @('shell', 'getprop', 'ro.build.version.sdk')
$taskAbi = Invoke-SelectedAdb -Arguments @('shell', 'getprop', 'ro.product.cpu.abilist')
if ($taskSdkText -notmatch '^\d+$' -or [int]$taskSdkText -lt [int]$taskManifest.minSdk) {
    throw "Android API $($taskManifest.minSdk)+ required; device reports $taskSdkText."
}
$taskFeatures = Invoke-SelectedAdb -Arguments @('shell', 'pm', 'list', 'features')
if ($taskFeatures -notmatch 'feature:android\.software\.leanback') {
    throw 'Selected device does not advertise Android TV / leanback support.'
}
Write-Host "Selected TV: $taskModel; API $taskSdkText; ABI $taskAbi"
Write-Host "Release: $($taskManifest.version); package: $($taskManifest.packageId)"

$taskTemporaryDirectory = $null
try {
    if (-not $ApkPath) {
        $taskTemporaryDirectory = Join-Path ([IO.Path]::GetTempPath()) ('twosub-install-' + [Guid]::NewGuid().ToString('N'))
        New-Item -ItemType Directory -Path $taskTemporaryDirectory | Out-Null
        $ApkPath = Join-Path $taskTemporaryDirectory '2sub-tv.apk'
        Invoke-WebRequest -Uri $taskManifest.downloadUrl -OutFile $ApkPath -UseBasicParsing
    }
    $taskResolvedApk = (Resolve-Path -LiteralPath $ApkPath).Path
    $taskHash = (Get-FileHash -LiteralPath $taskResolvedApk -Algorithm SHA256).Hash
    if ($taskHash -ne $taskManifest.sha256) {
        throw 'APK SHA-256 mismatch. Installation was not attempted.'
    }
    Write-Host "SHA-256 verified: $taskHash"
    if ($ValidateOnly) {
        Write-Host 'Validation passed. No installation or app launch was performed.'
        return
    }
    $taskInstall = Invoke-SelectedAdb -Arguments @('install', '-r', $taskResolvedApk)
    if ($taskInstall -notmatch '(?m)^Success\s*$') {
        throw "Installation did not report success. Existing data was not cleared: $taskInstall"
    }
    $taskLaunch = Invoke-SelectedAdb -Arguments @('shell', 'am', 'start', '-n', 'app.twosub.tv/.MainActivity')
    if ($taskLaunch -match '(?im)^(Error|Exception)') { throw "Installed, but launch failed: $taskLaunch" }
    Write-Host 'Installed and launch requested. Check the TV, remote navigation and QR login yourself.'
}
finally {
    if ($taskTemporaryDirectory -and (Test-Path -LiteralPath $taskTemporaryDirectory)) {
        # Only remove this invocation's exact download file, then the empty directory.
        $taskDownloadedFile = Join-Path $taskTemporaryDirectory '2sub-tv.apk'
        if (Test-Path -LiteralPath $taskDownloadedFile) { Remove-Item -LiteralPath $taskDownloadedFile -Force }
        Remove-Item -LiteralPath $taskTemporaryDirectory -Force
    }
}
