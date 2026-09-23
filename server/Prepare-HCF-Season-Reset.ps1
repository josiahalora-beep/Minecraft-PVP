param(
    [string]$ServerRoot = $PSScriptRoot
)

$ErrorActionPreference = 'Stop'
$marker = Join-Path $ServerRoot 'plugins\EraCore\season-reset.pending'
if (-not (Test-Path $marker)) {
    exit 0
}

Write-Host '[SOTW] Full season-reset marker detected.' -ForegroundColor Yellow

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
    if ($candidate -and (Test-Path (Split-Path -Qualifier $candidate))) {
        $archiveBase = Join-Path $candidate 'world-archives'
        Write-Host ('[SOTW] Using cold-storage archive root: ' + $archiveBase) -ForegroundColor Cyan
    }
}
$archiveRoot = Join-Path $archiveBase ('SOTW-' + $stamp)
New-Item -ItemType Directory -Path $archiveRoot -Force | Out-Null

$worlds = @(
    $levelName,
    ($levelName + '_nether'),
    ($levelName + '_the_end'),
    'ore_mountain'
) | Select-Object -Unique

Write-Host ('[SOTW] Archiving active dimensions to ' + $archiveRoot)
foreach ($worldName in $worlds) {
    $source = Join-Path $ServerRoot $worldName
    if (-not (Test-Path $source)) { continue }
    $destination = Join-Path $archiveRoot $worldName
    Write-Host ('  moving ' + $worldName)
    Move-Item -LiteralPath $source -Destination $destination
}

# Remove transient physical/event indices. Long-term player identity/rank/history
# files are intentionally preserved.
$era = Join-Path $ServerRoot 'plugins\EraCore'
foreach ($name in @('claims-v2.yml','events.yml','combat-hot.yml')) {
    $path = Join-Path $era $name
    if (Test-Path $path) { Remove-Item -LiteralPath $path -Force }
}

# Force the compositor and terrain migrations to run in the newly generated map.
$config = Join-Path $era 'config.yml'
if (Test-Path $config) {
    $text = Get-Content -LiteralPath $config -Raw
    $text = [regex]::Replace($text, '(?m)^(\s*auto-bootstrap:\s*).+
}

# Reset only the terrain-rematerialization marker inside the preserved AI state.
$simulation = Join-Path $era 'simulation.yml'
if (Test-Path $simulation) {
    $text = Get-Content -LiteralPath $simulation -Raw
    if ($text -match '(?m)^\s*terrain-repair-version:\s*\d+\s*$') {
        $text = [regex]::Replace($text, '(?m)^(\s*terrain-repair-version:\s*)\d+\s*$', '${1}0')
    }
    Set-Content -LiteralPath $simulation -Value $text -Encoding UTF8
}

Remove-Item -LiteralPath $marker -Force
Write-Host '[SOTW] Physical map reset complete. Staged v2 production build will resume automatically.' -ForegroundColor Green
Write-Host '[SOTW] Donor ranks and long-term AI memory were preserved; factions/economy were reset before shutdown.'
exit 0
, '${1}true', 1)
    $text = [regex]::Replace($text, '(?m)^(\s*complete:\s*).+
}

# Reset only the terrain-rematerialization marker inside the preserved AI state.
$simulation = Join-Path $era 'simulation.yml'
if (Test-Path $simulation) {
    $text = Get-Content -LiteralPath $simulation -Raw
    if ($text -match '(?m)^\s*terrain-repair-version:\s*\d+\s*$') {
        $text = [regex]::Replace($text, '(?m)^(\s*terrain-repair-version:\s*)\d+\s*$', '${1}0')
    }
    Set-Content -LiteralPath $simulation -Value $text -Encoding UTF8
}

Remove-Item -LiteralPath $marker -Force
Write-Host '[SOTW] Physical map reset complete. Starting a fresh generated map now.' -ForegroundColor Green
Write-Host '[SOTW] Donor ranks and long-term AI memory were preserved; factions/economy were reset before shutdown.'
exit 0
, '${1}false', 1)
    $text = [regex]::Replace($text, '(?m)^(\s*structures-complete:\s*).+
}

# Reset only the terrain-rematerialization marker inside the preserved AI state.
$simulation = Join-Path $era 'simulation.yml'
if (Test-Path $simulation) {
    $text = Get-Content -LiteralPath $simulation -Raw
    if ($text -match '(?m)^\s*terrain-repair-version:\s*\d+\s*$') {
        $text = [regex]::Replace($text, '(?m)^(\s*terrain-repair-version:\s*)\d+\s*$', '${1}0')
    }
    Set-Content -LiteralPath $simulation -Value $text -Encoding UTF8
}

