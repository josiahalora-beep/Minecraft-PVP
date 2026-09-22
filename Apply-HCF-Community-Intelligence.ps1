param(
  [switch]$ResetWorld
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$validatedCommit = 'e40cad1bcc008a5b8afd796d5a157caf1d7e9fd3'
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

Write-Host 'IMPORTANT: stop the Minecraft server and worker pool before running this updater.' -ForegroundColor Yellow

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
  'server\build-plugin.ps1',
  'server\server.properties',
  'Start-HCF-ControlPlane.ps1',
  'Start-HCF-LocalWorker.ps1'
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
  'bots/src/worker-coordinator.js',
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
  'server/plugins-src/EraCore/src/main/java/dev/jorel/eracore/HcfElevatorDirector.java',
  'server/plugins-src/EraCore/src/main/java/dev/jorel/eracore/HcfPortalDirector.java',
  'server/plugins-src/EraCore/src/main/java/dev/jorel/eracore/HcfTravelDirector.java',
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

# Base Intelligence v1 changes physical base geometry even when the world is preserved.
# Reset only the structural repair marker; keep simulation/economy/rank/social state.
$simulation = Join-Path (Join-Path $root 'server\plugins\EraCore') 'simulation.yml'
if (Test-Path $simulation) {
  $sourceLines = @(Get-Content $simulation)
  $outputLines = New-Object System.Collections.Generic.List[string]
  $versionFound = $false
  $metaSeen = $false

  foreach ($line in $sourceLines) {
    if ($line -match '^\s*terrain-repair-version:\s*\d+\s*
  # Keep simulation/ranks/economy/faction history. Replace only physical worlds
  # and generated-map state so the saved factions rematerialize from templates.
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

  # Force SimWorldDirector's structural migration to rebuild every saved base,
  # storage vault, brewer and claim footprint onto the regenerated terrain.
  $simulation = Join-Path $runtime 'simulation.yml'
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

  Write-Host "Legacy physical worlds removed. Backup: $backup" -ForegroundColor Green
  Write-Host "Saved simulation/factions will rebuild on clean low-relief terrain at next start." -ForegroundColor Green
}

Write-Host "Installing Mineflayer world-intelligence dependencies..." -ForegroundColor Cyan
Push-Location (Join-Path $root 'bots')
try {
  npm install --no-audit --no-fund
  if ($LASTEXITCODE -ne 0) { throw 'npm install failed.' }
} finally {
  Pop-Location
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
Write-Host 'HCF world/base/AI update installed (Base Intelligence v1).' -ForegroundColor Green
Write-Host "Backup: $backup" -ForegroundColor DarkGray
Write-Host ''
if ($ResetWorld) {
  Write-Host 'Physical worlds will regenerate; simulation/rank/economy/faction state was preserved.' -ForegroundColor Green
} else {
  Write-Host 'Live worlds were preserved. Existing bases will receive the v8 Base Intelligence structural migration.' -ForegroundColor Green
  Write-Host 'Use -ResetWorld for the cleanest terrain/floating-block correction.' -ForegroundColor DarkYellow
}
Write-Host 'Restart the server first, then restart the worker pool.' -ForegroundColor Yellow
Write-Host 'Recommended checks:' -ForegroundColor Yellow
Write-Host '  /f show <faction>'
Write-Host '  /keys'
Write-Host '  /crates vote'
Write-Host '  /crates donor'
Write-Host '  /warp duels'
Write-Host '  /simprobe'
Write-Host '  /teamfight test 3'
Write-Host ''
Write-Host 'For performance calibration, run /teamfight test 3 first, then 4, then 5.' -ForegroundColor Yellow
Write-Host ''
Write-Host 'Kraken spawn setup after pasting the schematic:' -ForegroundColor Cyan
Write-Host '  1. Stand at the exact spawn center facing the main road: /spawnpreset kraken center'
Write-Host '  2. Stand at the desired road PvP point if you want to override auto placement: /spawnpreset kraken mark pvp'
Write-Host '  3. Stand inside the real rooms: /spawnpreset kraken mark shop  and  /spawnpreset kraken mark enchant'
Write-Host '  4. Look at each physical crate block: /spawnpreset kraken mark votecrate  and  /spawnpreset kraken mark donorcrate'
Write-Host '  5. Verify everything: /spawnpreset kraken status'
Write-Host '  Director debug: /simcombat director'
Write-Host ''
Write-Host 'Distributed workers:' -ForegroundColor Cyan
Write-Host '  Home control plane: .\Start-HCF-ControlPlane.ps1 -CoordinatorToken <TOKEN>'
Write-Host '  Local worker:      .\Start-HCF-LocalWorker.ps1 -CoordinatorToken <TOKEN> -Bodies 10'
Write-Host '  Oracle workers use bots/start-worker-linux.sh with the same coordinator token.'
) {
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
  Write-Host "ResetWorld requested: replacing legacy physical worlds after backup..." -ForegroundColor Yellow

  # Keep simulation/ranks/economy/faction history. Replace only physical worlds
  # and generated-map state so the saved factions rematerialize from templates.
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

  # Force SimWorldDirector's structural migration to rebuild every saved base,
  # storage vault, brewer and claim footprint onto the regenerated terrain.
  $simulation = Join-Path $runtime 'simulation.yml'
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

  Write-Host "Legacy physical worlds removed. Backup: $backup" -ForegroundColor Green
  Write-Host "Saved simulation/factions will rebuild on clean low-relief terrain at next start." -ForegroundColor Green
}

Write-Host "Installing Mineflayer world-intelligence dependencies..." -ForegroundColor Cyan
Push-Location (Join-Path $root 'bots')
try {
  npm install --no-audit --no-fund
  if ($LASTEXITCODE -ne 0) { throw 'npm install failed.' }
} finally {
  Pop-Location
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
Write-Host 'HCF world/base/AI update installed.' -ForegroundColor Green
Write-Host "Backup: $backup" -ForegroundColor DarkGray
Write-Host ''
if ($ResetWorld) {
  Write-Host 'Physical worlds will regenerate; simulation/rank/economy/faction state was preserved.' -ForegroundColor Green
} else {
  Write-Host 'Live worlds were preserved. Existing bases will receive the v6 structural migration.' -ForegroundColor Green
  Write-Host 'Use -ResetWorld for the cleanest terrain/floating-block correction.' -ForegroundColor DarkYellow
}
Write-Host 'Restart the server first, then restart the worker pool.' -ForegroundColor Yellow
Write-Host 'Recommended checks:' -ForegroundColor Yellow
Write-Host '  /f show <faction>'
Write-Host '  /keys'
Write-Host '  /crates vote'
Write-Host '  /crates donor'
Write-Host '  /warp duels'
Write-Host '  /simprobe'
Write-Host '  /teamfight test 3'
Write-Host ''
Write-Host 'For performance calibration, run /teamfight test 3 first, then 4, then 5.' -ForegroundColor Yellow
Write-Host ''
Write-Host 'Kraken spawn setup after pasting the schematic:' -ForegroundColor Cyan
Write-Host '  1. Stand at the exact spawn center facing the main road: /spawnpreset kraken center'
Write-Host '  2. Stand at the desired road PvP point if you want to override auto placement: /spawnpreset kraken mark pvp'
Write-Host '  3. Stand inside the real rooms: /spawnpreset kraken mark shop  and  /spawnpreset kraken mark enchant'
Write-Host '  4. Look at each physical crate block: /spawnpreset kraken mark votecrate  and  /spawnpreset kraken mark donorcrate'
Write-Host '  5. Verify everything: /spawnpreset kraken status'
Write-Host '  Director debug: /simcombat director'
Write-Host ''
Write-Host 'Distributed workers:' -ForegroundColor Cyan
Write-Host '  Home control plane: .\Start-HCF-ControlPlane.ps1 -CoordinatorToken <TOKEN>'
Write-Host '  Local worker:      .\Start-HCF-LocalWorker.ps1 -CoordinatorToken <TOKEN> -Bodies 10'
Write-Host '  Oracle workers use bots/start-worker-linux.sh with the same coordinator token.'
