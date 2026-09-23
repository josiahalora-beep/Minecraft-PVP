param(
    [int]$Bodies = 14,
    [int]$Priority = 10,
    [string]$NodeId = 'home-pc'
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$server = Join-Path $root 'server'
$serverBat = Join-Path $server 'start-server.bat'
$bots = Join-Path $root 'bots'

if (!(Test-Path $serverBat)) { throw "Missing $serverBat" }
if (!(Test-Path (Join-Path $bots 'package.json'))) { throw "Missing bots\package.json" }

function Port-IsOpen([int]$Port) {
    try {
        $client = New-Object System.Net.Sockets.TcpClient
        $async = $client.BeginConnect('127.0.0.1',$Port,$null,$null)
        $ok = $async.AsyncWaitHandle.WaitOne(600)
        if (!$ok) { $client.Close(); return $false }
        $client.EndConnect($async)
        $client.Close()
        return $true
    } catch { return $false }
}

function Node-ProcessRunning([string]$Pattern) {
    try {
        return @(Get-CimInstance Win32_Process -Filter "Name='node.exe'" -ErrorAction Stop |
            Where-Object { $_.CommandLine -match $Pattern }).Count -gt 0
    } catch { return $false }
}

if (!(Port-IsOpen 25565)) {
    Write-Host 'Starting Minecraft server...' -ForegroundColor Cyan
    Start-Process -FilePath 'cmd.exe' -ArgumentList '/k',('"' + $serverBat + '"') -WorkingDirectory $server
}

Write-Host 'Waiting for Minecraft 127.0.0.1:25565...' -ForegroundColor DarkGray
$tries=0
while (!(Port-IsOpen 25565) -and $tries -lt 120) {
    Start-Sleep -Seconds 1
    $tries++
}
if (!(Port-IsOpen 25565)) { throw 'Minecraft server did not open port 25565.' }

if (!(Node-ProcessRunning 'worker-coordinator\.js')) {
    Write-Host 'Starting HCF worker coordinator on 127.0.0.1:8770...' -ForegroundColor Cyan
    $coord='cd /d "'+$bots+'" && npm run coordinator'
    Start-Process -FilePath 'cmd.exe' -ArgumentList '/k',$coord -WorkingDirectory $bots
}

$tries=0
while (!(Port-IsOpen 8770) -and $tries -lt 30) {
    Start-Sleep -Milliseconds 500
    $tries++
}
if (!(Port-IsOpen 8770)) { throw 'Worker coordinator did not open port 8770.' }

if (!(Node-ProcessRunning 'worker-pool\.js')) {
    Write-Host "Starting adaptive home worker '$NodeId' (hard capacity $Bodies)..." -ForegroundColor Cyan
    $cmd = @(
        'set "WORKER_COORDINATOR_URL=http://127.0.0.1:8770"',
        'set "WORKER_NODE_ID='+$NodeId+'"',
        'set "WORKER_NODE_PRIORITY='+$Priority+'"',
        'set "WORKER_MAX='+$Bodies+'"',
        'set "MC_HOST=127.0.0.1"',
        'set "MC_PORT=25565"',
        'set "HCF_AI_LOCAL=0"',
        'cd /d "'+$bots+'"',
        'npm run workers'
    ) -join ' && '
    Start-Process -FilePath 'cmd.exe' -ArgumentList '/k',$cmd -WorkingDirectory $bots
} else {
    Write-Host 'Mineflayer worker pool is already running.' -ForegroundColor Yellow
}

Write-Host ''
Write-Host 'DAEGON HCF STACK IS RUNNING.' -ForegroundColor Green
Write-Host 'Coordinator: http://127.0.0.1:8770' -ForegroundColor DarkGray
Write-Host "Home node: $NodeId  hard capacity=$Bodies  priority=$Priority" -ForegroundColor DarkGray
Write-Host 'Actual HOT body count remains adaptive to EraCore/server budget and node load.' -ForegroundColor Green
