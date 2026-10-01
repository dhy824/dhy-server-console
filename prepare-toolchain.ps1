[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$projectRoot = $PSScriptRoot
$toolchainRoot = Join-Path $projectRoot '.toolchain'
$downloadRoot = Join-Path $toolchainRoot 'downloads'
$jdkArchive = Join-Path $downloadRoot 'microsoft-jdk-17-windows-x64.zip'
$jdkChecksum = Join-Path $downloadRoot 'microsoft-jdk-17-windows-x64.zip.sha256sum.txt'
$gradleArchive = Join-Path $downloadRoot 'gradle-8.11.1-bin.zip'
$androidArchive = Join-Path $downloadRoot 'commandlinetools-win-15859902_latest.zip'

function Get-Artifact {
    param(
        [Parameter(Mandatory)][string]$Uri,
        [Parameter(Mandatory)][string]$Destination
    )
    if (Test-Path -LiteralPath $Destination -PathType Leaf) { return }
    $partial = "$Destination.part"
    for ($attempt = 1; $attempt -le 3; $attempt++) {
        try {
            Write-Host "Downloading $([IO.Path]::GetFileName($Destination)) (attempt $attempt/3)..."
            Invoke-WebRequest -Uri $Uri -OutFile $partial -UseBasicParsing
            Move-Item -LiteralPath $partial -Destination $Destination -Force
            return
        } catch {
            if (Test-Path -LiteralPath $partial -PathType Leaf) {
                Remove-Item -LiteralPath $partial -Force
            }
            if ($attempt -eq 3) { throw }
        }
    }
}

function Assert-Sha256 {
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][string]$Expected
    )
    $actual = (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actual -ne $Expected.ToLowerInvariant()) {
        throw "SHA-256 mismatch: $([IO.Path]::GetFileName($Path))"
    }
}

New-Item -ItemType Directory -Force -Path $downloadRoot | Out-Null

Get-Artifact 'https://aka.ms/download-jdk/microsoft-jdk-17-windows-x64.zip' $jdkArchive
Get-Artifact 'https://aka.ms/download-jdk/microsoft-jdk-17-windows-x64.zip.sha256sum.txt' $jdkChecksum
$jdkExpected = ([IO.File]::ReadAllText($jdkChecksum) -split '\s+')[0]
if ($jdkExpected -notmatch '^[0-9a-fA-F]{64}$') { throw 'Invalid JDK checksum file.' }
Assert-Sha256 $jdkArchive $jdkExpected

Get-Artifact 'https://services.gradle.org/distributions/gradle-8.11.1-bin.zip' $gradleArchive
Get-Artifact 'https://services.gradle.org/distributions/gradle-8.11.1-bin.zip.sha256' "$gradleArchive.sha256"
$gradleExpected = ([IO.File]::ReadAllText("$gradleArchive.sha256") -split '\s+')[0]
Assert-Sha256 $gradleArchive $gradleExpected

Get-Artifact 'https://dl.google.com/android/repository/commandlinetools-win-15859902_latest.zip' $androidArchive
Assert-Sha256 $androidArchive '90ae805d20434428bffcb699c290860f19bb5f66a67e6b330067e3de801fb04a'

$jdkExtract = Join-Path $toolchainRoot 'jdk'
$gradleExtract = Join-Path $toolchainRoot 'gradle'
$commandLineExtract = Join-Path $toolchainRoot 'commandline-tools'
$androidSdk = Join-Path $toolchainRoot 'android-sdk'

if (-not (Test-Path -LiteralPath $jdkExtract -PathType Container)) {
    Expand-Archive -LiteralPath $jdkArchive -DestinationPath $jdkExtract
}
if (-not (Test-Path -LiteralPath (Join-Path $gradleExtract 'gradle-8.11.1/bin/gradle.bat') -PathType Leaf)) {
    New-Item -ItemType Directory -Force -Path $gradleExtract | Out-Null
    Expand-Archive -LiteralPath $gradleArchive -DestinationPath $gradleExtract
}
if (-not (Test-Path -LiteralPath (Join-Path $commandLineExtract 'cmdline-tools/bin/sdkmanager.bat') -PathType Leaf)) {
    New-Item -ItemType Directory -Force -Path $commandLineExtract | Out-Null
    Expand-Archive -LiteralPath $androidArchive -DestinationPath $commandLineExtract
}

$javaHome = Get-ChildItem -LiteralPath $jdkExtract -Directory |
    Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName 'bin/java.exe') -PathType Leaf } |
    Select-Object -First 1 -ExpandProperty FullName
if (-not $javaHome) { throw 'Portable JDK was not found after extraction.' }

$env:JAVA_HOME = $javaHome
$env:ANDROID_SDK_ROOT = $androidSdk
$sdkManager = Join-Path $commandLineExtract 'cmdline-tools/bin/sdkmanager.bat'
New-Item -ItemType Directory -Force -Path $androidSdk | Out-Null

Write-Host 'Accepting Android SDK licenses...'
$licenseInput = (1..100 | ForEach-Object { 'y' }) -join [Environment]::NewLine
$licenseInput | & $sdkManager "--sdk_root=$androidSdk" --licenses | Out-Host
if ($LASTEXITCODE -ne 0) { throw "sdkmanager --licenses failed: $LASTEXITCODE" }

Write-Host 'Installing Android SDK 35 build components...'
& $sdkManager "--sdk_root=$androidSdk" 'platform-tools' 'platforms;android-35' 'build-tools;35.0.0'
if ($LASTEXITCODE -ne 0) { throw "sdkmanager package install failed: $LASTEXITCODE" }

$localProperties = "sdk.dir=$($androidSdk.Replace('\', '/'))$([Environment]::NewLine)"
[IO.File]::WriteAllText((Join-Path $projectRoot 'local.properties'), $localProperties, [Text.UTF8Encoding]::new($false))

Write-Host 'Android toolchain is ready.'
