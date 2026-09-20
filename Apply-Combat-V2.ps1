param(
  [string]$Branch = 'phase-0-bootstrap'
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path

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
$backup = Join-Path $root "combat-v2-backup-$stamp"
New-Item -ItemType Directory -Force -Path $backup | Out-Null

$base = "https://raw.githubusercontent.com/josiahalora-beep/Minecraft-PVP/$Branch"

$files = @(
  @{ Remote='bots/src/duel-bot.js'; Local='bots\src\duel-bot.js' },
  @{ Remote='bots/src/combat-profiles.js'; Local='bots\src\combat-profiles.js' },
  @{ Remote='server/plugins-src/EraCore/src/main/java/dev/jorel/eracore/EraCore.java'; Local='server\plugins-src\EraCore\src\main\java\dev\jorel\eracore\EraCore.java' },
  @{ Remote='server/plugins-src/EraCore/src/main/resources/config.yml'; Local='server\plugins-src\EraCore\src\main\resources\config.yml' }
)

Write-Host 'Backing up current combat/plugin source...' -ForegroundColor Cyan
foreach ($f in $files) {
  $dest = Join-Path $root $f.Local
  if (Test-Path $dest) {
    $safeName = ($f.Local -replace '[\\/:*?"<>|]', '_')
    Copy-Item $dest (Join-Path $backup $safeName) -Force
  }
}

Write-Host 'Downloading Combat V2 source from the project branch...' -ForegroundColor Cyan
foreach ($f in $files) {
  $dest = Join-Path $root $f.Local
  $dir = Split-Path $dest -Parent
  New-Item -ItemType Directory -Force -Path $dir | Out-Null
  $tmp = "$dest.download"
  Invoke-WebRequest -UseBasicParsing "$base/$($f.Remote)" -OutFile $tmp
  Move-Item $tmp $dest -Force
}

$runtimeConfig = Join-Path $root 'server\plugins\EraCore\config.yml'
if (Test-Path $runtimeConfig) {
  Copy-Item $runtimeConfig (Join-Path $backup 'runtime-config.yml') -Force
  $cfg = [System.IO.File]::ReadAllText($runtimeConfig)

  if ($cfg -notmatch '(?m)^pvp:\s*$') {
    $cfg += @"

pvp:
  pearl-cooldown-seconds: 16
"@
  }

  if ($cfg -notmatch '(?m)^creator-tag:\s*$') {
    $cfg += @"

creator-tag:
  enabled: true
  head-prefix: '&c[YT] &f'
  chat-prefix: '&c[YT] '
  creators:
    - Stimpy
    - Stimp
    - Stimpypvp
    - Marcel
    - PainfulPvP
    - lolitsalex
    - loolitsalex
    - Skimpy
"@
  } elseif ($cfg -notmatch '(?m)^\s*-\s*Stimpypvp\s*$') {
    $cfg = $cfg -replace "(?m)^(\s*-\s*Stimp\s*)$", "`$1`r`n    - Stimpypvp"
  }

  $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
  [System.IO.File]::WriteAllText($runtimeConfig, $cfg, $utf8NoBom)
}

Write-Host 'Checking bot JavaScript...' -ForegroundColor Cyan
Push-Location (Join-Path $root 'bots')
node --check src\combat-profiles.js
if ($LASTEXITCODE -ne 0) { Pop-Location; throw 'combat-profiles.js syntax check failed.' }
node --check src\duel-bot.js
if ($LASTEXITCODE -ne 0) { Pop-Location; throw 'duel-bot.js syntax check failed.' }
Pop-Location

$javaHome = ([System.IO.File]::ReadAllText((Join-Path $root 'server\java8-home.txt'))).Trim()
$spigot = Join-Path $root 'server\spigot-1.8.8.jar'

Write-Host 'Rebuilding EraCore with Java 8...' -ForegroundColor Cyan
& (Join-Path $root 'server\build-plugin.ps1') -SpigotJar $spigot -JavaHome $javaHome
if ($LASTEXITCODE -ne 0) { throw 'EraCore rebuild failed.' }

Write-Host ''
Write-Host 'Combat V2 update installed.' -ForegroundColor Green
Write-Host "Backup: $backup" -ForegroundColor DarkGray
Write-Host ''
Write-Host 'Restart server\start-server.bat, then test:' -ForegroundColor Yellow
Write-Host '  cd bots'
Write-Host '  npm run duel -- average balanced'
Write-Host '  npm run duel -- skilled balanced'
Write-Host '  npm run duel -- strong aggressive'
Write-Host '  npm run duel -- elite aggressive'
Write-Host ''
Write-Host 'Run only one duel bot at a time. In Minecraft use /duelprep after it spawns.' -ForegroundColor Yellow
