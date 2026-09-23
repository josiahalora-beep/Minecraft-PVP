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
$JavaSourceDir = Join-Path $Server 'plugins-src\EraCore\src\main\java\dev\jorel\eracore'

# This repository is often installed as a plain folder rather than a Git clone.
# Always sync the exact coordinated source generation before compiling.
$SourceCommit = 'f32bb23992cc9810f194e8b1ff8e1c21222c697c'
$RawBase = 'https://raw.githubusercontent.com/josiahalora-beep/Minecraft-PVP/' + $SourceCommit
$SourceFiles = @(
    'server/plugins-src/EraCore/src/main/java/dev/jorel/eracore/ActorDirectory.java',
    'server/plugins-src/EraCore/src/main/java/dev/jorel/eracore/AiChatBridge.java',
    'server/plugins-src/EraCore/src/main/java/dev/jorel/eracore/ContextChatBrain.java',
    'server/plugins-src/EraCore/src/main/java/dev/jorel/eracore/EraCore.java',
    'server/plugins-src/EraCore/src/main/java/dev/jorel/eracore/HcfAutoBrewerDirector.java',
    'server/plugins-src/EraCore/src/main/java/dev/jorel/eracore/HcfBaseBuilder.java',
    'server/plugins-src/EraCore/src/main/java/dev/jorel/eracore/HcfBasePlan.java',
    'server/plugins-src/EraCore/src/main/java/dev/jorel/eracore/HcfClaimDirector.java',
    'server/plugins-src/EraCore/src/main/java/dev/jorel/eracore/HcfClassDirector.java',
    'server/plugins-src/EraCore/src/main/java/dev/jorel/eracore/HcfElevatorDirector.java',
    'server/plugins-src/EraCore/src/main/java/dev/jorel/eracore/HcfEventDirector.java',
    'server/plugins-src/EraCore/src/main/java/dev/jorel/eracore/HcfGateDirector.java',
    'server/plugins-src/EraCore/src/main/java/dev/jorel/eracore/HcfInfrastructureDirector.java',
    'server/plugins-src/EraCore/src/main/java/dev/jorel/eracore/HcfMapDirector.java',
    'server/plugins-src/EraCore/src/main/java/dev/jorel/eracore/HcfPortalDirector.java',
    'server/plugins-src/EraCore/src/main/java/dev/jorel/eracore/HcfResourceDirector.java',
    'server/plugins-src/EraCore/src/main/java/dev/jorel/eracore/HcfSidebarDirector.java',
    'server/plugins-src/EraCore/src/main/java/dev/jorel/eracore/HcfTerrainDirector.java',
    'server/plugins-src/EraCore/src/main/java/dev/jorel/eracore/HcfTravelDirector.java',
    'server/plugins-src/EraCore/src/main/java/dev/jorel/eracore/HcfWorldBuildDirector.java',
    'server/plugins-src/EraCore/src/main/java/dev/jorel/eracore/HcfZoneDisplayDirector.java',
    'server/plugins-src/EraCore/src/main/java/dev/jorel/eracore/LegacySchematicComposer.java',
    'server/plugins-src/EraCore/src/main/java/dev/jorel/eracore/LogicalTabListDirector.java',
    'server/plugins-src/EraCore/src/main/java/dev/jorel/eracore/NmsFakePlayerRuntime.java',
    'server/plugins-src/EraCore/src/main/java/dev/jorel/eracore/SimChatDirector.java',
    'server/plugins-src/EraCore/src/main/java/dev/jorel/eracore/SimEconomyModel.java',
    'server/plugins-src/EraCore/src/main/java/dev/jorel/eracore/SimWorldDirector.java',
    'server/plugins-src/EraCore/src/main/java/dev/jorel/eracore/SpawnPresenceDirector.java',
    'server/plugins-src/EraCore/src/main/java/dev/jorel/eracore/SpawnRewardsDirector.java',
    'server/plugins-src/EraCore/src/main/java/dev/jorel/eracore/WarpManager.java',
    'server/plugins-src/EraCore/src/main/resources/config.yml',
    'server/plugins-src/EraCore/src/main/resources/plugin.yml',
    'server/Prepare-HCF-Season-Reset.ps1',
    'bots/src/worker-pool.js',
    'bots/src/hcf-map-intelligence.js',
    'docs/ACTOR_RUNTIME.md'
)

Write-Host ''
Write-Host '=== Daegon HCF Production Unification V2 ===' -ForegroundColor Cyan
Write-Host ('Root: ' + $Root)
Write-Host ('Pinned source generation: ' + $SourceCommit)

