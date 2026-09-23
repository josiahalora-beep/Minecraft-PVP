param(
    [int]$HomeBodies = 14,
    [int]$Priority = 10
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$launcher = Join-Path $root 'Start-Daegon-With-Workers.ps1'
if (!(Test-Path $launcher)) { throw "Missing $launcher" }

Write-Host 'Starting Daegon adaptive HCF stack...' -ForegroundColor Cyan
Write-Host 'The legacy 8771 Phase-1 control plane has been retired.' -ForegroundColor DarkGray
Write-Host 'Current adaptive control uses EraCore budget + the shared coordinator on 8770.' -ForegroundColor DarkGray

& $launcher -Bodies $HomeBodies -Priority $Priority -NodeId 'home-pc'
