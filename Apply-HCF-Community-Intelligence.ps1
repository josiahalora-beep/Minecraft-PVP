param(
  [switch]$ResetWorld
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$validatedCommit = 'ca866ea6228a220378bf7c728955febdc15b66cd'
$base = "https://raw.githubusercontent.com/josiahalora-beep/Minecraft-PVP/$validatedCommit"

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

Write-Host "Validated source commit: $validatedCommit" -ForegroundColor DarkGray
Write-Host "Creating safety backup..." -ForegroundColor Cyan

$stateItems = @(
  'server\plugins\EraCore',
  'server\plugins\EraCore.jar',
  'server\world',
  'server\world_nether',
  'server\world_the_end',
  'server\plugins-src\EraCore',
  'bots\src',
  'bots\package.json',
  'server\build-plugin.ps1'
)

foreach ($relative in $stateItems) {
  $src = Join-Path $root $relative
  if (!(Test-Path $src)) { continue }
  $dest = Join-Path $backup $relative
  $parent = Split-Path $dest -Parent
  New-Item -ItemType Directory -Force -Path $parent | Out-Null
  Copy-Item $src $dest -Recurse -Force
}

$files = @(
  'bots/package.json',
  'bots/src/combat-profiles.js',
  'bots/src/common.js',
  'bots/src/community-ai.js',
  'bots/src/duel-bot.js',
  'bots/src/idle-benchmark.js',
  'bots/src/state-handoff.js',
  'bots/src/team-combat.js',
  'bots/src/worker-pool.js',
  'server/build-plugin.ps1',
  'server/plugins-src/EraCore/pom.xml',
  'server/plugins-src/EraCore/src/main/java/dev/jorel/eracore/AiChatBridge.java',
  'server/plugins-src/EraCore/src/main/java/dev/jorel/eracore/ContextChatBrain.java',
  'server/plugins-src/EraCore/src/main/java/dev/jorel/eracore/EraCore.java',
  'server/plugins-src/EraCore/src/main/java/dev/jorel/eracore/HcfAutoBrewerDirector.java',
  'server/plugins-src/EraCore/src/main/java/dev/jorel/eracore/HcfBaseBuilder.java',
  'server/plugins-src/EraCore/src/main/java/dev/jorel/eracore/HcfInfrastructureDirector.java',
  'server/plugins-src/EraCore/src/main/java/dev/jorel/eracore/HcfGateDirector.java',
  'server/plugins-src/EraCore/src/main/java/dev/jorel/eracore/HcfTerrainDirector.java',
  'server/plugins-src/EraCore/src/main/java/dev/jorel/eracore/HcfClassDirector.java',
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

Write-Host "Downloading coordinated source set..." -ForegroundColor Cyan
foreach ($remote in $files) {
  $local = $remote -replace '/', '\'
  $dest = Join-Path $root $local
  $dir = Split-Path $dest -Parent
  New-Item -ItemType Directory -Force -Path $dir | Out-Null
  $tmp = "$dest.download"
  Invoke-WebRequest -UseBasicParsing "$base/$remote" -OutFile $tmp
  Move-Item $tmp $dest -Force
}

if ($ResetWorld) {
  Write-Host "ResetWorld requested: replacing legacy physical worlds after backup..." -ForegroundColor Yellow

  # The simulation/economy/ranks stay persistent. Only physical terrain and
  # generated infrastructure state are reset, so factions can rematerialize on
  # clean low-relief terrain using their saved templates.
  foreach ($worldName in @('world','world_nether','world_the_end')) {
    $worldPath = Join-Path $root "server\$worldName"
    if (Test-Path $worldPath) {
      Remove-Item $worldPath -Recurse -Force
    }
  }

  $runtime = Join-Path $root 'server\plugins\EraCore'
  foreach ($relative in @('infrastructure.yml','warps.yml','config.yml')) {
    $p = Join-Path $runtime $relative
    if (Test-Path $p) { Remove-Item $p -Force }
  }

  $simulation = Join-Path $runtime 'simulation.yml'
  if (Test-Path $simulation) {
    $text = Get-Content $simulation -Raw
    if ($text -match '(?m)^\s*terrain-repair-version:\s*\d+\s*
Push-Location (Join-Path $root 'bots')
try {
  Get-ChildItem .\src\*.js | ForEach-Object {
    node --check $_.FullName
    if ($LASTEXITCODE -ne 0) { throw "JavaScript syntax check failed: $($_.Name)" }
  }
} finally {
  Pop-Location
}

$javaHome = ([System.IO.File]::ReadAllText((Join-Path $root 'server\java8-home.txt'))).Trim()
$spigot = Join-Path $root 'server\spigot-1.8.8.jar'

Write-Host "Rebuilding EraCore with Java 8..." -ForegroundColor Cyan
& (Join-Path $root 'server\build-plugin.ps1') -SpigotJar $spigot -JavaHome $javaHome
if ($LASTEXITCODE -ne 0) { throw 'EraCore rebuild failed.' }

Write-Host ''
Write-Host 'HCF Community Intelligence update installed.' -ForegroundColor Green
Write-Host "Backup: $backup" -ForegroundColor DarkGray
Write-Host ''
if ($ResetWorld) {
  Write-Host 'This install preserved simulation/rank/economy state but intentionally regenerated physical worlds.' -ForegroundColor Green
} else {
  Write-Host 'This install preserved live worlds and EraCore runtime state.' -ForegroundColor Green
}
Write-Host 'Restart the server and worker pool, then test:' -ForegroundColor Yellow
if (!$ResetWorld) {
  Write-Host '  Tip: rerun with -ResetWorld if you want all legacy mountainous/generated terrain replaced.' -ForegroundColor DarkYellow
}
Write-Host '  /f show <faction>'
Write-Host '  /stuck'
Write-Host '  /warp duels'
Write-Host '  /warp nether'
Write-Host '  /warp end'
Write-Host '  /history <player-or-faction>'
Write-Host '  /duel <simulated-player>'
Write-Host '  /duel stats <player>'
Write-Host '  /teamfight test 3'
Write-Host '  /simprobe'
Write-Host ''
Write-Host 'For performance calibration, run /teamfight test 3 first, then 4, then 5.' -ForegroundColor Yellow
) {
      $text = [regex]::Replace(
        $text,
        '(?m)^(\s*terrain-repair-version:)\s*\d+\s*
Push-Location (Join-Path $root 'bots')
try {
  Get-ChildItem .\src\*.js | ForEach-Object {
    node --check $_.FullName
    if ($LASTEXITCODE -ne 0) { throw "JavaScript syntax check failed: $($_.Name)" }
  }
} finally {
  Pop-Location
}

$javaHome = ([System.IO.File]::ReadAllText((Join-Path $root 'server\java8-home.txt'))).Trim()
$spigot = Join-Path $root 'server\spigot-1.8.8.jar'

Write-Host "Rebuilding EraCore with Java 8..." -ForegroundColor Cyan
& (Join-Path $root 'server\build-plugin.ps1') -SpigotJar $spigot -JavaHome $javaHome
if ($LASTEXITCODE -ne 0) { throw 'EraCore rebuild failed.' }

Write-Host ''
Write-Host 'HCF Community Intelligence update installed.' -ForegroundColor Green
Write-Host "Backup: $backup" -ForegroundColor DarkGray
Write-Host ''
Write-Host 'This install preserved live worlds and EraCore runtime state.' -ForegroundColor Green
Write-Host 'Restart the server and worker pool, then test:' -ForegroundColor Yellow
Write-Host '  /f show <faction>'
Write-Host '  /stuck'
Write-Host '  /warp duels'
Write-Host '  /warp nether'
Write-Host '  /warp end'
Write-Host '  /history <player-or-faction>'
Write-Host '  /duel <simulated-player>'
Write-Host '  /duel stats <player>'
Write-Host '  /teamfight test 3'
Write-Host '  /simprobe'
Write-Host ''
Write-Host 'For performance calibration, run /teamfight test 3 first, then 4, then 5.' -ForegroundColor Yellow
,
        '$1 0'
      )
    } else {
      $replacement = 'meta:' + [Environment]::NewLine + '  terrain-repair-version: 0'
      $text = [regex]::Replace($text,'(?m)^meta:\s*
Push-Location (Join-Path $root 'bots')
try {
  Get-ChildItem .\src\*.js | ForEach-Object {
    node --check $_.FullName
    if ($LASTEXITCODE -ne 0) { throw "JavaScript syntax check failed: $($_.Name)" }
  }
} finally {
  Pop-Location
}

$javaHome = ([System.IO.File]::ReadAllText((Join-Path $root 'server\java8-home.txt'))).Trim()
$spigot = Join-Path $root 'server\spigot-1.8.8.jar'

Write-Host "Rebuilding EraCore with Java 8..." -ForegroundColor Cyan
& (Join-Path $root 'server\build-plugin.ps1') -SpigotJar $spigot -JavaHome $javaHome
if ($LASTEXITCODE -ne 0) { throw 'EraCore rebuild failed.' }

Write-Host ''
Write-Host 'HCF Community Intelligence update installed.' -ForegroundColor Green
Write-Host "Backup: $backup" -ForegroundColor DarkGray
Write-Host ''
Write-Host 'This install preserved live worlds and EraCore runtime state.' -ForegroundColor Green
Write-Host 'Restart the server and worker pool, then test:' -ForegroundColor Yellow
Write-Host '  /f show <faction>'
Write-Host '  /stuck'
Write-Host '  /warp duels'
Write-Host '  /warp nether'
Write-Host '  /warp end'
Write-Host '  /history <player-or-faction>'
Write-Host '  /duel <simulated-player>'
Write-Host '  /duel stats <player>'
Write-Host '  /teamfight test 3'
Write-Host '  /simprobe'
Write-Host ''
Write-Host 'For performance calibration, run /teamfight test 3 first, then 4, then 5.' -ForegroundColor Yellow
,$replacement,1)
    }
    Set-Content -Path $simulation -Value $text -Encoding UTF8
  }

  Write-Host "Legacy worlds removed. Backup remains at: $backup" -ForegroundColor Green
  Write-Host "Saved factions/simulation state will rebuild onto the clean world on next start." -ForegroundColor Green
}

Write-Host "Validating bot JavaScript..." -ForegroundColor Cyan
Push-Location (Join-Path $root 'bots')
try {
  Get-ChildItem .\src\*.js | ForEach-Object {
    node --check $_.FullName
    if ($LASTEXITCODE -ne 0) { throw "JavaScript syntax check failed: $($_.Name)" }
  }
} finally {
  Pop-Location
}

$javaHome = ([System.IO.File]::ReadAllText((Join-Path $root 'server\java8-home.txt'))).Trim()
$spigot = Join-Path $root 'server\spigot-1.8.8.jar'

Write-Host "Rebuilding EraCore with Java 8..." -ForegroundColor Cyan
& (Join-Path $root 'server\build-plugin.ps1') -SpigotJar $spigot -JavaHome $javaHome
if ($LASTEXITCODE -ne 0) { throw 'EraCore rebuild failed.' }

Write-Host ''
Write-Host 'HCF Community Intelligence update installed.' -ForegroundColor Green
Write-Host "Backup: $backup" -ForegroundColor DarkGray
Write-Host ''
Write-Host 'This install preserved live worlds and EraCore runtime state.' -ForegroundColor Green
Write-Host 'Restart the server and worker pool, then test:' -ForegroundColor Yellow
Write-Host '  /f show <faction>'
Write-Host '  /stuck'
Write-Host '  /warp duels'
Write-Host '  /warp nether'
Write-Host '  /warp end'
Write-Host '  /history <player-or-faction>'
Write-Host '  /duel <simulated-player>'
Write-Host '  /duel stats <player>'
Write-Host '  /teamfight test 3'
Write-Host '  /simprobe'
Write-Host ''
Write-Host 'For performance calibration, run /teamfight test 3 first, then 4, then 5.' -ForegroundColor Yellow
