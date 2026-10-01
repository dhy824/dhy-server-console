[CmdletBinding()]
param()
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
$project=Split-Path -Parent $PSScriptRoot
$helper=Join-Path $project 'Build-State.ps1'
. $helper
$tempRoot=[IO.Path]::GetTempPath()
$fixture=Join-Path $tempRoot ('android-state-test-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $fixture | Out-Null
$checks=New-Object 'System.Collections.Generic.List[string]'
function Assert([bool]$Condition,[string]$Name) { if (-not $Condition) { throw ('FAILED: ' + $Name) } }
$defaults=Join-Path $fixture 'default-config.properties'
$local=Join-Path $fixture 'local.properties'
$originalEnv=@{}
foreach($name in @('JAVA_HOME','ANDROID_SDK_ROOT','ANDROID_HOME')) { $originalEnv[$name]=[Environment]::GetEnvironmentVariable($name,'Process') }
try {
    [IO.File]::WriteAllBytes($defaults,[byte[]](0,1,2,13,10))
    [IO.File]::WriteAllBytes($local,[byte[]]@())
    Invoke-WithAndroidBuildState -ProjectRoot $fixture -Body {
        param($state)
        [IO.File]::WriteAllText($defaults,'synthetic-defaults')
        [IO.File]::WriteAllText($local,'synthetic-sdk')
        $env:JAVA_HOME='synthetic-java'; $env:ANDROID_SDK_ROOT='synthetic-sdk'; $env:ANDROID_HOME='synthetic-home'
    }
    Assert ([Convert]::ToBase64String([IO.File]::ReadAllBytes($defaults)) -eq 'AAECDQo=') 'byte-exact-restore'
    Assert ((Get-Item -LiteralPath $local).Length -eq 0) 'empty-file-restored'
    foreach($name in $originalEnv.Keys) { Assert ([Environment]::GetEnvironmentVariable($name,'Process') -ceq $originalEnv[$name]) ('environment-' + $name) }
    $checks.Add('success-restores-config-bytes-empty-file-and-environment')

    Remove-Item -LiteralPath $defaults,$local -Force
    $failed=$false
    try {
        Invoke-WithAndroidBuildState -ProjectRoot $fixture -Body {
            param($state)
            [IO.File]::WriteAllText($defaults,'temporary')
            [IO.File]::WriteAllText($local,'temporary')
            $env:JAVA_HOME='temporary-java'
            Push-Location $fixture; $state.LocationPushed=$true
            throw 'injected-build-failure'
        }
    } catch { $failed=$_.Exception.Message -match 'injected-build-failure' }
    Assert $failed 'original-failure-propagated'
    Assert (-not (Test-Path -LiteralPath $defaults)) 'new-defaults-removed'
    Assert (-not (Test-Path -LiteralPath $local)) 'new-local-properties-removed'
    Assert ([Environment]::GetEnvironmentVariable('JAVA_HOME','Process') -ceq $originalEnv['JAVA_HOME']) 'failed-build-environment-restored'
    $checks.Add('failure-restores-state-and-propagates-error')

    $driveLetter=@('R','S','T','U','V') | Where-Object { -not (Test-Path -LiteralPath ($_ + ':\')) } | Select-Object -First 1
    if (-not $driveLetter) { throw 'No free drive for ownership fixture.' }
    $drive=$driveLetter + ':'
    Invoke-WithAndroidBuildState -ProjectRoot $fixture -Body {
        param($state)
        & subst.exe $drive $fixture
        if($LASTEXITCODE -ne 0) { throw 'Fixture drive creation failed.' }
        $state.MappedDrive=$drive
        Assert (Test-Path -LiteralPath ($drive + '\')) 'fixture-drive-visible'
    }
    Assert (-not (Test-Path -LiteralPath ($drive + '\'))) 'owned-drive-removed'
    $checks.Add('owned-subst-drive-cleaned')

    $unicode=Join-Path $fixture ([string][char]0x4e2d + '-build');New-Item -ItemType Directory -Path $unicode | Out-Null
    Invoke-WithAndroidBuildState -ProjectRoot $unicode -Body {
        param($state)
        & subst.exe $drive $unicode
        if($LASTEXITCODE -ne 0) { throw 'Unicode fixture drive creation failed.' }
        $state.MappedDrive=$drive
    }
    Assert (-not (Test-Path -LiteralPath ($drive + '\'))) 'unicode-owned-drive-removed'
    $checks.Add('unicode-drive-target-preserved-with-native-api')

    $foreign=Join-Path $fixture 'foreign'; New-Item -ItemType Directory -Path $foreign | Out-Null
    & subst.exe $drive $foreign
    if($LASTEXITCODE -ne 0) { throw 'Foreign fixture drive creation failed.' }
    try {
        $refused=$false
        try { Remove-AndroidBuildDrive -Drive $drive -ProjectRoot $fixture } catch { $refused=$true }
        Assert $refused 'foreign-drive-refused'
        Assert (Test-Path -LiteralPath ($drive + '\')) 'foreign-drive-preserved'
    } finally { Remove-AndroidBuildDrive -Drive $drive -ProjectRoot $foreign }
    $checks.Add('foreign-subst-drive-preserved')

    $mutex=Enter-AndroidBuildLock -ProjectRoot $fixture
    try {
        $nested=Enter-AndroidBuildLock -ProjectRoot $fixture
        try { $checks.Add('same-process-nested-release-lock-supported') } finally { $nested.ReleaseMutex();$nested.Dispose() }
        $command=". '" + $helper.Replace("'","''") + "'; try { `$m=Enter-AndroidBuildLock -ProjectRoot '" + $fixture.Replace("'","''") + "'; `$m.ReleaseMutex(); `$m.Dispose(); exit 7 } catch { if (`$_.Exception.Message -like 'Another Android build*') { exit 0 }; exit 8 }"
        $start=New-Object Diagnostics.ProcessStartInfo
        $start.FileName=(Get-Process -Id $PID).Path
        $start.Arguments='-NoProfile -NonInteractive -EncodedCommand ' + [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($command))
        $start.UseShellExecute=$false;$start.CreateNoWindow=$true;$start.RedirectStandardOutput=$true;$start.RedirectStandardError=$true
        $child=[Diagnostics.Process]::Start($start)
        try {
            if(-not $child.WaitForExit(15000)) { $child.Kill();throw 'Lock fixture timed out.' }
            Assert ($child.ExitCode -eq 0) 'concurrent-build-refused'
        } finally { $child.Dispose() }
    } finally { $mutex.ReleaseMutex();$mutex.Dispose() }
    $checks.Add('other-process-build-refused')

    $outside=Join-Path $fixture 'must-stay';New-Item -ItemType Directory -Path $outside | Out-Null
    $refused=$false
    try { Remove-AndroidOwnedDirectory -Path $outside -Parent $fixture -Prefix 'owned-' } catch { $refused=$true }
    Assert ($refused -and (Test-Path -LiteralPath $outside)) 'unowned-directory-refused'
    $checks.Add('unowned-directory-cleanup-refused')

    [pscustomobject]@{Passed=$true;Checks=$checks.Count;Results=@($checks)} | ConvertTo-Json -Depth 3
} finally {
    foreach($name in $originalEnv.Keys) { [Environment]::SetEnvironmentVariable($name,$originalEnv[$name],'Process') }
    Remove-AndroidOwnedDirectory -Path $fixture -Parent $tempRoot -Prefix 'android-state-test-'
}
