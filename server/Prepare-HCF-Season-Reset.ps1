param(
    [string]$ServerRoot = $PSScriptRoot
)

$ErrorActionPreference = 'Stop'

# PowerShell -File calls from cmd.exe can preserve quoting/trailing-separator
# artifacts in explicitly supplied path arguments. The script normally lives
# directly in the server folder, so normalize once before constructing paths.
if ([string]::IsNullOrWhiteSpace($ServerRoot)) {
    $ServerRoot = $PSScriptRoot
}
$ServerRoot = $ServerRoot.Trim().Trim('"')
$ServerRoot = [IO.Path]::GetFullPath($ServerRoot)
$marker = [IO.Path]::Combine($ServerRoot,'plugins','EraCore','season-reset.pending')

if (-not (Test-Path -LiteralPath $marker)) {
    exit 0
}

Write-Host '[SOTW] Full season-reset marker detected.' -ForegroundColor Yellow

# This script must only run before Spigot starts. If anything is already
# listening on 25565, abort instead of attempting to move a live world.
try {
    $listener = Get-NetTCPConnection -LocalPort 25565 -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($listener) {
        throw 'Port 25565 is already listening. Stop the Minecraft server, then launch through server\start-server.bat.'
    }
} catch [System.Management.Automation.CommandNotFoundException] {
    # Older PowerShell installs may not provide Get-NetTCPConnection.
}

$assetDir = Join-Path $ServerRoot 'map-assets'
$requiredAssets = @(
    'krakenhcf.schematic',
    'KOTH2-production-1.8.schematic',
    'EndStyleKOTH-production-1.8.schematic',
    'EgyptKOTH-production-1.8.schematic',
    'KOTH-Forty-1.8-converted.schematic',
    'conquest.schematic',
    'NetherSpawnWillzaTeam.schematic',
    'magical-hcf-end-xayden-bt.schematic',
    'FreeMap.rar'
)

$missing = @($requiredAssets | Where-Object { -not (Test-Path (Join-Path $assetDir $_)) })
if ($missing.Count -gt 0) {
    Write-Host '[SOTW] ABORTED: production map assets are missing. No world was moved.' -ForegroundColor Red
    $missing | ForEach-Object { Write-Host ('  - ' + $_) -ForegroundColor Red }
    Write-Host ('Expected folder: ' + $assetDir)
    exit 31
}

$serverProperties = Join-Path $ServerRoot 'server.properties'
$levelName = 'world'
if (Test-Path $serverProperties) {
    $match = Select-String -Path $serverProperties -Pattern '^level-name=(.+)$' | Select-Object -First 1
    if ($match -and $match.Matches.Count -gt 0) {
        $value = $match.Matches[0].Groups[1].Value.Trim()
        if ($value) { $levelName = $value }
    }
}

$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'

# Optional spare-SSD archive root written by the production installer.
$coldRootFile = Join-Path $ServerRoot 'cold-storage-root.txt'
$archiveBase = Join-Path $ServerRoot 'world-archives'
if (Test-Path $coldRootFile) {
    $candidate = (Get-Content -LiteralPath $coldRootFile -Raw).Trim()
    if ($candidate) {
        $drive = Split-Path -Qualifier $candidate
        if ($drive -and (Test-Path $drive)) {
            $archiveBase = Join-Path $candidate 'world-archives'
            Write-Host ('[SOTW] Using cold-storage archive root: ' + $archiveBase) -ForegroundColor Cyan
        }
    }
}

$archiveRoot = Join-Path $archiveBase ('SOTW-' + $stamp)
New-Item -ItemType Directory -Path $archiveRoot -Force | Out-Null

# Full SOTW means every generated dimension is fresh. The previous reset left
# the duel world behind and, more importantly, stale location files could point
# /spawn at terrain from the old map.
$worlds = @(
    $levelName,
    ($levelName + '_nether'),
    ($levelName + '_the_end'),
    'ore_mountain',
    'duel_arena'
) | Select-Object -Unique

Write-Host ('[SOTW] Archiving active dimensions to ' + $archiveRoot)
foreach ($worldName in $worlds) {
    $source = Join-Path $ServerRoot $worldName
    if (-not (Test-Path $source)) { continue }
    $destination = Join-Path $archiveRoot $worldName
    Write-Host ('  moving ' + $worldName)
    Move-Item -LiteralPath $source -Destination $destination
    if (Test-Path $source) {
        throw ('World reset verification failed; source still exists after archive move: ' + $source)
    }
}

# Phase 1 authored-world foundation. Never regenerate the Overworld as
# superflat: every SOTW starts from the verified Stylez HCF world snapshot.
if (Test-Path $serverProperties) {
    $props = Get-Content -LiteralPath $serverProperties
    $wanted = @{
        'level-type' = 'DEFAULT'
        'generator-settings' = ''
        'generate-structures' = 'false'
        'spawn-protection' = '0'
    }
    foreach ($key in $wanted.Keys) {
        $found = $false
        for ($i=0; $i -lt $props.Count; $i++) {
            if ($props[$i] -match ('^' + [regex]::Escape($key) + '=')) {
                $props[$i] = $key + '=' + $wanted[$key]
                $found = $true
                break
            }
        }
        if (-not $found) { $props += ($key + '=' + $wanted[$key]) }
    }
    Set-Content -LiteralPath $serverProperties -Value $props -Encoding ASCII
}