if (!(Test-Path $Server)) { throw 'server\ directory was not found. Run this script from the Minecraft-PVP root.' }
if (!(Test-Path $SpigotJar)) { throw 'server\spigot-1.8.8.jar is missing. Run setup-windows.ps1 first.' }
if (!(Test-Path $JavaHomeFile)) { throw 'server\java8-home.txt is missing. Run setup-windows.ps1 first.' }
if (!(Test-Path $BuildScript)) { throw 'server\build-plugin.ps1 is missing.' }

# Refuse to mutate a live server.
try {
    $listener = Get-NetTCPConnection -LocalPort 25565 -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($listener) { throw 'Port 25565 is listening. Stop the Minecraft server before installing this build.' }
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

# java -version writes normal version text to STDERR. With ErrorActionPreference=Stop,
# PowerShell 5.1 can incorrectly turn that into a terminating NativeCommandError.
$previousEap = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
$versionText = (& $java -version 2>&1 | ForEach-Object { $_.ToString() } | Out-String)
$javaExit = $LASTEXITCODE
$ErrorActionPreference = $previousEap
if ($javaExit -ne 0) { throw ('Java version probe failed with exit code ' + $javaExit) }
if ($versionText -notmatch '1\.8\.') { throw ('EraCore requires Java 8. Found: ' + $versionText.Trim()) }
Write-Host ('[OK] Java 8: ' + (($versionText -split "[\r\n]+" | Select-Object -First 1).Trim())) -ForegroundColor Green

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

$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$backupRoot = Join-Path $Root ('production-unification-backup-' + $stamp)
$tempRoot = Join-Path $Root ('.production-unification-sync-' + $stamp)
New-Item -ItemType Directory -Path $backupRoot -Force | Out-Null
New-Item -ItemType Directory -Path $tempRoot -Force | Out-Null

Write-Host '[1/6] Downloading exact coordinated source generation...' -ForegroundColor Cyan
foreach ($rel in $SourceFiles) {
    $url = $RawBase + '/' + $rel
    $tmp = Join-Path $tempRoot ($rel -replace '/', '\')
    New-Item -ItemType Directory -Path (Split-Path -Parent $tmp) -Force | Out-Null
    Invoke-WebRequest -UseBasicParsing -Uri $url -OutFile $tmp
    if (!(Test-Path $tmp) -or (Get-Item $tmp).Length -lt 10) {
        throw ('Source download failed or was empty: ' + $rel)
    }
}

# Validate the downloaded generation before touching local source.
$downloadEra = Get-Content -LiteralPath (Join-Path $tempRoot 'server\plugins-src\EraCore\src\main\java\dev\jorel\eracore\EraCore.java') -Raw
$downloadWorld = Get-Content -LiteralPath (Join-Path $tempRoot 'server\plugins-src\EraCore\src\main\java\dev\jorel\eracore\HcfWorldBuildDirector.java') -Raw
$downloadReset = Get-Content -LiteralPath (Join-Path $tempRoot 'server\Prepare-HCF-Season-Reset.ps1') -Raw
$downloadWorker = Get-Content -LiteralPath (Join-Path $tempRoot 'bots\src\worker-pool.js') -Raw
$downloadMapAi = Get-Content -LiteralPath (Join-Path $tempRoot 'bots\src\hcf-map-intelligence.js') -Raw
if ($downloadEra -notmatch 'migrateProductionUnificationConfig') { throw 'Downloaded EraCore is not the production-unification generation.' }
if ($downloadEra -notmatch 'cmdSimTab') { throw 'Downloaded EraCore is missing /simtab diagnostics.' }
if ($downloadWorld -notmatch 'STRUCTURES' -or $downloadWorld -notmatch 'RESOURCES') { throw 'Downloaded staged world builder is incomplete.' }
$forbiddenWorkerTravel = @(
    "tryCommand(state,'/warp",
    "tryCommand(state, '/warp",
    "tryCommand(state,'/spawn",
    "tryCommand(state, '/spawn",
    "tryCommand(state,'/oremountain",
    "tryCommand(state, '/oremountain",
    "queueBotCommand(state,'/stuck",
    "queueBotCommand(state, '/stuck"
)
foreach ($needle in $forbiddenWorkerTravel) {
    if ($downloadWorker.Contains($needle)) {
        throw ('Downloaded Mineflayer runtime still contains a player teleport shortcut: ' + $needle)
    }
}
if ($downloadWorker -notmatch 'enterFactionPortal' -or $downloadWorker -notmatch 'tryFactionHome') {
    throw 'Downloaded Mineflayer runtime is missing physical HCF travel.'
}
if ($downloadMapAi -notmatch "factionHome:\s*'/f home'") {
    throw 'Downloaded HCF map intelligence is missing faction-home routing.'
}
if ($downloadMapAi -match "x:\s*650" -or $downloadMapAi -match "z:\s*-?650") {
    throw 'Downloaded HCF map intelligence still contains the obsolete +/-650 KOTH layout.'
}
if ($downloadWorld -notmatch 'restartSotwProtectionClock') {
    throw 'Downloaded world builder does not start the SOTW clock at map readiness.'
}
if ($downloadEra -notmatch 'permanent-speed-2' -or $downloadEra -notmatch 'factionPrefix') {
    throw 'Downloaded EraCore is missing HCF v4 movement/presentation rules.'
}
[void][ScriptBlock]::Create($downloadReset)
Write-Host '[OK] Downloaded source/reset/worker validation passed.' -ForegroundColor Green

Write-Host '[2/6] Creating rollback backup...' -ForegroundColor Cyan
if (Test-Path $PluginJar) {
    Copy-Item -LiteralPath $PluginJar -Destination (Join-Path $backupRoot 'EraCore.jar') -Force
}

$stateBackup = Join-Path $backupRoot 'EraCore-state'
New-Item -ItemType Directory -Path $stateBackup -Force | Out-Null
if (Test-Path $PluginData) {
    $stateNames = @(
        'config.yml','simulation.yml','factions.yml','claims-v2.yml','events.yml',
        'economy.yml','kits.yml','ranks.yml','stats.yml','infrastructure.yml',
        'warps.yml','safezones.yml','rewards.yml','memory-events.log'
    )
    foreach ($name in $stateNames) {
        $p = Join-Path $PluginData $name
        if (Test-Path $p) { Copy-Item -LiteralPath $p -Destination $stateBackup -Force }
    }
}

$sourceBackup = Join-Path $backupRoot 'EraCore-source'
if (Test-Path (Join-Path $Server 'plugins-src\EraCore')) {
    Copy-Item -LiteralPath (Join-Path $Server 'plugins-src\EraCore') -Destination $sourceBackup -Recurse -Force
}
if (Test-Path $ResetScript) {
    Copy-Item -LiteralPath $ResetScript -Destination (Join-Path $backupRoot 'Prepare-HCF-Season-Reset.ps1') -Force
}
$botBackup = Join-Path $backupRoot 'bots-src'
New-Item -ItemType Directory -Path $botBackup -Force | Out-Null
foreach ($name in @('worker-pool.js','hcf-map-intelligence.js')) {
    $p = Join-Path $Root ('bots\src\' + $name)
    if (Test-Path $p) { Copy-Item -LiteralPath $p -Destination (Join-Path $botBackup $name) -Force }
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
$Source = Join-Path $Here 'EraCore-source'
if (Test-Path $Source) {
    $dst = Join-Path $Server 'plugins-src\EraCore'
    if (Test-Path $dst) { Remove-Item -LiteralPath $dst -Recurse -Force }
    Copy-Item -LiteralPath $Source -Destination $dst -Recurse -Force
}
if (Test-Path (Join-Path $Here 'Prepare-HCF-Season-Reset.ps1')) {
    Copy-Item -LiteralPath (Join-Path $Here 'Prepare-HCF-Season-Reset.ps1') -Destination (Join-Path $Server 'Prepare-HCF-Season-Reset.ps1') -Force
}
$BotSource = Join-Path $Here 'bots-src'
if (Test-Path $BotSource) {
    $BotDst = Join-Path $Root 'bots\src'
    New-Item -ItemType Directory -Path $BotDst -Force | Out-Null
    Get-ChildItem -LiteralPath $BotSource -File | ForEach-Object {
        Copy-Item -LiteralPath $_.FullName -Destination (Join-Path $BotDst $_.Name) -Force
    }
}
Write-Host 'Rollback restored EraCore jar/source/state and Mineflayer runtime.' -ForegroundColor Green
'@
Set-Content -LiteralPath (Join-Path $backupRoot 'rollback.ps1') -Value $rollback -Encoding UTF8
Write-Host ('Backup: ' + $backupRoot) -ForegroundColor DarkGray

Write-Host '[3/6] Installing one coordinated EraCore source generation...' -ForegroundColor Cyan
New-Item -ItemType Directory -Path $JavaSourceDir -Force | Out-Null
Get-ChildItem -LiteralPath $JavaSourceDir -Filter '*.java' -File -ErrorAction SilentlyContinue | Remove-Item -Force

foreach ($rel in $SourceFiles) {
    $src = Join-Path $tempRoot ($rel -replace '/', '\')
    $dst = Join-Path $Root ($rel -replace '/', '\')
    New-Item -ItemType Directory -Path (Split-Path -Parent $dst) -Force | Out-Null
    Copy-Item -LiteralPath $src -Destination $dst -Force
}
Remove-Item -LiteralPath $tempRoot -Recurse -Force

if ($ColdStorageRoot) {
    $ColdStorageRoot = [IO.Path]::GetFullPath($ColdStorageRoot)
    New-Item -ItemType Directory -Path $ColdStorageRoot -Force | Out-Null
    foreach ($sub in @('world-archives','memory-archive')) {
        New-Item -ItemType Directory -Path (Join-Path $ColdStorageRoot $sub) -Force | Out-Null
    }
    Set-Content -LiteralPath (Join-Path $Server 'cold-storage-root.txt') -Value $ColdStorageRoot -Encoding UTF8
    Write-Host ('[OK] Cold storage: ' + $ColdStorageRoot) -ForegroundColor Green
}

Write-Host '[4/6] Compiling EraCore with Java 8...' -ForegroundColor Cyan
& $BuildScript -SpigotJar $SpigotJar -JavaHome $javaHome
if ($LASTEXITCODE -ne 0) {
    throw ('EraCore build failed. Rollback: powershell -ExecutionPolicy Bypass -File "' + (Join-Path $backupRoot 'rollback.ps1') + '"')
}
if (!(Test-Path $PluginJar)) { throw 'Build returned without producing server\plugins\EraCore.jar.' }

Write-Host '[5/6] Validating built jar and commands...' -ForegroundColor Cyan
$jarList = (& $jarTool tf $PluginJar | Out-String)
$mustContain = @(
    'plugin.yml',
    'dev/jorel/eracore/EraCore.class',
    'dev/jorel/eracore/HcfWorldBuildDirector.class',
    'dev/jorel/eracore/HcfBaseBuilder.class',
    'dev/jorel/eracore/HcfResourceDirector.class',
    'dev/jorel/eracore/HcfSidebarDirector.class',
    'dev/jorel/eracore/LogicalTabListDirector.class',
    'dev/jorel/eracore/AiChatBridge.class'
)
foreach ($entry in $mustContain) {
    if ($jarList -notmatch [regex]::Escape($entry)) { throw ('Built jar is missing ' + $entry) }
}

$pluginYml = Join-Path $Server 'plugins-src\EraCore\src\main\resources\plugin.yml'
$pluginText = Get-Content -LiteralPath $pluginYml -Raw
foreach ($cmd in @('simtab:','mapcompose:','sotw:','baserebuild:','simactor:')) {
    if ($pluginText -notmatch [regex]::Escape($cmd)) { throw ('plugin.yml validation failed: ' + $cmd) }
}
[void][ScriptBlock]::Create((Get-Content -LiteralPath $ResetScript -Raw))

Write-Host '[6/6] Deployment validation complete.' -ForegroundColor Green
Write-Host ''
Write-Host 'Installed capabilities:' -ForegroundColor Cyan
Write-Host '  - staged automatic v7 production-map build with MSPT throttling'
Write-Host '  - canonical KOTH/portal/conquest geometry'
Write-Host '  - hybrid five-family faction bases + rectangular claim containment'
Write-Host '  - local-first contextual chat + semantic anti-repeat + hard AI budget'
Write-Host '  - bounded hot memory + compressed cold history shards'
Write-Host '  - automatic KOTH/Conquest rotation + classic right-side countdown sidebar'
Write-Host '  - color-only donor presentation + canonical creator identities'
Write-Host '  - verified full-world SOTW reset + Kraken spawn-origin alignment'
Write-Host '  - physical HCF travel: /f home warmup, /f stuck, no player spawn/warp shortcuts'
Write-Host '  - SOTW base/resource rush with physical Nether/End portal use'
Write-Host '  - permanent Speed II; no Speed/Fire Resistance economy or kit stock'
Write-Host '  - synchronized Mineflayer HCF travel/resource runtime'
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
Write-Host '  5. In game: /simprobe'
Write-Host '  6. Wait 20-30 seconds, then /simprobe again'
Write-Host '  7. Do NOT /sotw reset confirm until this baseline is healthy.'
