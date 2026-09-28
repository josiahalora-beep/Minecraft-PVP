param(
    [int]$Bodies = 14,
    [int]$Priority = 10,
    [string]$NodeId = 'home-pc'
)

$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$server = Join-Path $root 'server'
$serverBat = Join-Path $server 'start-server.bat'
$spigotJar = Join-Path $server 'spigot-1.8.8.jar'
$javaHomeFile = Join-Path $server 'java8-home.txt'
$buildPlugin = Join-Path $server 'build-plugin.ps1'
$bots = Join-Path $root 'bots'
$simDir = Join-Path $server 'plugins\EraCore'
$simFile = Join-Path $simDir 'simulation.yml'
$lastGoodFile = Join-Path $simDir 'simulation.lastgood.yml'

if (!(Test-Path $serverBat)) { throw "Missing $serverBat" }
if (!(Test-Path $spigotJar)) { throw "Missing $spigotJar" }
if (!(Test-Path $javaHomeFile)) { throw "Missing $javaHomeFile" }
if (!(Test-Path $buildPlugin)) { throw "Missing $buildPlugin" }
if (!(Test-Path (Join-Path $bots 'package.json'))) { throw "Missing bots\package.json" }

$nodeCmd = Get-Command node.exe -ErrorAction SilentlyContinue
$npmCmd = Get-Command npm.cmd -ErrorAction SilentlyContinue
if ($null -eq $nodeCmd) { throw 'Node.js is not available in PATH.' }
if ($null -eq $npmCmd) { throw 'npm.cmd is not available in PATH.' }

$logs = Join-Path $root 'logs'
New-Item -ItemType Directory -Path $logs -Force | Out-Null
$coordOut = Join-Path $logs 'coordinator.out.log'
$coordErr = Join-Path $logs 'coordinator.err.log'
$workerOut = Join-Path $logs 'worker.out.log'
$workerErr = Join-Path $logs 'worker.err.log'
$ollamaOut = Join-Path $logs 'ollama.out.log'
$ollamaErr = Join-Path $logs 'ollama.err.log'

function Port-IsOpen([int]$Port) {
    try {
        $client = New-Object System.Net.Sockets.TcpClient
        $async = $client.BeginConnect('127.0.0.1', $Port, $null, $null)
        $ok = $async.AsyncWaitHandle.WaitOne(600)
        if (!$ok) {
            $client.Close()
            return $false
        }
        $client.EndConnect($async)
        $client.Close()
        return $true
    }
    catch {
        return $false
    }
}

function Node-ProcessRunning([string]$Pattern) {
    try {
        return @(
            Get-CimInstance Win32_Process -Filter "Name='node.exe'" -ErrorAction Stop |
            Where-Object { $_.CommandLine -match $Pattern }
        ).Count -gt 0
    }
    catch {
        return $false
    }
}

function Stop-ProjectNodeProcesses {
    try {
        $targets = @(Get-CimInstance Win32_Process -Filter "Name='node.exe'" -ErrorAction Stop |
            Where-Object {
                $_.CommandLine -match 'worker-coordinator\.js' -or
                $_.CommandLine -match 'worker-pool\.js'
            })
        foreach ($proc in $targets) {
            Write-Host "Stopping stale HCF Node process PID $($proc.ProcessId)..." -ForegroundColor DarkGray
            Stop-Process -Id $proc.ProcessId -Force -ErrorAction SilentlyContinue
        }
        if ($targets.Count -gt 0) { Start-Sleep -Milliseconds 800 }
    }
    catch {}
}

function Find-Ollama {
    $cmd = Get-Command ollama.exe -ErrorAction SilentlyContinue
    if ($null -ne $cmd) { return $cmd.Source }

    $candidate = Join-Path $env:LOCALAPPDATA 'Programs\Ollama\ollama.exe'
    if (Test-Path $candidate) { return $candidate }

    return $null
}

