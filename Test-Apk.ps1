[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][string]$Apk,
    [Parameter(Mandatory=$true)][string]$VersionName,
    [Parameter(Mandatory=$true)][int]$VersionCode,
    [switch]$Public,
    [string]$ReferenceApk
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot 'Build-State.ps1')
$buildTools = Join-Path $PSScriptRoot '.toolchain/android-sdk/build-tools/35.0.0'
$aapt = Join-Path $buildTools 'aapt.exe'
$signer = Join-Path $buildTools 'apksigner.bat'
$stage = Join-Path ([IO.Path]::GetTempPath()) ('amadeus-verify-' + [Guid]::NewGuid().ToString('N'))
$savedJava = $env:JAVA_HOME
function Get-Certificate([string]$File) {
    $lines = @(& $signer verify --print-certs $File 2>&1)
    if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed.' }
    $certificates = @($lines | ForEach-Object { if ([string]$_ -match '^Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]+)$') { $matches[1].ToLowerInvariant() } })
    if ($certificates.Count -ne 1) { throw 'Expected exactly one APK signer.' }
    return $certificates[0]
}
try {
    $env:JAVA_HOME = Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot '.toolchain/jdk') -Directory | Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName 'bin/java.exe') } | Select-Object -First 1 -ExpandProperty FullName
    New-Item -ItemType Directory -Path $stage | Out-Null
    $candidate = Join-Path $stage 'candidate.apk'
    Copy-Item -LiteralPath $Apk -Destination $candidate
    $badging = @(& $aapt dump badging $candidate 2>&1)
    if ($LASTEXITCODE -ne 0) { throw 'APK metadata cannot be read.' }
    $package = @($badging | Where-Object { [string]$_ -like 'package:*' })
    if ($package.Count -ne 1 -or $package[0] -notmatch "name='cn.zzmllk.amadeus'" -or $package[0] -notmatch ("versionName='" + [regex]::Escape($VersionName) + "'") -or $package[0] -notmatch ("versionCode='" + $VersionCode + "'")) { throw 'APK package or version mismatch.' }
    if ($Public) {
        $resources = @(& $aapt dump --values resources $candidate 2>&1)
        if ($LASTEXITCODE -ne 0) { throw 'APK resources cannot be read.' }
        $reading = $false; $values = @()
        foreach ($entry in $resources) {
            $line = [string]$entry
            if ($line -match '^\s*resource\s+\S+\s+\S+:string/default_host:') { $reading = $true; continue }
            if ($line -match '^\s*(?:spec )?resource\s') { $reading = $false }
            if ($reading -and $line.TrimStart().StartsWith('(string')) { $values += $line }
        }
        if ($values.Count -eq 0 -or @($values | Where-Object { $_ -notmatch '""\s*$' }).Count -gt 0) { throw 'Public APK contains missing or nonempty server defaults.' }
    }
    $certificate = Get-Certificate $candidate
    if ($ReferenceApk) {
        Copy-Item -LiteralPath $ReferenceApk -Destination (Join-Path $stage 'reference.apk')
        if ($certificate -ne (Get-Certificate (Join-Path $stage 'reference.apk'))) { throw 'Release signer differs from the previous official APK. Restore original signing materials.' }
        if (@($badging | Where-Object { [string]$_ -eq 'application-debuggable' }).Count -gt 0) { throw 'Release APK is debuggable.' }
    }
    [pscustomobject]@{ Package='cn.zzmllk.amadeus'; Version=$VersionName; VersionCode=$VersionCode; PublicDefaultsVerified=[bool]$Public; SignatureVerified=$true; SignerContinuityVerified=[bool]$ReferenceApk; Sha256=(Get-FileHash -LiteralPath $candidate -Algorithm SHA256).Hash.ToLowerInvariant() }
} finally {
    $env:JAVA_HOME = $savedJava
    # Only this invocation's exact temporary directory is removed.
    Remove-AndroidOwnedDirectory -Path $stage -Parent ([IO.Path]::GetTempPath()) -Prefix 'amadeus-verify-'
}
