param(
    [string]$ColdStorageRoot = "",
    [switch]$SkipAssetCheck
)

$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$Server = Join-Path $Root 'server'
$PluginData = Join-Path $Server 'plugins\EraCore'
$PluginJar = Join-Path $Server 'plugins\EraCore.jar'
$SpigotJar = Join-Path $Server 'spigot-1.8.8.jar'
$JavaHomeFile = Join-Path $Server 'java8-home.txt'
$BuildScript = Join-Path $Server 'build-plugin.ps1'
$ResetScript = Join-Path $Server 'Prepare-HCF-Season-Reset.ps1'

Write-Host ''
Write-Host '=== Daegon HCF Production Unification V2 ===' -ForegroundColor Cyan
Write-Host ('Root: ' + $Root)

if (!(Test-Path $Server)) { throw 'server\ directory was not found. Run this script from the Minecraft-PVP repository root.' }
if (!(Test-Path $SpigotJar)) { throw 'server\spigot-1.8.8.jar is missing. Run setup-windows.ps1 first.' }
if (!(Test-Path $JavaHomeFile)) { throw 'server\java8-home.txt is missing. Run setup-windows.ps1 first.' }
if (!(Test-Path $BuildScript)) { throw 'server\build-plugin.ps1 is missing.' }

# Refuse to mutate the live plugin while the Minecraft listener is active.
try {
    $listener = Get-NetTCPConnection -LocalPort 25565 -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($listener) {
        throw 'Port 25565 is listening. Stop the Minecraft server before installing this build.'
    }
} catch [System.Management.Automation.CommandNotFoundException] {
    Write-Host '[WARN] Get-NetTCPConnection is unavailable; verify the Minecraft server is stopped.' -ForegroundColor Yellow
}

$javaHome = (Get-Content -LiteralPath $JavaHomeFile -Raw).Trim()
$java = Join-Path $javaHome 'bin\java.exe'
$javac = Join-Path $javaHome 'bin\javac.exe'
$jarTool = Join-Path $javaHome 'bin\jar.exe'
if (!(Test-Path $java) -or !(Test-Path $javac) -or !(Test-Path $jarTool)) {
    throw ('Saved Java 8 JDK is invalid: ' + $javaHome)
}
$versionText = (& $java -version 2>&1 | Out-String)
if ($versionText -notmatch '1\.8\.') { throw ('EraCore requires Java 8. Found: ' + $versionText.Trim()) }

if (!$SkipAssetCheck) {
    $assetDir = Join-Path $Server 'map-assets'
    $required = @(
        'krakenhcf.schematic',
        'KOTH2-production-1.8.schematic',
        'EndStyleKOTH-production-1.8.schematic',
        'EgyptKOTH-production-1.8.schematic',
        'KOTH-Forty-1.8-converted.schematic',
        'conquest.schematic',
        'NetherSpawnWillzaTeam.schematic',
        'magical-hcf-end-xayden-bt.schematic'
    )
    $missing = @($required | Where-Object { !(Test-Path (Join-Path $assetDir $_)) })
    if ($missing.Count -gt 0) {
        Write-Host '[FAIL] Production map assets are incomplete:' -ForegroundColor Red
        $missing | ForEach-Object { Write-Host ('  - ' + $_) -ForegroundColor Red }
        throw 'Install the missing production assets before deploying the unified build.'
    }
    Write-Host '[OK] Production schematic assets present.' -ForegroundColor Green
}

# Parse the reset preflight before changing anything.
if (!(Test-Path $ResetScript)) { throw 'Prepare-HCF-Season-Reset.ps1 is missing.' }
[void][ScriptBlock]::Create((Get-Content -LiteralPath $ResetScript -Raw))
Write-Host '[OK] SOTW reset preflight parses.' -ForegroundColor Green

$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$backupRoot = Join-Path $Root ('production-unification-backup-' + $stamp)
New-Item -ItemType Directory -Path $backupRoot -Force | Out-Null

if (Test-Path $PluginJar) {
    Copy-Item -LiteralPath $PluginJar -Destination (Join-Path $backupRoot 'EraCore.jar') -Force
}

$stateBackup = Join-Path $backupRoot 'EraCore-state'
New-Item -ItemType Directory -Path $stateBackup -Force | Out-Null
if (Test-Path $PluginData) {
    $stateNames = @(
        'config.yml','simulation.yml','factions.yml','claims-v2.yml','events.yml',
        'economy.yml','kits.yml','ranks.yml','stats.yml','infrastructure.yml'
    )
    foreach ($name in $stateNames) {
        $p = Join-Path $PluginData $name
        if (Test-Path $p) { Copy-Item -LiteralPath $p -Destination $stateBackup -Force }
    }
}
Copy-Item -LiteralPath $ResetScript -Destination (Join-Path $backupRoot 'Prepare-HCF-Season-Reset.ps1') -Force

