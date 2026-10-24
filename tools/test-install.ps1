# Offline checks of the installer. No real ADB, TV or download is used.
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path $PSScriptRoot -Parent
$taskFixture = Join-Path ([IO.Path]::GetTempPath()) ('twosub-installer-test-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $taskFixture | Out-Null
$taskFakeAdb = Join-Path $taskFixture 'fake-adb.ps1'
$taskApk = Join-Path $taskFixture 'fixture.apk'
$taskManifestPath = Join-Path $taskFixture 'release.json'
$taskLog = Join-Path $taskFixture 'calls.txt'
$taskScenario = Join-Path $taskFixture 'scenario.txt'
@'
param([Parameter(ValueFromRemainingArguments=$true)][string[]]$Arguments)
$argsText = $Arguments -join ' '
Add-Content -LiteralPath (Join-Path $PSScriptRoot 'calls.txt') -Value $argsText
$mode = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'scenario.txt')
$global:LASTEXITCODE = 0
switch -Regex ($argsText) {
    'get-state$' { 'device'; break }
    'ro.product.model$' { 'Test TV'; break }
    'ro.build.version.sdk$' { if($mode -eq 'old'){'25'}else{'30'}; break }
    'ro.product.cpu.abilist$' { 'armeabi-v7a'; break }
    'pm list features$' { 'feature:android.software.leanback'; break }
    'install -r ' { if($mode -eq 'signature'){$global:LASTEXITCODE=1; 'Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE]'}else{'Success'}; break }
    'am start ' { 'Starting: Intent'; break }
    default { throw "Unexpected ADB call: $argsText" }
}
'@ | Set-Content -LiteralPath $taskFakeAdb -Encoding UTF8
[IO.File]::WriteAllText($taskApk, 'test fixture, not an Android APK')
$taskValidHash = (Get-FileHash -LiteralPath $taskApk -Algorithm SHA256).Hash

function Test-InstallCase {
    param([string]$Name, [bool]$ExpectError, [bool]$ValidateOnly = $false, [bool]$BadHash = $false)
    Set-Content -LiteralPath $taskScenario -Value $Name
    Set-Content -LiteralPath $taskLog -Value ''
    $taskFixtureManifest = @{version='0.1.0'; packageId='app.twosub.tv'; minSdk=26; sha256=$(if($BadHash){'0'*64}else{$taskValidHash}); downloadUrl='https://github.com/zignifer/twosubstv/releases/download/v0.1.0/2sub-tv.apk'}
    $taskFixtureManifest | ConvertTo-Json | Set-Content -LiteralPath $taskManifestPath -Encoding UTF8
    $taskFailed = $false
    try { & (Join-Path $taskRoot 'install.ps1') -Serial 'chosen-device' -AdbPath $taskFakeAdb -ManifestPath $taskManifestPath -ApkPath $taskApk -ValidateOnly:$ValidateOnly }
    catch { $taskFailed = $true }
    if ($taskFailed -ne $ExpectError) { throw "Unexpected outcome: $Name" }
    $taskCalls = Get-Content -LiteralPath $taskLog -Raw
    if ($taskCalls -match 'uninstall|clear|tcpip') { throw "Unsafe mutation in $Name" }
    if (($ValidateOnly -or $BadHash -or $Name -eq 'old') -and $taskCalls -match 'install -r') { throw "Should not install in $Name" }
    if ($Name -eq 'signature' -and $taskCalls -match 'am start') { throw 'Should not launch after failed update' }
    if ($Name -eq 'success' -and $taskCalls -notmatch '-s chosen-device install -r') { throw 'Install must target the selected device' }
    if ($Name -eq 'success' -and $taskCalls -notmatch 'am start') { throw 'Successful install should request launch' }
    Write-Host "PASS: $Name"
}

try {
    Test-InstallCase -Name 'success' -ExpectError $false
    Test-InstallCase -Name 'validate' -ExpectError $false -ValidateOnly $true
    Test-InstallCase -Name 'hash' -ExpectError $true -BadHash $true
    Test-InstallCase -Name 'old' -ExpectError $true
    Test-InstallCase -Name 'signature' -ExpectError $true
}
finally {
    # Exact fixture paths only; no recursive cleanup or path enumeration.
    foreach ($taskFile in @($taskFakeAdb,$taskApk,$taskManifestPath,$taskLog,$taskScenario)) {
        if(Test-Path -LiteralPath $taskFile){Remove-Item -LiteralPath $taskFile -Force}
    }
    Remove-Item -LiteralPath $taskFixture -Force
}
# The expected signature-failure fixture sets a native-style exit code. Reset it
# only after every assertion and cleanup passed, so the CI shell reports success.
$global:LASTEXITCODE = 0
