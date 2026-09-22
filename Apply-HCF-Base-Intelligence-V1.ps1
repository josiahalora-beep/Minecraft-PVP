param(
  [switch]$ResetWorld
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$validatedCommit = '2a6fffc6fc3cdcff638eebfe8f56f6cf07ecea42'
$base = "https://raw.githubusercontent.com/josiahalora-beep/Minecraft-PVP/$validatedCommit"

Write-Host 'IMPORTANT: stop the Minecraft server and every worker/coordinator process before continuing.' -ForegroundColor Yellow
Write-Host "Pinned validated source: $validatedCommit" -ForegroundColor DarkGray

$required = @(
  (Join-Path $root 'server\spigot-1.8.8.jar'),
  (Join-Path $root 'server\java8-home.txt'),
  (Join-Path $root 'server\build-plugin.ps1'),
  (Join-Path $root 'bots\package.json')
)
foreach ($p in $required) {
  if (!(Test-Path $p)) { throw "Missing required project file: $p" }
}

$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$backup = Join-Path $root "base-intelligence-backup-$stamp"
New-Item -ItemType Directory -Force -Path $backup | Out-Null

$backupItems = @(
  'server\plugins\EraCore',
  'server\plugins\EraCore.jar',
  'server\plugins-src\EraCore',
  'bots\src\worker-pool.js',
  'server\world',
  'server\world_nether',
  'server\world_the_end'
)
Write-Host "Creating backup: $backup" -ForegroundColor Cyan
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
  'bots/src/worker-pool.js',
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

Write-Host 'Installing HCF base-intelligence source set...' -ForegroundColor Cyan
foreach ($remote in $files) {
  $local = $remote -replace '/', '\'
  $dest = Join-Path $root $local
  New-Item -ItemType Directory -Force -Path (Split-Path $dest -Parent) | Out-Null
  $tmp = "$dest.download"
  Invoke-WebRequest -UseBasicParsing "$base/$remote" -OutFile $tmp
  Move-Item $tmp $dest -Force
}

# Force the physical-base migration while preserving all authoritative simulation,
# faction, economy, rank and relationship state.
$simulation = Join-Path $root 'server\plugins\EraCore\simulation.yml'
if (Test-Path $simulation) {
  $sourceLines = @(Get-Content $simulation)
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
  Set-Content -Path $simulation -Value $outputLines -Encoding UTF8
}

if ($ResetWorld) {
  Write-Host 'ResetWorld selected: removing physical dimensions only. Simulation/economy/faction state is preserved.' -ForegroundColor Yellow
  foreach ($worldName in @('world','world_nether','world_the_end')) {
    $p = Join-Path $root "server\$worldName"
    if (Test-Path $p) { Remove-Item $p -Recurse -Force }
  }

  $runtime = Join-Path $root 'server\plugins\EraCore'
  foreach ($relative in @('infrastructure.yml','warps.yml','config.yml')) {
    $p = Join-Path $runtime $relative
    if (Test-Path $p) { Remove-Item $p -Force }
  }
}

Write-Host 'Validating Mineflayer worker syntax...' -ForegroundColor Cyan
Push-Location (Join-Path $root 'bots')
try {
  npm install --no-audit --no-fund
  if ($LASTEXITCODE -ne 0) { throw 'npm install failed.' }
  node --check .\src\worker-pool.js
  if ($LASTEXITCODE -ne 0) { throw 'worker-pool.js syntax validation failed.' }
} finally {
  Pop-Location
}

$javaHome = ([System.IO.File]::ReadAllText((Join-Path $root 'server\java8-home.txt'))).Trim()
$spigot = Join-Path $root 'server\spigot-1.8.8.jar'

Write-Host 'Rebuilding EraCore with Java 8...' -ForegroundColor Cyan
& (Join-Path $root 'server\build-plugin.ps1') -SpigotJar $spigot -JavaHome $javaHome
if ($LASTEXITCODE -ne 0) { throw 'EraCore rebuild failed.' }

Write-Host ''
Write-Host 'HCF Base Intelligence v1 installed.' -ForegroundColor Green
Write-Host "Backup: $backup" -ForegroundColor DarkGray
Write-Host "Validated source: $validatedCommit" -ForegroundColor DarkGray
Write-Host ''
if ($ResetWorld) {
  Write-Host 'Clean physical worlds will regenerate; saved simulation/faction/economy state was preserved.' -ForegroundColor Green
} else {
  Write-Host 'Existing physical worlds were preserved and base migration was forced.' -ForegroundColor Green
  Write-Host 'If legacy detached vault/brewer pieces remain outside the rebuilt footprint, rerun with -ResetWorld for a clean map.' -ForegroundColor DarkYellow
}
Write-Host ''
Write-Host 'Restart order:' -ForegroundColor Yellow
Write-Host '  1. Start the Minecraft server'
Write-Host '  2. Start the coordinator/control plane'
Write-Host '  3. Start local/Oracle workers'
Write-Host ''
Write-Host 'Useful checks:' -ForegroundColor Yellow
Write-Host '  /sotw status'
Write-Host '  /simprobe'
Write-Host '  /f show <faction>'
Write-Host '  /baserate 1-5 [faction]'
Write-Host '  /warps'
Write-Host ''
Write-Host 'New behavior: 2-4 personality-based surface gates, roofed SOTW shell,'
Write-Host 'dropdown-down/elevator-up transit, underground storage/farms/brewer,'
Write-Host 'storage growth, expensive faction portals, fall-trap avoidance,'
Write-Host 'and 10-second /spawn + /warp warmups.'