if ($ColdStorageRoot) {
    $ColdStorageRoot = [IO.Path]::GetFullPath($ColdStorageRoot)
    New-Item -ItemType Directory -Path $ColdStorageRoot -Force | Out-Null
    foreach ($sub in @('world-archives','memory-archive')) {
        New-Item -ItemType Directory -Path (Join-Path $ColdStorageRoot $sub) -Force | Out-Null
    }
    Set-Content -LiteralPath (Join-Path $Server 'cold-storage-root.txt') -Value $ColdStorageRoot -Encoding UTF8
    Write-Host ('[OK] Cold storage: ' + $ColdStorageRoot) -ForegroundColor Green
}

$rollback = @'
param()
$ErrorActionPreference = 'Stop'
$Here = Split-Path -Parent $MyInvocation.MyCommand.Path
$Root = Split-Path -Parent $Here
$Server = Join-Path $Root 'server'
$PluginData = Join-Path $Server 'plugins\EraCore'

if (Test-Path (Join-Path $Here 'EraCore.jar')) {
    Copy-Item -LiteralPath (Join-Path $Here 'EraCore.jar') -Destination (Join-Path $Server 'plugins\EraCore.jar') -Force
}
$State = Join-Path $Here 'EraCore-state'
if (Test-Path $State) {
    New-Item -ItemType Directory -Path $PluginData -Force | Out-Null
    Get-ChildItem -LiteralPath $State -File | ForEach-Object {
        Copy-Item -LiteralPath $_.FullName -Destination (Join-Path $PluginData $_.Name) -Force
    }
}
if (Test-Path (Join-Path $Here 'Prepare-HCF-Season-Reset.ps1')) {
    Copy-Item -LiteralPath (Join-Path $Here 'Prepare-HCF-Season-Reset.ps1') -Destination (Join-Path $Server 'Prepare-HCF-Season-Reset.ps1') -Force
}
Write-Host 'Rollback restored the backed-up EraCore jar/state.' -ForegroundColor Green
'@
Set-Content -LiteralPath (Join-Path $backupRoot 'rollback.ps1') -Value $rollback -Encoding UTF8

Write-Host ('[1/4] Backup created: ' + $backupRoot) -ForegroundColor Cyan
Write-Host '[2/4] Compiling EraCore with Java 8...' -ForegroundColor Cyan
& $BuildScript -SpigotJar $SpigotJar -JavaHome $javaHome
if ($LASTEXITCODE -ne 0) { throw 'EraCore build failed; the previous jar is still available in the backup folder.' }

if (!(Test-Path $PluginJar)) { throw 'Build returned without producing server\plugins\EraCore.jar.' }

Write-Host '[3/4] Validating built jar...' -ForegroundColor Cyan
$jarList = (& $jarTool tf $PluginJar | Out-String)
$mustContain = @(
    'plugin.yml',
    'dev/jorel/eracore/EraCore.class',
    'dev/jorel/eracore/HcfWorldBuildDirector.class',
    'dev/jorel/eracore/HcfBaseBuilder.class',
    'dev/jorel/eracore/LogicalTabListDirector.class',
    'dev/jorel/eracore/AiChatBridge.class'
)
foreach ($entry in $mustContain) {
    if ($jarList -notmatch [regex]::Escape($entry)) { throw ('Built jar is missing ' + $entry) }
}

# Validate plugin.yml command registration from source.
$pluginYml = Join-Path $Server 'plugins-src\EraCore\src\main\resources\plugin.yml'
$pluginText = Get-Content -LiteralPath $pluginYml -Raw
foreach ($cmd in @('simtab:','mapcompose:','sotw:','baserebuild:')) {
    if ($pluginText -notmatch [regex]::Escape($cmd)) { throw ('plugin.yml validation failed: ' + $cmd) }
}

Write-Host '[4/4] Deployment validation complete.' -ForegroundColor Green
Write-Host ''
Write-Host 'Installed capabilities:' -ForegroundColor Cyan
Write-Host '  - staged automatic v7 production-map build with MSPT throttling'
Write-Host '  - canonical KOTH/portal/conquest geometry'
Write-Host '  - hybrid five-family faction bases + rectangular claim containment'
Write-Host '  - local-first contextual chat + semantic anti-repeat + hard AI budget'
Write-Host '  - bounded hot memory + compressed cold history shards'
Write-Host '  - automatic KOTH/Conquest rotation'
Write-Host '  - hardened logical TAB with /simtab diagnostics'
Write-Host '  - duel arena isolated from the HCF Overworld'
Write-Host ''
Write-Host ('Rollback: powershell -ExecutionPolicy Bypass -File "' + (Join-Path $backupRoot 'rollback.ps1') + '"') -ForegroundColor Yellow
Write-Host ''
Write-Host 'Next:' -ForegroundColor Cyan
Write-Host '  1. Start server\start-server.bat'
Write-Host '  2. In game: /mapcompose status'
Write-Host '  3. In game: /simtab status'
Write-Host '  4. In game: /simchat status'
Write-Host '  5. When ready for the fresh SOTW: /sotw reset confirm'
Write-Host '  6. Restart with server\start-server.bat and let /mapcompose status reach READY'
