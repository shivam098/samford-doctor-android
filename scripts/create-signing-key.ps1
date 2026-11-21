<#
.SYNOPSIS
  Creates the Samford Doctor release signing key outside the repository (run once).

.DESCRIPTION
  Generates a random password, stores it DPAPI-protected (only this Windows user on this PC can
  decrypt it) with Export-Clixml, and creates a PKCS12 keystore with keytool. The password is
  passed to keytool through a process-scoped environment variable, never on the command line,
  and is never printed. Refuses to overwrite an existing key: losing or replacing the key means
  installed apps can no longer be updated in place.
#>
[CmdletBinding()]
param(
    [string]$Directory = (Join-Path $env:USERPROFILE 'OneDrive - Microsoft\Documents\Samford-Android-Signing'),
    [string]$JavaHome = (Join-Path $env:LOCALAPPDATA 'Programs\Java\jdk-21.0.13.1+2'),
    [string]$Alias = 'samford-doctor'
)
$ErrorActionPreference = 'Stop'
$keystore = Join-Path $Directory 'samford-doctor-release.p12'
$credFile = Join-Path $Directory 'samford-doctor-release.cred.xml'
if ((Test-Path $keystore) -or (Test-Path $credFile)) { throw "A doctor signing key already exists in $Directory. Not overwriting it." }
New-Item -ItemType Directory -Force -Path $Directory | Out-Null

$bytes = New-Object byte[] 32
[Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
$plain = [Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+', 'A').Replace('/', 'B')
$secure = ConvertTo-SecureString $plain -AsPlainText -Force
New-Object System.Management.Automation.PSCredential($Alias, $secure) | Export-Clixml -Path $credFile

$env:SAMFORD_KS_PW = $plain
try {
    & (Join-Path $JavaHome 'bin\keytool.exe') -genkeypair -noprompt -storetype PKCS12 -keystore $keystore -alias $Alias `
        -keyalg RSA -keysize 4096 -validity 10000 `
        -dname 'CN=Samford Doctor (demo), OU=Paperless OPD demo, O=Samford Hospital demo, L=Ranchi, C=IN' `
        -storepass:env SAMFORD_KS_PW -keypass:env SAMFORD_KS_PW
    if ($LASTEXITCODE -ne 0) { throw 'keytool failed' }
} finally {
    Remove-Item Env:SAMFORD_KS_PW -ErrorAction SilentlyContinue
    $plain = $null
}
Write-Output "Created $keystore (alias $Alias); password stored DPAPI-protected in $credFile."