function Start-LocalOllama {
    $env:HCF_AI_PROVIDER = 'ollama'
    $env:HCF_OLLAMA_URL = 'http://127.0.0.1:11434'
    $env:HCF_OLLAMA_MODEL = 'qwen3:4b'

    if (Port-IsOpen 11434) {
        Write-Host 'Ollama is already available on 127.0.0.1:11434.' -ForegroundColor Green
        return
    }

    $ollama = Find-Ollama
    if ([string]::IsNullOrWhiteSpace($ollama)) {
        Write-Host 'Ollama executable was not found; community AI will use deterministic fallback.' -ForegroundColor Yellow
        return
    }

    Write-Host 'Starting local Ollama for HCF community AI...' -ForegroundColor Cyan
    Remove-Item $ollamaOut, $ollamaErr -Force -ErrorAction SilentlyContinue
    Start-Process -FilePath $ollama -ArgumentList 'serve' -WindowStyle Hidden -RedirectStandardOutput $ollamaOut -RedirectStandardError $ollamaErr | Out-Null

    $ollamaTries = 0
    while (!(Port-IsOpen 11434) -and $ollamaTries -lt 40) {
        Start-Sleep -Milliseconds 500
        $ollamaTries++
    }

    if (Port-IsOpen 11434) {
        Write-Host 'Ollama ready: qwen3:4b on 127.0.0.1:11434.' -ForegroundColor Green
    }
    else {
        Write-Host 'Ollama did not open port 11434; community AI will use deterministic fallback.' -ForegroundColor Yellow
        if (Test-Path $ollamaErr) {
            Get-Content $ollamaErr -Tail 12 | ForEach-Object { Write-Host $_ -ForegroundColor DarkGray }
        }
    }
}

function Backup-File([string]$Source, [string]$Label) {
    $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
    $backup = "$Source.$Label-$stamp.bak"
    Copy-Item -LiteralPath $Source -Destination $backup -Force
    return $backup
}

function Repair-SimulationState {
    if (!(Test-Path $simFile)) {
        return
    }

    $utf8 = New-Object System.Text.UTF8Encoding($false)
    $text = [System.IO.File]::ReadAllText($simFile, [System.Text.Encoding]::UTF8)
    $text = $text.TrimStart([char]0xFEFF)
    $lines = @($text -split "\r?\n")

    $hasMeta = @($lines | Where-Object { $_ -match '^meta:\s*$' }).Count -gt 0
    $badMarkers = @($lines | Where-Object { $_ -match '^\s*:\s*terrain-repair-version:\s*\d+\s*$' })
    $hasIndentedSchema = @($lines | Where-Object { $_ -match '^\s{2,}schema:\s*4\s*$' }).Count -gt 0
    $hasLiteralBomEscapes = $text.Contains('\xef\xbb\xbf')

    $looksBroken = $hasLiteralBomEscapes -or
                   (($badMarkers.Count -gt 0 -or $hasIndentedSchema) -and !$hasMeta)

    if ($looksBroken -and (Test-Path $lastGoodFile)) {
        $backup = Backup-File $simFile 'pre-lastgood-restore'
        Copy-Item -LiteralPath $lastGoodFile -Destination $simFile -Force
        Write-Host 'Restored EraCore simulation.lastgood.yml over malformed simulation.yml.' -ForegroundColor Yellow
        Write-Host "Malformed copy preserved at: $backup" -ForegroundColor DarkGray
        return
    }

    if ($badMarkers.Count -eq 1 -and !$hasMeta) {
        $backup = Backup-File $simFile 'pre-meta-repair'
        $fixedLines = New-Object System.Collections.Generic.List[string]
        foreach ($line in $lines) {
            if ($line -match '^\s*:\s*terrain-repair-version:\s*\d+\s*$') {
                $fixedLines.Add('meta:')
            }
            else {
                $fixedLines.Add($line)
            }
        }
        [System.IO.File]::WriteAllLines($simFile, $fixedLines.ToArray(), $utf8)
        Write-Host 'Restored missing meta: YAML parent in simulation.yml.' -ForegroundColor Yellow
        Write-Host "Backup: $backup" -ForegroundColor DarkGray
        return
    }

    if ($badMarkers.Count -gt 1) {
        throw 'simulation.yml contains multiple malformed terrain markers; refusing automatic repair.'
    }
}

if (Port-IsOpen 25565) {
    Write-Host ''
    Write-Host 'Minecraft is already running on port 25565.' -ForegroundColor Yellow
    Write-Host 'Type stop in the Minecraft server console first, then run this launcher again.' -ForegroundColor Yellow
    Write-Host 'The launcher will not force-kill Java because that can damage saved state.' -ForegroundColor DarkGray
    exit 2
}

Repair-SimulationState
Stop-ProjectNodeProcesses
Start-LocalOllama

