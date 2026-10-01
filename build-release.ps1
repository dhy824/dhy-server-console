[CmdletBinding()]
param([ValidateSet('Public','Personal')][string]$Mode = 'Public')

$ErrorActionPreference = 'Stop'
& (Join-Path $PSScriptRoot 'build-debug.ps1') -Release -Mode $Mode
