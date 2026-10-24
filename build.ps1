[CmdletBinding()]
param(
    [string[]]$Tasks = @('assembleDebug'),
    [string]$ToolsRoot = 'C:\Users\Public\Codex-TV-Tools'
)

$ErrorActionPreference = 'Stop'
$taskProjectRoot = $PSScriptRoot
$taskJdk = Join-Path $ToolsRoot 'jdk\jdk-21.0.12.1+1'
$taskSdk = Join-Path $ToolsRoot 'android-sdk'
$taskGradleCache = Join-Path $ToolsRoot 'gradle-cache'
$taskKeyDirectory = Join-Path $taskProjectRoot '.local-signing'
$taskKeyFile = Join-Path $taskKeyDirectory 'debug.keystore'
$taskBuildRoot = $taskProjectRoot

foreach ($taskRequiredPath in @((Join-Path $taskJdk 'bin\java.exe'), (Join-Path $taskSdk 'platforms\android-35\android.jar'))) {
    if (-not (Test-Path -LiteralPath $taskRequiredPath)) {
        throw "Required local build tool missing: $taskRequiredPath"
    }
}

# JDK's Windows @argfile decoding can corrupt a non-ASCII Gradle worker classpath.
# An ASCII junction keeps the same checkout, outputs and signing key in place.
if ($taskProjectRoot -match '[^\x00-\x7F]') {
    $taskLinkParent = Join-Path $ToolsRoot 'workspace-links'
    if ($taskLinkParent -match '[^\x00-\x7F]') {
        throw 'ToolsRoot must be an ASCII path when the project path contains non-ASCII characters.'
    }
    $taskHasher = [System.Security.Cryptography.SHA256]::Create()
    try {
        $taskPathHash = [BitConverter]::ToString($taskHasher.ComputeHash([Text.Encoding]::UTF8.GetBytes($taskProjectRoot.ToLowerInvariant()))).Replace('-', '').Substring(0, 12).ToLowerInvariant()
    }
    finally { $taskHasher.Dispose() }
    $taskBuildRoot = Join-Path $taskLinkParent "2sub-tv-$taskPathHash"
    New-Item -ItemType Directory -Path $taskLinkParent -Force | Out-Null
    if (Test-Path -LiteralPath $taskBuildRoot) {
        $taskExistingLink = Get-Item -LiteralPath $taskBuildRoot -Force
        if ($taskExistingLink.LinkType -ne 'Junction' -or
            [IO.Path]::GetFullPath(@($taskExistingLink.Target)[0]).TrimEnd('\') -ne $taskProjectRoot.TrimEnd('\')) {
            throw "Build junction already exists with a different target: $taskBuildRoot"
        }
    }
    else {
        New-Item -ItemType Junction -Path $taskBuildRoot -Target $taskProjectRoot | Out-Null
    }
}

$taskOldJava = $env:JAVA_HOME
$taskOldAndroidHome = $env:ANDROID_HOME
$taskOldAndroidSdkRoot = $env:ANDROID_SDK_ROOT
$taskOldGradleHome = $env:GRADLE_USER_HOME
try {
    $env:JAVA_HOME = $taskJdk
    $env:ANDROID_HOME = $taskSdk
    $env:ANDROID_SDK_ROOT = $taskSdk
    $env:GRADLE_USER_HOME = $taskGradleCache
    if (-not (Test-Path -LiteralPath $taskKeyFile)) {
        New-Item -ItemType Directory -Path $taskKeyDirectory -Force | Out-Null
        & (Join-Path $taskJdk 'bin\keytool.exe') -genkeypair -noprompt -keystore $taskKeyFile -storepass android -keypass android -alias androiddebugkey -dname 'CN=2SUB TV Local Test, O=Local, C=ZZ' -keyalg RSA -keysize 2048 -validity 10000
        if ($LASTEXITCODE -ne 0) { throw 'Could not generate the local test signing key.' }
    }
    Push-Location -LiteralPath $taskBuildRoot
    try {
        & (Join-Path $taskBuildRoot 'gradlew.bat') --project-dir $taskBuildRoot --no-daemon --console=plain @Tasks
        if ($LASTEXITCODE -ne 0) { throw "Gradle failed with exit code $LASTEXITCODE." }
    }
    finally { Pop-Location }
}
finally {
    $env:JAVA_HOME = $taskOldJava
    $env:ANDROID_HOME = $taskOldAndroidHome
    $env:ANDROID_SDK_ROOT = $taskOldAndroidSdkRoot
    $env:GRADLE_USER_HOME = $taskOldGradleHome
}
