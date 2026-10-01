# Shared build transaction. Dot-source this file; it never builds or reads keys.
Set-StrictMode -Version Latest

function Enter-AndroidBuildLock {
    param([Parameter(Mandatory=$true)][string]$ProjectRoot)
    $canonical = [IO.Path]::GetFullPath($ProjectRoot).TrimEnd('\').ToLowerInvariant()
    $hash = [Security.Cryptography.SHA256]::Create()
    try { $id = ([BitConverter]::ToString($hash.ComputeHash([Text.Encoding]::UTF8.GetBytes($canonical)))).Replace('-','') } finally { $hash.Dispose() }
    $mutex = New-Object Threading.Mutex($false, ('Local\AmadeusAndroidBuild-' + $id))
    $owned = $false
    try {
        try { $owned = $mutex.WaitOne(0) } catch [Threading.AbandonedMutexException] { $owned = $true }
        if (-not $owned) { throw 'Another Android build or release is already using this source tree.' }
        return $mutex
    } catch { $mutex.Dispose(); throw }
}

function Remove-AndroidOwnedDirectory {
    param(
        [Parameter(Mandatory=$true)][string]$Path,
        [Parameter(Mandatory=$true)][string]$Parent,
        [Parameter(Mandatory=$true)][string]$Prefix
    )
    $full = [IO.Path]::GetFullPath($Path).TrimEnd('\')
    $root = [IO.Path]::GetFullPath($Parent).TrimEnd('\')
    if (-not [string]::Equals([IO.Path]::GetDirectoryName($full),$root,[StringComparison]::OrdinalIgnoreCase) -or
        [IO.Path]::GetFileName($full) -notmatch ('^' + [regex]::Escape($Prefix) + '[0-9a-f]{32}$')) {
        throw 'Refusing cleanup outside the exact owned temporary directory.'
    }
    if (-not (Test-Path -LiteralPath $full)) { return }
    $pending = New-Object 'System.Collections.Generic.Stack[string]'
    $pending.Push($full)
    while ($pending.Count -gt 0) {
        $entry = Get-Item -LiteralPath $pending.Pop() -Force -ErrorAction Stop
        if (($entry.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { throw 'Refusing recursive cleanup through a reparse point.' }
        if ($entry.PSIsContainer) {
            foreach ($child in (Get-ChildItem -LiteralPath $entry.FullName -Force -ErrorAction Stop)) { $pending.Push($child.FullName) }
        }
    }
    Remove-Item -LiteralPath $full -Recurse -Force -ErrorAction Stop
}

function Remove-AndroidBuildDrive {
    param([string]$Drive, [string]$ProjectRoot)
    if ($Drive -notmatch '^[A-Za-z]:$') { throw 'Invalid owned build drive.' }
    if (-not ('AndroidBuild.NativeMethods' -as [type])) {
        Add-Type -TypeDefinition @'
using System;
using System.Text;
using System.Runtime.InteropServices;
namespace AndroidBuild {
 public static class NativeMethods {
  [DllImport("kernel32.dll", CharSet=CharSet.Unicode, SetLastError=true)]
  public static extern uint QueryDosDevice(string name, StringBuilder target, int length);
  [DllImport("kernel32.dll", CharSet=CharSet.Unicode, SetLastError=true)]
  [return: MarshalAs(UnmanagedType.Bool)]
  public static extern bool DefineDosDevice(uint flags, string name, string target);
 }
}
'@
    }
    $buffer=New-Object Text.StringBuilder(32768)
    if ([AndroidBuild.NativeMethods]::QueryDosDevice($Drive,$buffer,$buffer.Capacity) -eq 0) { throw 'Unable to inspect temporary drive ownership.' }
    $raw=$buffer.ToString().Split([char]0)[0]
    $prefix='\??\'
    if (-not $raw.StartsWith($prefix,[StringComparison]::Ordinal)) { throw 'This drive is not an owned directory mapping.' }
    $target=$raw.Substring($prefix.Length)
    if (-not [string]::Equals([IO.Path]::GetFullPath($target).TrimEnd('\'),[IO.Path]::GetFullPath($ProjectRoot).TrimEnd('\'),[StringComparison]::OrdinalIgnoreCase)) {
        throw 'Temporary drive ownership changed; refusing to remove it.'
    }
    # RAW_TARGET_PATH | REMOVE_DEFINITION | EXACT_MATCH_ON_REMOVE.
    if (-not [AndroidBuild.NativeMethods]::DefineDosDevice(7,$Drive,$raw)) { throw 'Unable to remove the exact owned temporary build drive.' }
}

function Invoke-WithAndroidBuildState {
    param(
        [Parameter(Mandatory=$true)][string]$ProjectRoot,
        [Parameter(Mandatory=$true)][scriptblock]$Body
    )
    $mutex = Enter-AndroidBuildLock -ProjectRoot $ProjectRoot
    $savedFiles = @()
    $savedEnvironment = @{}
    $state = [pscustomobject]@{ ProjectRoot=$ProjectRoot; MappedDrive=$null; LocationPushed=$false }
    $cleanupErrors = New-Object 'System.Collections.Generic.List[string]'
    $bodyFailure = $null
    try {
        foreach ($name in @('JAVA_HOME','ANDROID_SDK_ROOT','ANDROID_HOME')) { $savedEnvironment[$name] = [Environment]::GetEnvironmentVariable($name,'Process') }
        foreach ($name in @('default-config.properties','local.properties')) {
            $path = Join-Path $ProjectRoot $name
            $exists = Test-Path -LiteralPath $path -PathType Leaf
            if ((Test-Path -LiteralPath $path) -and -not $exists) { throw 'A build configuration path is not a regular file.' }
            if ($exists -and (((Get-Item -LiteralPath $path -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0)) { throw 'A build configuration file is a reparse point.' }
            $bytes = $null
            if ($exists) { $bytes = [IO.File]::ReadAllBytes($path) }
            $savedFiles += [pscustomobject]@{ Path=$path; Existed=$exists; Bytes=[byte[]]$bytes }
        }
        & $Body $state
    } catch { $bodyFailure = $_ } finally {
        if ($state.LocationPushed) { try { Pop-Location -ErrorAction Stop } catch { $cleanupErrors.Add('working-directory') } }
        if ($state.MappedDrive) { try { Remove-AndroidBuildDrive -Drive $state.MappedDrive -ProjectRoot $ProjectRoot } catch { $cleanupErrors.Add('temporary-drive') } }
        foreach ($saved in $savedFiles) {
            try {
                if ((Test-Path -LiteralPath $saved.Path) -and (((Get-Item -LiteralPath $saved.Path -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0)) { throw 'Build configuration ownership changed.' }
                if ($saved.Existed) {
                    [IO.File]::WriteAllBytes($saved.Path,$saved.Bytes)
                } elseif (Test-Path -LiteralPath $saved.Path -PathType Leaf) {
                    Remove-Item -LiteralPath $saved.Path -Force -ErrorAction Stop
                }
            } catch { $cleanupErrors.Add([IO.Path]::GetFileName($saved.Path)) }
        }
        foreach ($name in $savedEnvironment.Keys) {
            try { [Environment]::SetEnvironmentVariable($name,$savedEnvironment[$name],'Process') } catch { $cleanupErrors.Add($name) }
        }
        try { $mutex.ReleaseMutex() } finally { $mutex.Dispose() }
        if ($cleanupErrors.Count -gt 0) {
            $message='Android build cleanup incomplete: ' + ($cleanupErrors -join ', ') + '. Inspect these items before rebuilding.'
            if ($bodyFailure) { throw (New-Object InvalidOperationException($message,$bodyFailure.Exception)) }
            throw $message
        }
    }
    if ($bodyFailure) { throw $bodyFailure }
}