$javaHome = (Get-Content $javaHomeFile -Raw).Trim()
if ([string]::IsNullOrWhiteSpace($javaHome)) {
    throw 'server\java8-home.txt is empty.'
}

Write-Host 'Rebuilding latest EraCore...' -ForegroundColor Cyan
& $buildPlugin -SpigotJar $spigotJar -JavaHome $javaHome
if ($LASTEXITCODE -ne 0) {
    throw 'EraCore build failed.'
}

Write-Host 'Starting Minecraft server...' -ForegroundColor Cyan
Start-Process -FilePath 'cmd.exe' -ArgumentList '/k', ('call "' + $serverBat + '"') -WorkingDirectory $server

Write-Host 'Waiting for Minecraft 127.0.0.1:25565...' -ForegroundColor DarkGray
$tries = 0
while (!(Port-IsOpen 25565) -and $tries -lt 120) {
    Start-Sleep -Seconds 1
    $tries++
}
if (!(Port-IsOpen 25565)) {
    throw 'Minecraft server did not open port 25565.'
}

if (!(Test-Path (Join-Path $bots 'node_modules\yaml'))) {
    Write-Host 'Installing Mineflayer runtime dependencies...' -ForegroundColor Cyan
    Push-Location $bots
    try {
        & $npmCmd.Source install --no-audit --no-fund
        if ($LASTEXITCODE -ne 0) {
            throw 'npm install failed for bots runtime.'
        }
    }
    finally {
        Pop-Location
    }
}

if (!(Node-ProcessRunning 'worker-coordinator\.js')) {
    Write-Host 'Starting HCF worker coordinator on 127.0.0.1:8770...' -ForegroundColor Cyan
    Remove-Item $coordOut, $coordErr -Force -ErrorAction SilentlyContinue
    Start-Process -FilePath $nodeCmd.Source -ArgumentList '.\src\worker-coordinator.js' -WorkingDirectory $bots -RedirectStandardOutput $coordOut -RedirectStandardError $coordErr | Out-Null
}

$tries = 0
while (!(Port-IsOpen 8770) -and $tries -lt 60) {
    Start-Sleep -Milliseconds 500
    $tries++
}
if (!(Port-IsOpen 8770)) {
    Write-Host ''
    Write-Host 'Coordinator failed to open 8770. Last output:' -ForegroundColor Red
    if (Test-Path $coordOut) {
        Get-Content $coordOut -Tail 30 | ForEach-Object { Write-Host $_ }
    }
    if (Test-Path $coordErr) {
        Get-Content $coordErr -Tail 30 | ForEach-Object { Write-Host $_ -ForegroundColor Red }
    }
    throw "Worker coordinator did not open port 8770. Logs: $coordOut / $coordErr"
}

if (!(Node-ProcessRunning 'worker-pool\.js')) {
    Write-Host "Starting adaptive home worker '$NodeId' (hard capacity $Bodies)..." -ForegroundColor Cyan
    $env:WORKER_COORDINATOR_URL = 'http://127.0.0.1:8770'
    $env:WORKER_NODE_ID = $NodeId
    $env:WORKER_NODE_PRIORITY = [string]$Priority
    $env:WORKER_MAX = [string]$Bodies
    $env:MC_HOST = '127.0.0.1'
    $env:MC_PORT = '25565'
    $env:HCF_AI_LOCAL = '0'

    Remove-Item $workerOut, $workerErr -Force -ErrorAction SilentlyContinue
    Start-Process -FilePath $nodeCmd.Source -ArgumentList '.\src\worker-pool.js' -WorkingDirectory $bots -RedirectStandardOutput $workerOut -RedirectStandardError $workerErr | Out-Null
}
else {
    Write-Host 'Mineflayer worker pool is already running.' -ForegroundColor Yellow
}

Write-Host ''
Write-Host 'DAEGON HCF STACK IS RUNNING.' -ForegroundColor Green
Write-Host 'Coordinator: http://127.0.0.1:8770' -ForegroundColor DarkGray
Write-Host "Home node: $NodeId  hard capacity=$Bodies  priority=$Priority" -ForegroundColor DarkGray
Write-Host 'Actual HOT body count remains adaptive to EraCore/server budget and node load.' -ForegroundColor Green
Write-Host "Worker logs: $workerOut / $workerErr" -ForegroundColor DarkGray
