param(
  [switch]$ResetWorld
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$validatedCommit = '7d5156a6bd9bb926f031351c69cf1a4bee9dedf3'
$base = "https://raw.githubusercontent.com/josiahalora-beep/Minecraft-PVP/$validatedCommit"

Write-Host 'IMPORTANT: stop the Minecraft server and all worker/coordinator processes before running this updater.' -ForegroundColor Yellow
Write-Host "Pinned source: $validatedCommit" -ForegroundColor DarkGray

$required = @(
  (Join-Path $root 'server\spigot-1.8.8.jar'),
  (Join-Path $root 'server\java8-home.txt'),
  (Join-Path $root 'server\build-plugin.ps1'),
  (Join-Path $root 'bots\package.json')
)
foreach ($p in $required) {
  if (!(Test-Path $p)) { throw "Missing required Phase-0 file: $p" }
}

$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$backup = Join-Path $root "community-intelligence-backup-$stamp"
New-Item -ItemType Directory -Force -Path $backup | Out-Null

$backupItems = @(
  'server\plugins\EraCore',
  'server\plugins\EraCore.jar',
  'server\plugins-src\EraCore',
  'server\world',
  'server\world_nether',
  'server\world_the_end',
  'bots\src',
  'bots\package.json',
  'Start-HCF-ControlPlane.ps1',
  'Start-HCF-LocalWorker.ps1'
)

Write-Host "Creating safety backup: $backup" -ForegroundColor Cyan
foreach ($relative in $backupItems) {
  $src = Join-Path $root $relative
  if (!(Test-Path $src)) { continue }
  $dest = Join-Path $backup $relative
  New-Item -ItemType Directory -Force -Path (Split-Path $dest -Parent) | Out-Null
  Copy-Item $src $dest -Recurse -Force
}

$eraSourceDir = Join-Path $root 'server\plugins-src\EraCore\src\main\java\dev\jorel\eracore'
if (Test-Path $eraSourceDir) {
  Write-Host 'Removing mixed/stale EraCore Java sources after backup...' -ForegroundColor Cyan
  Remove-Item $eraSourceDir -Recurse -Force
}
New-Item -ItemType Directory -Force -Path $eraSourceDir | Out-Null

$files = @(
  'bots/package.json',
  'bots/src/combat-profiles.js',
  'bots/src/common.js',
  'bots/src/community-ai.js',
  'bots/src/distributed-smoke-test.js',
  'bots/src/duel-bot.js',
  'bots/src/idle-benchmark.js',
  'bots/src/state-handoff.js',
  'bots/src/team-combat.js',
  'bots/src/worker-coordinator.js',
  'bots/src/worker-pool.js',
  'bots/start-worker-linux.sh',
  'bots/setup-linux-worker.sh',
  'Start-HCF-ControlPlane.ps1',
  'Start-HCF-LocalWorker.ps1',
  'server/server.properties',
  'server/build-plugin.ps1',
  'server/plugins-src/EraCore/pom.xml',
  'server/plugins-src/EraCore/src/main/java/dev/jorel/eracore/AiChatBridge.java',
  'server/plugins-src/EraCore/src/main/java/dev/jorel/eracore/ContextChatBrain.java',
  'server/plugins-src/EraCore/src/main/java/dev/jorel/eracore/EraCore.java',
  'server/plugins-src/EraCore/src/main/java/dev/jorel/eracore/HcfAutoBrewerDirector.java',
  'server/plugins-src/EraCore/src/main/java/dev/jorel/eracore/HcfBaseBuilder.java',
  'server/plugins-src/EraCore/src/main/java/dev/jorel/eracore/HcfBasePlan.java',
  'server/plugins-src/EraCore/src/main/java/dev/jorel/eracore/HcfClassDirector.java',
  'server/plugins-src/EraCore/src/main/java/dev/jorel/eracore/HcfElevatorDirector.java',
  'server/plugins-src/EraCore/src/main/java/dev/jorel/eracore/HcfGateDirector.java',
  'server/plugins-src/EraCore/src/main/java/dev/jorel/eracore/HcfInfrastructureDirector.java',
  'server/plugins-src/EraCore/src/main/java/dev/jorel/eracore/HcfPortalDirector.java',
  'server/plugins-src/EraCore/src/main/java/dev/jorel/eracore/HcfTerrainDirector.java',
  'server/plugins-src/EraCore/src/main/java/dev/jorel/eracore/HcfTravelDirector.java',
  'server/plugins-src/EraCore/src/main/java/dev/jorel/eracore/HcfZoneDisplayDirector.java',
  'server/plugins-src/EraCore/src/main/java/dev/jorel/eracore/LogicalTabListDirector.java',
  'server/plugins-src/EraCore/src/main/java/dev/jorel/eracore/SimChatDirector.java',
  'server/plugins-src/EraCore/src/main/java/dev/jorel/eracore/SimEconomyModel.java',
  'server/plugins-src/EraCore/src/main/java/dev/jorel/eracore/SimWorldDirector.java',
  'server/plugins-src/EraCore/src/main/java/dev/jorel/eracore/SpawnPresenceDirector.java',
  'server/plugins-src/EraCore/src/main/java/dev/jorel/eracore/SpawnRewardsDirector.java',
  'server/plugins-src/EraCore/src/main/java/dev/jorel/eracore/WarpManager.java',
  'server/plugins-src/EraCore/src/main/resources/config.yml',
  'server/plugins-src/EraCore/src/main/resources/plugin.yml'
)

Write-Host 'Downloading coordinated HCF source set...' -ForegroundColor Cyan
foreach ($remote in $files) {
  $dest = Join-Path $root ($remote -replace '/', '\')
  New-Item -ItemType Directory -Force -Path (Split-Path $dest -Parent) | Out-Null
  $tmp = "$dest.download"
  Invoke-WebRequest -UseBasicParsing "$base/$remote" -OutFile $tmp
  Move-Item $tmp $dest -Force
}

function Reset-BaseRepairMarker {
  param([string]$SimulationPath)
  if (!(Test-Path $SimulationPath)) { return }

  $sourceLines = @(Get-Content $SimulationPath)
  $outputLines = New-Object System.Collections.Generic.List[string]
  $versionFound = $false
  $metaSeen = $false

  foreach ($line in $sourceLines) {
    if ($line -match '^\s*terrain-repair-version:\s*\d+\s*$') {
      $indent = ([regex]::Match($line,'^\s*')).Value
      $outputLines.Add($indent + 'terrain-repair-version: 0')
      $versionFound = $true
      continue
    }

    $outputLines.Add($line)
    if (!$metaSeen -and $line.Trim() -eq 'meta:') {
      $metaSeen = $true
      if (!$versionFound) {
        $outputLines.Add('  terrain-repair-version: 0')
        $versionFound = $true
      }
    }
  }

  if (!$versionFound) {
    $outputLines.Add('meta:')
    $outputLines.Add('  terrain-repair-version: 0')
  }
  Set-Content -Path $SimulationPath -Value $outputLines -Encoding UTF8
}

$runtime = Join-Path $root 'server\plugins\EraCore'
Reset-BaseRepairMarker -SimulationPath (Join-Path $runtime 'simulation.yml')

if ($ResetWorld) {
  Write-Host 'ResetWorld selected: replacing physical worlds after backup.' -ForegroundColor Yellow
  foreach ($worldName in @('world','world_nether','world_the_end')) {
    $worldPath = Join-Path $root "server\$worldName"
    if (Test-Path $worldPath) { Remove-Item $worldPath -Recurse -Force }
  }
  foreach ($relative in @('infrastructure.yml','warps.yml','config.yml')) {
    $p = Join-Path $runtime $relative
    if (Test-Path $p) { Remove-Item $p -Force }
  }
}

Write-Host 'Installing/validating Mineflayer dependencies...' -ForegroundColor Cyan
Push-Location (Join-Path $root 'bots')
try {
  npm install --no-audit --no-fund
  if ($LASTEXITCODE -ne 0) { throw 'npm install failed.' }
  Get-ChildItem .\src\*.js | ForEach-Object {
    node --check $_.FullName
    if ($LASTEXITCODE -ne 0) { throw "JavaScript syntax check failed: $($_.Name)" }
  }
} finally {
  Pop-Location
}

$javaHome = ([System.IO.File]::ReadAllText((Join-Path $root 'server\java8-home.txt'))).Trim()
$spigot = Join-Path $root 'server\spigot-1.8.8.jar'

Write-Host 'Rebuilding EraCore with Java 8...' -ForegroundColor Cyan
& (Join-Path $root 'server\build-plugin.ps1') -SpigotJar $spigot -JavaHome $javaHome
if ($LASTEXITCODE -ne 0) { throw 'EraCore rebuild failed.' }

Write-Host ''
Write-Host 'HCF community intelligence + Base Intelligence v1 installed.' -ForegroundColor Green
Write-Host "Backup: $backup" -ForegroundColor DarkGray
Write-Host "Pinned source: $validatedCommit" -ForegroundColor DarkGray
if ($ResetWorld) {
  Write-Host 'Physical worlds will regenerate; simulation/rank/economy/faction/social state was preserved.' -ForegroundColor Green
} else {
  Write-Host 'Worlds were preserved and the v8 physical-base migration was forced.' -ForegroundColor Green
  Write-Host 'Use -ResetWorld if legacy detached structures remain around old faction bases.' -ForegroundColor DarkYellow
}

Write-Host ''
Write-Host 'Restart order:' -ForegroundColor Yellow
Write-Host '  1. Minecraft server'
Write-Host '  2. Home coordinator/control plane'
Write-Host '  3. Local and Oracle workers'
Write-Host ''
Write-Host 'Checks:' -ForegroundColor Yellow
Write-Host '  /sotw status'
Write-Host '  /simprobe'
Write-Host '  /f show <faction>'
Write-Host '  /baserate 1-5 [faction]'
Write-Host '  /warps'
