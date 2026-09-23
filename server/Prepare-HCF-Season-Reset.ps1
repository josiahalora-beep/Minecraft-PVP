param(
    [string]$ServerRoot = $PSScriptRoot
)

$ErrorActionPreference = 'Stop'
$marker = Join-Path $ServerRoot 'plugins\EraCore\season-reset.pending'
if (-not (Test-Path $marker)) {
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
    'magical-hcf-end-xayden-bt.schematic'
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

# Force the canonical flat 1.8 HCF terrain on the regenerated Overworld.
# This prevents a reset from silently coming back as ordinary vanilla terrain.
if (Test-Path $serverProperties) {
    $props = Get-Content -LiteralPath $serverProperties
    $wanted = @{
        'level-type' = 'FLAT'
        'generator-settings' = '2;7,59x1,3x3,2;1;'
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

function Set-YamlScalar {
    param(
        [string]$Text,
        [string]$Key,
        [string]$Value,
        [int]$Occurrence = 1
    )
    $pattern = '(?m)^(\s*' + [regex]::Escape($Key) + ':\s*).+$'
    $matches = [regex]::Matches($Text,$pattern)
    if ($matches.Count -lt $Occurrence) { return $Text }
    $m = $matches[$Occurrence-1]
    return $Text.Substring(0,$m.Index) + $m.Groups[1].Value + $Value + $Text.Substring($m.Index+$m.Length)
}

# A full reset requests the staged production pipeline. Structures are built
# first, resources second, then the map is marked READY.
$config = Join-Path $era 'config.yml'
if (Test-Path $config) {
    $text = Get-Content -LiteralPath $config -Raw
    $text = Set-YamlScalar $text 'auto-bootstrap' 'true' 1
    $text = Set-YamlScalar $text 'complete' 'false' 1
    $text = Set-YamlScalar $text 'structures-complete' 'false' 1
    $text = Set-YamlScalar $text 'active' 'true' 1
    $text = Set-YamlScalar $text 'complete' 'false' 2
    $text = Set-YamlScalar $text 'resources-complete' 'false' 1
    Set-Content -LiteralPath $config -Value $text -Encoding UTF8
}

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

foreach ($worldName in $worlds) {
    $source = Join-Path $ServerRoot $worldName
    if (Test-Path $source) {
        throw ('SOTW reset verification failed; generated world folder unexpectedly exists before Spigot start: ' + $source)
    }
}

Write-Host '[SOTW] VERIFIED fresh physical map reset complete.' -ForegroundColor Green
Write-Host '[SOTW] Old worlds + stale location state were archived. Spigot will now generate the canonical flat world and EraCore will paste production assets.'
Write-Host ('[SOTW] Archive: ' + $archiveRoot) -ForegroundColor DarkGray
exit 0