Remove-Item -LiteralPath $marker -Force
Write-Host '[SOTW] Physical map reset complete. Starting a fresh generated map now.' -ForegroundColor Green
Write-Host '[SOTW] Donor ranks and long-term AI memory were preserved; factions/economy were reset before shutdown.'
exit 0
, '${1}false', 1)
    $text = [regex]::Replace($text, '(?m)^(\s*active:\s*).+
}

# Reset only the terrain-rematerialization marker inside the preserved AI state.
$simulation = Join-Path $era 'simulation.yml'
if (Test-Path $simulation) {
    $text = Get-Content -LiteralPath $simulation -Raw
    if ($text -match '(?m)^\s*terrain-repair-version:\s*\d+\s*$') {
        $text = [regex]::Replace($text, '(?m)^(\s*terrain-repair-version:\s*)\d+\s*$', '${1}0')
    }
    Set-Content -LiteralPath $simulation -Value $text -Encoding UTF8
}

Remove-Item -LiteralPath $marker -Force
Write-Host '[SOTW] Physical map reset complete. Starting a fresh generated map now.' -ForegroundColor Green
Write-Host '[SOTW] Donor ranks and long-term AI memory were preserved; factions/economy were reset before shutdown.'
exit 0
, '${1}true', 1)
    $text = [regex]::Replace($text, '(?m)^(\s*resources-complete:\s*).+
}

# Reset only the terrain-rematerialization marker inside the preserved AI state.
$simulation = Join-Path $era 'simulation.yml'
if (Test-Path $simulation) {
    $text = Get-Content -LiteralPath $simulation -Raw
    if ($text -match '(?m)^\s*terrain-repair-version:\s*\d+\s*$') {
        $text = [regex]::Replace($text, '(?m)^(\s*terrain-repair-version:\s*)\d+\s*$', '${1}0')
    }
    Set-Content -LiteralPath $simulation -Value $text -Encoding UTF8
}

Remove-Item -LiteralPath $marker -Force
Write-Host '[SOTW] Physical map reset complete. Starting a fresh generated map now.' -ForegroundColor Green
Write-Host '[SOTW] Donor ranks and long-term AI memory were preserved; factions/economy were reset before shutdown.'
exit 0
, '${1}false', 1)
    # world-build.complete appears after map.complete; reset its own key too.
    $text = [regex]::Replace($text, '(?ms)(world-build:.*?^\s*complete:\s*).+
}

# Reset only the terrain-rematerialization marker inside the preserved AI state.
$simulation = Join-Path $era 'simulation.yml'
if (Test-Path $simulation) {
    $text = Get-Content -LiteralPath $simulation -Raw
    if ($text -match '(?m)^\s*terrain-repair-version:\s*\d+\s*$') {
        $text = [regex]::Replace($text, '(?m)^(\s*terrain-repair-version:\s*)\d+\s*$', '${1}0')
    }
    Set-Content -LiteralPath $simulation -Value $text -Encoding UTF8
}

Remove-Item -LiteralPath $marker -Force
Write-Host '[SOTW] Physical map reset complete. Starting a fresh generated map now.' -ForegroundColor Green
Write-Host '[SOTW] Donor ranks and long-term AI memory were preserved; factions/economy were reset before shutdown.'
exit 0
, '${1}false', 1)
    Set-Content -LiteralPath $config -Value $text -Encoding UTF8
}

# Reset only the terrain-rematerialization marker inside the preserved AI state.
$simulation = Join-Path $era 'simulation.yml'
if (Test-Path $simulation) {
    $text = Get-Content -LiteralPath $simulation -Raw
    if ($text -match '(?m)^\s*terrain-repair-version:\s*\d+\s*$') {
        $text = [regex]::Replace($text, '(?m)^(\s*terrain-repair-version:\s*)\d+\s*$', '${1}0')
    }
    Set-Content -LiteralPath $simulation -Value $text -Encoding UTF8
}

Remove-Item -LiteralPath $marker -Force
Write-Host '[SOTW] Physical map reset complete. Starting a fresh generated map now.' -ForegroundColor Green
Write-Host '[SOTW] Donor ranks and long-term AI memory were preserved; factions/economy were reset before shutdown.'
exit 0
