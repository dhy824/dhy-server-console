[CmdletBinding()]
param(
    [switch]$Release,
    [ValidateSet('Public','Personal')][string]$Mode = 'Public'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$projectRoot = $PSScriptRoot
. (Join-Path $projectRoot 'Build-State.ps1')
$toolchainRoot = Join-Path $projectRoot '.toolchain'
$javaHome = Get-ChildItem -LiteralPath (Join-Path $toolchainRoot 'jdk') -Directory -ErrorAction Stop |
    Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName 'bin/java.exe') -PathType Leaf } |
    Select-Object -First 1 -ExpandProperty FullName
$androidSdk = Join-Path $toolchainRoot 'android-sdk'
$gradle = Join-Path $toolchainRoot 'gradle/gradle-8.11.1/bin/gradle.bat'
if (-not $javaHome -or -not (Test-Path -LiteralPath $gradle -PathType Leaf)) {
    throw 'Toolchain is missing. Run prepare-toolchain.ps1 first.'
}

$desktopConfig = Join-Path ([Environment]::GetFolderPath('LocalApplicationData')) 'PersonalServerToolbox/toolbox.json'
$embeddedDefaults = Join-Path $projectRoot 'default-config.properties'

Invoke-WithAndroidBuildState -ProjectRoot $projectRoot -Body {
    param($buildState)
    $env:JAVA_HOME = $javaHome
    $env:ANDROID_SDK_ROOT = $androidSdk
    $env:ANDROID_HOME = $androidSdk
    if ($Mode -eq 'Personal' -and (Test-Path -LiteralPath $desktopConfig -PathType Leaf)) {
        $config = [IO.File]::ReadAllText($desktopConfig) | ConvertFrom-Json
        $lines = @(
            "host=$($config.HostName)"
            "sshPort=$([int]$config.Port)"
            "user=$($config.UserName)"
            "apiPort=$([int]$config.LocalManagementPort)"
            "astrBotPort=$([int]$config.AstrBotLocalPort)"
        )
        [IO.File]::WriteAllLines($embeddedDefaults, $lines, [Text.UTF8Encoding]::new($false))
        Write-Host 'Current desktop connection settings were embedded (private key excluded).'
    } else {
        [IO.File]::WriteAllText($embeddedDefaults, "host=`n", [Text.UTF8Encoding]::new($false))
        Write-Host 'Building with safe blank server defaults.'
    }

    $driveLetter = @('R', 'S', 'T', 'U', 'V') |
        Where-Object { -not (Test-Path -LiteralPath "${_}:\") } |
        Select-Object -First 1
    if (-not $driveLetter) { throw 'No free temporary drive letter is available for the Android build.' }
    $mappedDrive = "${driveLetter}:"
    & subst.exe $mappedDrive $projectRoot
    if ($LASTEXITCODE -ne 0) { throw "Unable to create temporary build drive $mappedDrive" }
    $buildState.MappedDrive = $mappedDrive

    $mappedRoot = "$mappedDrive\"
    $jdkFolder = Split-Path -Leaf $javaHome
    $env:JAVA_HOME = Join-Path $mappedRoot ".toolchain/jdk/$jdkFolder"
    $env:ANDROID_SDK_ROOT = Join-Path $mappedRoot '.toolchain/android-sdk'
    $env:ANDROID_HOME = $env:ANDROID_SDK_ROOT
    $mappedGradle = Join-Path $mappedRoot '.toolchain/gradle/gradle-8.11.1/bin/gradle.bat'
    $localProperties = "sdk.dir=$($env:ANDROID_SDK_ROOT.Replace('\', '/'))$([Environment]::NewLine)"
    [IO.File]::WriteAllText((Join-Path $projectRoot 'local.properties'), $localProperties, [Text.UTF8Encoding]::new($false))

    if ($Release) {
        $signingDir = Join-Path $mappedRoot '.signing'
        $keystorePath = Join-Path $signingDir 'amadeus-release.p12'
        $signingProperties = Join-Path $signingDir 'keystore.properties'
        $hasKeystore = Test-Path -LiteralPath $keystorePath -PathType Leaf
        $hasProperties = Test-Path -LiteralPath $signingProperties -PathType Leaf
        if ($hasKeystore -xor $hasProperties) {
            throw 'Release signing files are incomplete. Restore both files from backup before rebuilding.'
        }
        if (-not $hasKeystore -or -not $hasProperties) {
            throw 'Release signing is missing. Restore original signing files; automatic key generation is forbidden.'
        }
    }

    Push-Location $mappedRoot
    $buildState.LocationPushed = $true
    $variant = if ($Release) { 'Release' } else { 'Debug' }
    $savedErrorPolicy=$ErrorActionPreference
    try {
        # PowerShell 5 must not abort a successful native process on a stderr warning.
        $ErrorActionPreference='Continue'
        & $mappedGradle --no-daemon ":app:assemble$variant" ":app:lint$variant" ':app:testDebugUnitTest' 2>&1
        $gradleExit=$LASTEXITCODE
    } finally { $ErrorActionPreference=$savedErrorPolicy }
    if ($gradleExit -ne 0) { throw "Gradle build failed: $gradleExit" }
    Pop-Location
    $buildState.LocationPushed = $false

    $sourceApk = if ($Release) {
        Join-Path $projectRoot 'app/build/outputs/apk/release/app-release.apk'
    } else {
        Join-Path $projectRoot 'app/build/outputs/apk/debug/app-debug.apk'
    }
    $metadataPath = Join-Path (Split-Path -Parent $sourceApk) 'output-metadata.json'
    if (-not (Test-Path -LiteralPath $metadataPath -PathType Leaf)) {
        throw 'Gradle APK output metadata is missing.'
    }
    $metadata = [IO.File]::ReadAllText($metadataPath) | ConvertFrom-Json
    $versionName = [string]$metadata.elements[0].versionName
    if ($versionName -notmatch '^[0-9A-Za-z][0-9A-Za-z._-]*$') {
        throw "Unsafe or missing APK version name: $versionName"
    }
    # Windows PowerShell 5.1 may read a UTF-8 script without a BOM using the
    # active ANSI code page. Construct Chinese artifact names from code points
    # so a successful build can never be copied to a mojibake filename.
    $consoleName = ([char[]](0x670D, 0x52A1, 0x5668, 0x63A7, 0x5236, 0x53F0) -join '')
    $testSuffix = ([char[]](0x6D4B, 0x8BD5, 0x7248) -join '')
    $targetName = if ($Release) {
        "$consoleName$versionName.apk"
    } else {
        "$consoleName$versionName-$testSuffix.apk"
    }
    $targetApk = Join-Path (Split-Path -Parent $projectRoot) $targetName
    $verify = @{ Apk = $sourceApk; VersionName = $versionName; VersionCode = [int]$metadata.elements[0].versionCode; Public = ($Mode -eq 'Public') }
    if ($Release) { $verify['ReferenceApk'] = Join-Path (Split-Path -Parent $projectRoot) 'release-public/v1.2.3/dhy-server-console-1.2.3.apk' }
    & (Join-Path $projectRoot 'Test-Apk.ps1') @verify
    if (Test-Path -LiteralPath $targetApk) {
        $backupRoot = Join-Path $projectRoot 'artifact-backups'
        New-Item -ItemType Directory -Force -Path $backupRoot | Out-Null
        Copy-Item -LiteralPath $targetApk -Destination (Join-Path $backupRoot (([DateTime]::UtcNow.ToString('yyyyMMddTHHmmssfffffffZ')) + '-' + $targetName))
    }
    Copy-Item -LiteralPath $sourceApk -Destination $targetApk -Force
    Write-Host "APK: $targetApk"
}