$authoredArchive = Join-Path $assetDir 'FreeMap.rar'
$expectedAuthoredHash = 'af9c214979fcde0b1c41e435a6359940a930ffa97c8e2ad09667f74203afba95'
$actualAuthoredHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $authoredArchive).Hash.ToLowerInvariant()
if ($actualAuthoredHash -ne $expectedAuthoredHash) {
    throw ('Authored HCF map checksum mismatch. Expected ' + $expectedAuthoredHash + ' but found ' + $actualAuthoredHash)
}

$extractRoot = Join-Path $ServerRoot ('.authored-world-' + $stamp)
New-Item -ItemType Directory -Path $extractRoot -Force | Out-Null

$expanded = $false
$tar = Get-Command tar.exe -ErrorAction SilentlyContinue
if (-not $tar) { $tar = Get-Command tar -ErrorAction SilentlyContinue }
if ($tar) {
    & $tar.Source -xf $authoredArchive -C $extractRoot
    if ($LASTEXITCODE -eq 0 -and (Test-Path (Join-Path $extractRoot 'FreeWorld\level.dat'))) {
        $expanded = $true
    }
}

if (-not $expanded) {
    $sevenCandidates = @(
        (Join-Path $env:ProgramFiles '7-Zip\7z.exe'),
        (Join-Path \${env:ProgramFiles(x86)} '7-Zip\7z.exe')
    ) | Where-Object { $_ -and (Test-Path $_) }
    if ($sevenCandidates.Count -gt 0) {
        & $sevenCandidates[0] x -y ('-o' + $extractRoot) $authoredArchive | Out-Null
        if ($LASTEXITCODE -eq 0 -and (Test-Path (Join-Path $extractRoot 'FreeWorld\level.dat'))) {
            $expanded = $true
        }
    }
}

if (-not $expanded) {
    throw 'Could not extract FreeMap.rar. Windows tar/libarchive or 7-Zip is required for the authored HCF world reset.'
}

$authoredSource = Join-Path $extractRoot 'FreeWorld'
$activeOverworld = Join-Path $ServerRoot $levelName
Copy-Item -LiteralPath $authoredSource -Destination $activeOverworld -Recurse -Force
$sessionLock = Join-Path $activeOverworld 'session.lock'
if (Test-Path $sessionLock) { Remove-Item -LiteralPath $sessionLock -Force }
Remove-Item -LiteralPath $extractRoot -Recurse -Force

if (-not (Test-Path (Join-Path $activeOverworld 'level.dat')) -or
    -not (Test-Path (Join-Path $activeOverworld 'region'))) {
    throw 'Authored HCF world restore failed verification.'
}
Write-Host '[SOTW] Authored Stylez HCF overworld restored and checksum verified.' -ForegroundColor Green

$era = Join-Path $ServerRoot 'plugins\EraCore'
$stateArchive = Join-Path $archiveRoot 'EraCore-reset-state'
New-Item -ItemType Directory -Path $stateArchive -Force | Out-Null

# These files contain physical-map locations or transient world state. Preserve
# copies in the archive, then remove them so a fresh map cannot inherit an old
# spawn, safezone, claim index, crate mark, event, duel, or combat location.
$resetState = @(
    'claims-v2.yml',
    'events.yml',
    'combat-hot.yml',
    'warps.yml',
    'safezones.yml',
    'infrastructure.yml',
    'rewards.yml'
)
foreach ($name in $resetState) {
    $path = Join-Path $era $name
    if (-not (Test-Path $path)) { continue }
    Copy-Item -LiteralPath $path -Destination (Join-Path $stateArchive $name) -Force
    Remove-Item -LiteralPath $path -Force
}

# EraCore owns config.yml mutation. This reset preflight only handles
# destructive filesystem work and leaves a receipt for the plugin to consume
# after Bukkit has parsed a valid configuration.

# Reset only the terrain-rematerialization marker inside the preserved AI state.
$simulation = Join-Path $era 'simulation.yml'
if (Test-Path $simulation) {
    $text = Get-Content -LiteralPath $simulation -Raw
    if ($text -match '(?m)^\s*terrain-repair-version:\s*\d+\s*$') {
        $text = [regex]::Replace(
            $text,
            '(?m)^(\s*terrain-repair-version:\s*)\d+\s*$',
            { param($m) $m.Groups[1].Value + '0' },
            1
        )
    }
    Set-Content -LiteralPath $simulation -Value $text -Encoding UTF8
}

# Leave a positive receipt for diagnostics. The pending marker is removed only
# after every destructive/reset step above has completed successfully.
$receipt = Join-Path $era 'season-reset.applied'
Set-Content -LiteralPath $receipt -Value @(
    ('applied-at=' + [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()),
    ('archive=' + $archiveRoot),
    ('level-name=' + $levelName),
    'layout-version=3'
) -Encoding ASCII

Remove-Item -LiteralPath $marker -Force

$activeOverworld = Join-Path $ServerRoot $levelName
if (-not (Test-Path (Join-Path $activeOverworld 'level.dat')) -or
    -not (Test-Path (Join-Path $activeOverworld 'region'))) {
    throw 'SOTW reset verification failed; authored Overworld is not staged before Spigot start.'
}
foreach ($worldName in $worlds) {
    if ($worldName -eq $levelName) { continue }
    $source = Join-Path $ServerRoot $worldName
    if (Test-Path $source) {
        throw ('SOTW reset verification failed; non-Overworld dimension unexpectedly exists before Spigot start: ' + $source)
    }
}

Write-Host '[SOTW] VERIFIED authored HCF physical map reset complete.' -ForegroundColor Green
Write-Host '[SOTW] Old worlds + stale location state were archived. The verified authored Overworld is staged; EraCore will add only HCF-specific production structures/overlays.'
Write-Host ('[SOTW] Archive: ' + $archiveRoot) -ForegroundColor DarkGray
exit 0
