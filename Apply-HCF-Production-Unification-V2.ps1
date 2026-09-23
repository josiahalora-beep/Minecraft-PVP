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
$SourceCommit = '3425a7c0f8d8d0ce0da5d02317110a6c0a209a7a'
$RawBase = 'https://raw.githubusercontent.com/josiahalora-beep/Minecraft-PVP/' + $SourceCommit
$SourceFiles = @(
    'server/plugins-src/EraCore/src/main/java/dev/jorel/eracore/ActorDirectory.java',
    'server/plugins-src/EraCore/src/main/java/dev/jorel/eracore/AiChatBridge.java',
    'server/plugins-src/EraCore/src/main/java/dev/jorel/eracore/ContextChatBrain.java',
    'server/plugins-src/EraCore/src/main/java/dev/jorel/eracore/EraCore.java',
    'server/plugins-src/EraCore/src/main/java/dev/jorel/eracore/HcfAutoBrewerDirector.java',
    'server/plugins-src/EraCore/src/main/java/dev/jorel/eracore/HcfAtmosphereDirector.java',
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
    'server/start-server.bat',
    'bots/package.json',
    'bots/src/worker-pool.js',
    'bots/src/team-combat.js',
    'bots/src/worker-coordinator.js',
    'bots/src/hcf-map-intelligence.js',
    'bots/src/community-ai.js',
    'Start-Daegon-With-Workers.ps1',
    'Start-Daegon-Adaptive.ps1',
    'docs/ACTOR_RUNTIME.md',
    'docs/BUILD_VIEWER_CONTRACT.md'
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
$downloadLauncher = Get-Content -LiteralPath (Join-Path $tempRoot 'server\start-server.bat') -Raw
$downloadWorker = Get-Content -LiteralPath (Join-Path $tempRoot 'bots\src\worker-pool.js') -Raw
$downloadTeamCombat = Get-Content -LiteralPath (Join-Path $tempRoot 'bots\src\team-combat.js') -Raw
$downloadCoordinator = Get-Content -LiteralPath (Join-Path $tempRoot 'bots\src\worker-coordinator.js') -Raw
$downloadBotPackage = Get-Content -LiteralPath (Join-Path $tempRoot 'bots\package.json') -Raw
$downloadMapAi = Get-Content -LiteralPath (Join-Path $tempRoot 'bots\src\hcf-map-intelligence.js') -Raw
$downloadCommunityAi = Get-Content -LiteralPath (Join-Path $tempRoot 'bots\src\community-ai.js') -Raw
$downloadComposer = Get-Content -LiteralPath (Join-Path $tempRoot 'server\plugins-src\EraCore\src\main\java\dev\jorel\eracore\LegacySchematicComposer.java') -Raw
$downloadTerrain = Get-Content -LiteralPath (Join-Path $tempRoot 'server\plugins-src\EraCore\src\main\java\dev\jorel\eracore\HcfTerrainDirector.java') -Raw
$downloadBasePlan = Get-Content -LiteralPath (Join-Path $tempRoot 'server\plugins-src\EraCore\src\main\java\dev\jorel\eracore\HcfBasePlan.java') -Raw
$downloadBaseBuilder = Get-Content -LiteralPath (Join-Path $tempRoot 'server\plugins-src\EraCore\src\main\java\dev\jorel\eracore\HcfBaseBuilder.java') -Raw
$downloadSimWorld = Get-Content -LiteralPath (Join-Path $tempRoot 'server\plugins-src\EraCore\src\main\java\dev\jorel\eracore\SimWorldDirector.java') -Raw
$downloadStackLauncher = Get-Content -LiteralPath (Join-Path $tempRoot 'Start-Daegon-With-Workers.ps1') -Raw
$downloadAdaptiveLauncher = Get-Content -LiteralPath (Join-Path $tempRoot 'Start-Daegon-Adaptive.ps1') -Raw
if ($downloadEra -notmatch 'migrateProductionUnificationConfig') { throw 'Downloaded EraCore is not the production-unification generation.' }
if ($downloadEra -match 'factionSuffix\s*\(') { throw 'Downloaded EraCore still contains the removed factionSuffix method reference.' }
if ($downloadEra -notmatch 'cmdSimTab') { throw 'Downloaded EraCore is missing /simtab diagnostics.' }
if ($downloadWorld -notmatch 'STRUCTURES' -or $downloadWorld -notmatch 'RESOURCES') { throw 'Downloaded staged world builder is incomplete.' }
if ($downloadTerrain -notmatch 'terrain\.spawn-flat-radius' -or
    $downloadTerrain -notmatch 'terrain\.spawn-transition-radius' -or
    $downloadTerrain -notmatch 'terrain\.wilderness-amplitude' -or
    $downloadTerrain -notmatch 'Natural HCF terrain v3' -or
    $downloadTerrain -notmatch 'valueNoise\(' -or
    $downloadTerrain -notmatch 'koth-classic-flat-radius') {
    throw 'Downloaded terrain director is missing the natural HCF v3 terrain contract.'
}
if ($downloadBasePlan -notmatch 'Family selection happens BEFORE dimensions' -or
    $downloadBasePlan -notmatch 'coreHalfX\+20' -or
    $downloadBasePlan -notmatch 'war-room' -or
    $downloadBaseBuilder -notmatch 'prepareTerrainPad\(World w,HcfBasePlan p\)' -or
    $downloadBaseBuilder -notmatch 'buildCoreUtilityModules' -or
    $downloadBaseBuilder -notmatch 'surfaceWallMaterial' -or
    $downloadBaseBuilder -notmatch 'surfaceRoofMaterial' -or
    $downloadBaseBuilder -notmatch '\[base-plan\]') {
    throw 'Downloaded base compiler is missing the Build Viewer topology/terraforming contract.'
}
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
if ($downloadTeamCombat -notmatch 'defendUntil' -or
    $downloadTeamCombat -notmatch 'server flips them to ENGAGE' -or
    $downloadTeamCombat -match 'Date\.now\(\)-fightStartedAt>=delay\) return true') {
    throw 'Downloaded team combat runtime is missing the non-automatic watch/shadow/cleanup behavior.'
}
if ($downloadSimWorld -notmatch 'updateThirdPartyOpportunities' -or
    $downloadSimWorld -notmatch 'maybeActivateThirdPartyAfterDeath' -or
    $downloadSimWorld -notmatch 'premiumGearAce' -or
    $downloadSimWorld -notmatch 'takeBestSword\(inv,f,requiredSword,premiumHolder\)' -or
    $downloadSimWorld -notmatch 'ca\.enemies\.contains\(thirdName\)' -or
    $downloadSimWorld -notmatch 'FIRE_ASPECT,2') {
    throw 'Downloaded simulation is missing third-party opportunism, persistent retaliation or ace-safe premium gear allocation.'
}
if ($downloadMapAi -notmatch "factionHome:\s*'/f home'") {
    throw 'Downloaded HCF map intelligence is missing faction-home routing.'
}
if ($downloadStackLauncher -notmatch 'WORKER_COORDINATOR_URL=http://127\.0\.0\.1:8770' -or
    $downloadAdaptiveLauncher -notmatch 'Start-Daegon-With-Workers\.ps1') {
    throw 'Downloaded Daegon launcher chain is incomplete.'
}
if ($downloadCoordinator -notmatch 'server\.listen\(PORT,BIND' -or
    $downloadCoordinator -notmatch 'worker-coordinator' -or
    $downloadBotPackage -notmatch '"coordinator"\s*:\s*"node src/worker-coordinator\.js"') {
    throw 'Downloaded worker coordinator runtime is incomplete.'
}
if ($downloadEra -notmatch 'production-unification-version",6' -or
    $downloadEra -notmatch 'world-composer\.blocks-per-tick",320') {
    throw 'Downloaded EraCore is missing production pacing/presentation v6.'
}
if ($downloadWorld -notmatch 'jobProgress=' -or $downloadComposer -notmatch 'writes=') {
    throw 'Downloaded production composer is missing scan/write-aware progress reporting.'
}
if ($downloadComposer -notmatch 'Kraken Spawn[\s\S]{0,500},false\)') {
    throw 'Downloaded production composer still allows Kraken selection air to carve the terrain.'
}
if ($downloadSimWorld -notmatch 'uniquePlayerNames' -or
    $downloadSimWorld -notmatch 'name-skill-model-version",3') {
    throw 'Downloaded simulation is missing unique-name/rare-handle skill model v3.'
}
if ($downloadCommunityAi -notmatch 'EADDRINUSE') {
    throw 'Downloaded community AI bridge can still kill the coordinator on an occupied AI port.'
}
if ($downloadStackLauncher -notmatch 'coordinator\.err\.log' -or
    $downloadStackLauncher -notmatch 'node_modules\\yaml') {
    throw 'Downloaded adaptive launcher is missing coordinator diagnostics/dependency preflight.'
}
if ($downloadMapAi -match "x:\s*650" -or $downloadMapAi -match "z:\s*-?650") {
    throw 'Downloaded HCF map intelligence still contains the obsolete +/-650 KOTH layout.'
}
if ($downloadWorld -notmatch 'restartSotwProtectionClock') {
    throw 'Downloaded world builder does not start the SOTW clock at map readiness.'
}
$downloadEvents = Get-Content -LiteralPath (Join-Path $tempRoot 'server\plugins-src\EraCore\src\main\java\dev\jorel\eracore\HcfEventDirector.java') -Raw
if ($downloadEvents -notmatch 'simWorldProtectionMillisLeft' -or
    $downloadEvents -notmatch 'SOTW is the opening progression race') {
    throw 'Downloaded event scheduler still has the repeated SOTW 5-minute warning behavior.'
}
if ($downloadEra -notmatch 'permanent-speed-2' -or $downloadEra -notmatch 'factionPrefix') {
    throw 'Downloaded EraCore is missing HCF v4 movement/presentation rules.'
}
if ($downloadEra -notmatch 'ensureRuntimeConfigReadable' -or
    $downloadEra -notmatch 'consumeSeasonResetReceipt') {
    throw 'Downloaded EraCore is missing safe config recovery/reset-receipt handling.'
}
$downloadInfrastructure = Get-Content -LiteralPath (Join-Path $tempRoot 'server\plugins-src\EraCore\src\main\java\dev\jorel\eracore\HcfInfrastructureDirector.java') -Raw
$downloadRewards = Get-Content -LiteralPath (Join-Path $tempRoot 'server\plugins-src\EraCore\src\main\java\dev\jorel\eracore\SpawnRewardsDirector.java') -Raw
$downloadSim = Get-Content -LiteralPath (Join-Path $tempRoot 'server\plugins-src\EraCore\src\main\java\dev\jorel\eracore\SimWorldDirector.java') -Raw
if ($downloadEra -notmatch 'armor\(helmet,1\)' -or
    $downloadEra -notmatch 'sword\(swordMat,1\)') {
    throw 'Downloaded EraCore is not using the Protection I / Sharpness I normal combat baseline.'
}
if ($downloadRewards -notmatch 'FULL.*P2 SET' -or
    $downloadRewards -notmatch 'rareSword\(fire\)' -or
    $downloadRewards -notmatch 'KOTH set \+ S2/Fire II') {
    throw 'Downloaded reward director is missing the tiered rare P2/S2/Fire jackpot economy.'
}
$downloadComposer = Get-Content -LiteralPath (Join-Path $tempRoot 'server\plugins-src\EraCore\src\main\java\dev\jorel\eracore\LegacySchematicComposer.java') -Raw
if ($downloadInfrastructure -notmatch 'Production schematic mode: preserving Kraken \+ Nether/End geometry exactly') {
    throw 'Downloaded infrastructure director can still fall back to generic production geometry.'
}
if ($downloadRewards -notmatch 'placeFunctionalCrate' -or $downloadRewards -notmatch 'Refusing to overwrite Kraken block') {
    throw 'Downloaded rewards director is missing non-destructive Kraken crate placement.'
}
if ($downloadSim -notmatch 'case-insensitive unique identities' -or $downloadSim -notmatch 'namePrestigeTier') {
    throw 'Downloaded simulation is missing unique/prestige-aware HCF identities.'
}
if ($downloadComposer -notmatch 'Kraken Spawn.*false' -or $downloadComposer -notmatch 'selection padding, not instructions to excavate') {
    throw 'Downloaded composer is not using non-air Kraken production paste.'
}
if ($downloadReset -match 'Set-YamlScalar' -or
    $downloadReset -match 'Set-Content -LiteralPath \$config') {
    throw 'Downloaded reset preflight still mutates config.yml directly.'
}
if ($downloadLauncher -notmatch 'season-reset\.pending' -or
    $downloadLauncher -notmatch 'Prepare-HCF-Season-Reset\.ps1' -or
    $downloadLauncher -notmatch 'if errorlevel 1' -or
    $downloadLauncher -match '-ServerRoot') {
    throw 'Downloaded start-server.bat does not enforce the safe script-local SOTW reset preflight.'
}
if ($downloadReset -notmatch 'GetFullPath' -or
    $downloadReset -notmatch 'Test-Path -LiteralPath \$marker') {
    throw 'Downloaded reset script is missing normalized literal-path handling.'
}
[void][ScriptBlock]::Create($downloadReset)
Write-Host '[OK] Downloaded source/reset/launcher/worker validation passed.' -ForegroundColor Green

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
$startBat = Join-Path $Server 'start-server.bat'
if (Test-Path $startBat) {
    Copy-Item -LiteralPath $startBat -Destination (Join-Path $backupRoot 'start-server.bat') -Force
}
foreach ($name in @('Start-Daegon-With-Workers.ps1','Start-Daegon-Adaptive.ps1')) {
    $p = Join-Path $Root $name
    if (Test-Path $p) { Copy-Item -LiteralPath $p -Destination (Join-Path $backupRoot $name) -Force }
}
$botBackup = Join-Path $backupRoot 'bots-src'
New-Item -ItemType Directory -Path $botBackup -Force | Out-Null
foreach ($name in @('worker-pool.js','team-combat.js','hcf-map-intelligence.js','community-ai.js')) {
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
if (Test-Path (Join-Path $Here 'start-server.bat')) {
    Copy-Item -LiteralPath (Join-Path $Here 'start-server.bat') -Destination (Join-Path $Server 'start-server.bat') -Force
}
foreach ($name in @('Start-Daegon-With-Workers.ps1','Start-Daegon-Adaptive.ps1')) {
    $p = Join-Path $Here $name
    if (Test-Path $p) { Copy-Item -LiteralPath $p -Destination (Join-Path $Root $name) -Force }
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
Write-Host '  - scan/write-aware v7 production-map build with Kraken air-padding preserved as terrain'
Write-Host '  - natural HCF terrain v3: ~175 flat spawn apron, 175-300 transition, +/-7 domain-warped wilderness'
Write-Host '  - narrow readable roads + event-specific terrain pads sized to the real KOTH/Conquest builds'
Write-Host '  - clustered grass-dominant terrain materials: restrained dirt/gravel/rock/dry-region patches'
Write-Host '  - permanent clear noon presentation: rain/thunder rejected and time automatically restored'
Write-Host '  - blended faction-site terraforming: flat PvP frontage without giant square plateaus'
Write-Host '  - five real base topologies: Redemption / Base-HCF / ModernHCF / Tunnel / Cave'
Write-Host '  - family-specific exterior silhouettes/walls/roofs instead of one repeated glass-box shell'
Write-Host '  - underground compiler modules: dropdown/elevator, 14-dub storage, enchant, war-room, utility, farm, brewer, portals, traps'
Write-Host '  - claim envelope covers the farthest compiled module plus the configured outside buffer'
Write-Host '  - minimal Kraken crate row: Vote chest + donor Ender Chest + KOTH chest'
Write-Host '  - unique period usernames with rare 4-6 letter handles weighted toward elite PvP'
Write-Host '  - restored Start-Daegon-Adaptive.ps1 -> current 8770 coordinator/worker stack with logs'
Write-Host '  - canonical KOTH/portal/conquest geometry'
Write-Host '  - hybrid five-family faction bases + rectangular claim containment'
Write-Host '  - local-first contextual chat + semantic anti-repeat + hard AI budget'
Write-Host '  - Protection I / Sharpness I normal PvP; rare P2 + S2/Fire I prestige loot'
Write-Host '  - ultra-rare full KOTH jackpot carries the exceptional S2/Fire II sword'
Write-Host '  - warzone etiquette: pass/watch/shadow/cleanup, low-health/death opportunism, self-defense'
Write-Host '  - novice SOTW guidance + social recruiting/vouch/tryout behavior'
Write-Host '  - faction premium gear is banked for the roster''s best diamond PvPer'
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
Write-Host ''
Write-Host 'Kraken terrain note:' -ForegroundColor Yellow
Write-Host '  - This build prevents Kraken AIR padding from carving terrain on future fresh builds.'
Write-Host '  - It does NOT magically refill terrain already erased in the current holed world.'
Write-Host '  - /mapcompose spawn only re-pastes non-air Kraken blocks; it is not an old-hole repair.'
Write-Host '  - After validation, a fresh /sotw reset confirm + normal start is the safe way to regenerate the erased terrain.'
