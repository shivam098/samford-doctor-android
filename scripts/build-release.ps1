<#
.SYNOPSIS
  Builds the signed release APK locally, verifies its signature and writes its SHA-256.

.DESCRIPTION
  Decrypts the DPAPI-protected keystore password (see create-signing-key.ps1) into
  process-scoped environment variables read by app/build.gradle.kts, runs assembleRelease
  (R8 + resource shrinking + Baseline Profile), verifies with apksigner, and copies
  samford-doctor-<version>.apk and its .sha256 to -OutDir. Nothing secret is printed or written
  to the repository.
#>
[CmdletBinding()]
param(
    [string]$OutDir = (Join-Path $PSScriptRoot '..\build\release'),
    [string]$SigningDir = (Join-Path $env:USERPROFILE 'OneDrive - Microsoft\Documents\Samford-Android-Signing'),
    [string]$JavaHome = (Join-Path $env:LOCALAPPDATA 'Programs\Java\jdk-21.0.13.1+2'),
    [string]$AndroidHome = (Join-Path $env:LOCALAPPDATA 'Android\Sdk')
)
$ErrorActionPreference = 'Stop'
$repo = Resolve-Path (Join-Path $PSScriptRoot '..')
$keystore = Join-Path $SigningDir 'samford-doctor-release.p12'
$credFile = Join-Path $SigningDir 'samford-doctor-release.cred.xml'
if (-not (Test-Path $keystore)) { throw "No signing key at $keystore. Run scripts\create-signing-key.ps1 first." }

$cred = Import-Clixml -Path $credFile
$env:JAVA_HOME = $JavaHome
$env:ANDROID_HOME = $AndroidHome
$env:SAMFORD_KEYSTORE_FILE = $keystore
$env:SAMFORD_KEY_ALIAS = $cred.UserName
$env:SAMFORD_KEYSTORE_PASSWORD = $cred.GetNetworkCredential().Password
$env:SAMFORD_KEY_PASSWORD = $env:SAMFORD_KEYSTORE_PASSWORD
try {
    Push-Location $repo
    & .\gradlew.bat --no-configuration-cache :app:assembleRelease --console=plain -q
    if ($LASTEXITCODE -ne 0) { throw 'assembleRelease failed' }
} finally {
    Pop-Location
    Remove-Item Env:SAMFORD_KEYSTORE_PASSWORD, Env:SAMFORD_KEY_PASSWORD, Env:SAMFORD_KEYSTORE_FILE, Env:SAMFORD_KEY_ALIAS -ErrorAction SilentlyContinue
}

$apk = Get-ChildItem (Join-Path $repo 'app\build\outputs\apk\release') -Filter '*.apk' | Where-Object { $_.Name -notmatch 'unsigned' } | Select-Object -First 1
if (-not $apk) { throw 'No signed release APK was produced.' }
$buildTools = Get-ChildItem (Join-Path $AndroidHome 'build-tools') | Sort-Object Name -Descending | Select-Object -First 1
& (Join-Path $buildTools.FullName 'apksigner.bat') verify --verbose --print-certs $apk.FullName | Select-String -Pattern 'Verified using|Signer #1 certificate DN|SHA-256 digest'
if ($LASTEXITCODE -ne 0) { throw 'apksigner verification failed' }

$version = (Select-String -Path (Join-Path $repo 'app\build.gradle.kts') -Pattern 'versionName = "([^"]+)"').Matches[0].Groups[1].Value
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$target = Join-Path $OutDir "samford-doctor-v$version.apk"
Copy-Item $apk.FullName $target -Force
$hash = (Get-FileHash -Algorithm SHA256 $target).Hash.ToLowerInvariant()
Set-Content -Path "$target.sha256" -Value "$hash  samford-doctor-v$version.apk" -NoNewline
Write-Output "APK:    $target"
Write-Output "SHA256: $hash"
