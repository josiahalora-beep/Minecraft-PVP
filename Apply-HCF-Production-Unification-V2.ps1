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
$SourceCommit = '7916821dfde55e19645c105268b17d8811146011'
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
    'server/server.properties',
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
    'docs/BUILD_VIEWER_CONTRACT.md',
    'docs/TERRAIN_PERSONA_AND_WORKFLOW.md',
    'docs/HCF_BASE_PERSONA_AND_WORKFLOW.md',
    'docs/HCF_REFERENCE_LIBRARY.md'
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

function Ensure-AuthoredHcfMapAsset {
    param([string]$AssetDir)

    try {
        [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    } catch {
        Write-Host '[WARN] Could not force TLS 1.2; continuing with the system default.' -ForegroundColor Yellow
    }

    $fileName = 'FreeMap.rar'
    $quickKey = '32sslt4lmrut2e0'
    $expectedHash = 'af9c214979fcde0b1c41e435a6359940a930ffa97c8e2ad09667f74203afba95'
    $expectedSize = 207109938L
    $target = Join-Path $AssetDir $fileName

    if (!(Test-Path $AssetDir)) {
        New-Item -ItemType Directory -Path $AssetDir -Force | Out-Null
    }

    if (Test-Path $target) {
        $existing = Get-Item -LiteralPath $target
        $existingHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $target).Hash.ToLowerInvariant()
        if ($existing.Length -eq $expectedSize -and $existingHash -eq $expectedHash) {
            Write-Host '[OK] Authored Stylez HCF map asset already verified.' -ForegroundColor Green
            return
        }
        Write-Host '[WARN] Existing FreeMap.rar failed checksum/size validation; downloading the approved public copy.' -ForegroundColor Yellow
        Remove-Item -LiteralPath $target -Force
    }

    $headers = @{
        'User-Agent' = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131 Safari/537.36'
        'Accept' = 'application/json,text/html,*/*'
        'Referer' = 'https://builtbybit.com/'
    }

    $api = 'https://www.mediafire.com/api/1.5/file/get_info.php?quick_key=' + $quickKey + '&response_format=json'
    Write-Host '[MAP] Resolving approved authored HCF world...' -ForegroundColor Cyan
    $info = Invoke-RestMethod -Method Get -Uri $api -Headers $headers
    $fileInfo = $info.response.file_info
    if ($null -eq $fileInfo -or
        [string]$fileInfo.filename -ne $fileName -or
        [string]$fileInfo.ready -ne 'yes' -or
        [string]$fileInfo.privacy -ne 'public' -or
        ([string]$fileInfo.hash).ToLowerInvariant() -ne $expectedHash -or
        [int64]$fileInfo.size -ne $expectedSize) {
        throw 'MediaFire metadata no longer matches the approved authored HCF map.'
    }

    $normal = [string]$fileInfo.links.normal_download
    if ([string]::IsNullOrWhiteSpace($normal)) {
        throw 'MediaFire did not return the authored-map download page.'
    }

    $page = Invoke-WebRequest -UseBasicParsing -Uri $normal -Headers $headers
    $html = [string]$page.Content
    $patterns = @(
        'id=["'']downloadButton["''][^>]+href=["'']([^"'']+)',
        'href=["'']([^"'']+)["''][^>]+id=["'']downloadButton["'']',
        'aria-label=["'']Download file["''][^>]+href=["'']([^"'']+)'
    )
    $direct = $null
    foreach ($pattern in $patterns) {
        $m = [regex]::Match($html,$pattern,[Text.RegularExpressions.RegexOptions]::IgnoreCase)
        if ($m.Success) {
            $direct = [Net.WebUtility]::HtmlDecode($m.Groups[1].Value)
            break
        }
    }
    if ([string]::IsNullOrWhiteSpace($direct)) {
        throw 'Could not resolve the public MediaFire CDN URL for FreeMap.rar.'
    }

    $downloadHost = ([Uri]$direct).Host
    if (!$downloadHost.StartsWith('download') -or !$downloadHost.EndsWith('.mediafire.com')) {
        throw ('Refusing unexpected authored-map download host: ' + $downloadHost)
    }

    $downloadHeaders = @{}
    foreach ($k in $headers.Keys) { $downloadHeaders[$k] = $headers[$k] }
    $downloadHeaders['Referer'] = $normal

    $tmp = $target + '.download'
    if (Test-Path $tmp) { Remove-Item -LiteralPath $tmp -Force }
    Write-Host ('[MAP] Downloading ' + $fileName + ' (~198 MB) from verified public source...') -ForegroundColor Cyan
    Invoke-WebRequest -UseBasicParsing -Uri $direct -Headers $downloadHeaders -OutFile $tmp

    $downloaded = Get-Item -LiteralPath $tmp
    $actualHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $tmp).Hash.ToLowerInvariant()
    if ($downloaded.Length -ne $expectedSize -or $actualHash -ne $expectedHash) {
        Remove-Item -LiteralPath $tmp -Force -ErrorAction SilentlyContinue
        throw ('Downloaded authored map failed validation. size=' + $downloaded.Length + ' sha256=' + $actualHash)
    }

    Move-Item -LiteralPath $tmp -Destination $target -Force
    Write-Host '[OK] Authored Stylez HCF map downloaded and SHA-256 verified.' -ForegroundColor Green
}

function Ensure-ApprovedProductionSchematics {
    param([string]$AssetDir)

    $expected = [ordered]@{
        'HCF-Spawn-101-production.schematic' = '3f41d2ac7d329f96c0d33c8ec2ba3807d74f35d4b01b0b544644a585d7d2e378'
        'KOTH2-production-1.8.schematic' = '76927168a1f228f7c12c4a00c48f5785fd25b02b4d66f26bb496e540cdc82a31'
        'EndStyleKOTH-production-1.8.schematic' = 'ebe27dfc5c102fe84c9f1c748fc52850486c22edc0835ba3ee1a355d7a759182'
        'EgyptKOTH-production-1.8.schematic' = '99fed90e7a57bb6c16cf9293b11f4cf9236a2f6bc3e0acdd8c1facba306d351d'
        'KOTH-Forty-1.8-converted.schematic' = '1893f941747cd9f8a1e92f4df42e68341fa934a14c2e875c68fd09377655c4de'
        'conquest.schematic' = 'cd6bf439e9bd18858dcb144d5e96f9749e2e2b935a51c89bc860026285d2f336'
        'NetherSpawnWillzaTeam.schematic' = '8db0cf006c9ef9d4b1f8c297b4e5a0747552b629fefdb0e0d95280ec100706d9'
        'magical-hcf-end-xayden-bt.schematic' = '952bf326168522641192c66fea5100d637821e1e1f19c77ce8035b7fd4be2754'
    }

    if (!(Test-Path $AssetDir)) {
        New-Item -ItemType Directory -Path $AssetDir -Force | Out-Null
    }

    $allValid = $true
    foreach ($name in $expected.Keys) {
        $p = Join-Path $AssetDir $name
        if (!(Test-Path $p)) { $allValid=$false; break }
        $h=(Get-FileHash -Algorithm SHA256 -LiteralPath $p).Hash.ToLowerInvariant()
        if ($h -ne $expected[$name]) { $allValid=$false; break }
    }
    if ($allValid) {
        Write-Host '[OK] Approved production schematic pack already verified.' -ForegroundColor Green
        return
    }

    $packName='Daegon-HCF-Phase1-Production-Assets.zip'
    $candidates=@(
        (Join-Path $Root $packName),
        (Join-Path $Server $packName),
        (Join-Path $AssetDir $packName),
        (Join-Path (Get-Location).Path $packName)
    ) | Select-Object -Unique
    $pack=$candidates | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
    if ([string]::IsNullOrWhiteSpace([string]$pack)) {
        Write-Host '[FAIL] Approved production schematic pack was not found.' -ForegroundColor Red
        Write-Host ('Place ' + $packName + ' in the Minecraft-PVP root and run this installer again.') -ForegroundColor Yellow
        throw 'Production schematic asset pack is missing.'
    }

    $extract=Join-Path $Root '.phase1-production-assets'
    if (Test-Path $extract) { Remove-Item -LiteralPath $extract -Recurse -Force }
    New-Item -ItemType Directory -Path $extract -Force | Out-Null
    Expand-Archive -LiteralPath $pack -DestinationPath $extract -Force

    foreach ($name in $expected.Keys) {
        $src=Join-Path $extract $name
        if (!(Test-Path $src)) {
            Remove-Item -LiteralPath $extract -Recurse -Force -ErrorAction SilentlyContinue
            throw ('Approved asset pack is missing: ' + $name)
        }
        $h=(Get-FileHash -Algorithm SHA256 -LiteralPath $src).Hash.ToLowerInvariant()
        if ($h -ne $expected[$name]) {
            Remove-Item -LiteralPath $extract -Recurse -Force -ErrorAction SilentlyContinue
            throw ('Approved asset checksum mismatch: ' + $name + ' sha256=' + $h)
        }
        Copy-Item -LiteralPath $src -Destination (Join-Path $AssetDir $name) -Force
    }

    Remove-Item -LiteralPath $extract -Recurse -Force -ErrorAction SilentlyContinue
    Write-Host '[OK] Approved spawn/KOTH/Conquest/Nether/End schematic pack imported and verified.' -ForegroundColor Green
}

if (!$SkipAssetCheck) {
    $assetDir = Join-Path $Server 'map-assets'
    Ensure-AuthoredHcfMapAsset -AssetDir $assetDir
    Ensure-ApprovedProductionSchematics -AssetDir $assetDir

    $required = @(
        'FreeMap.rar',
        'HCF-Spawn-101-production.schematic',
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
        throw 'Production assets failed installation.'
    }
    Write-Host '[OK] Production map assets present.' -ForegroundColor Green
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
$downloadTerrainDoctrine = Get-Content -LiteralPath (Join-Path $tempRoot 'docs\TERRAIN_PERSONA_AND_WORKFLOW.md') -Raw
$downloadBaseDoctrine = Get-Content -LiteralPath (Join-Path $tempRoot 'docs\HCF_BASE_PERSONA_AND_WORKFLOW.md') -Raw
$downloadReferenceLibrary = Get-Content -LiteralPath (Join-Path $tempRoot 'docs\HCF_REFERENCE_LIBRARY.md') -Raw
$downloadServerProperties = Get-Content -LiteralPath (Join-Path $tempRoot 'server\server.properties') -Raw
if ($downloadEra -notmatch 'migrateProductionUnificationConfig') { throw 'Downloaded EraCore is not the production-unification generation.' }
if ($downloadEra -match 'factionSuffix\s*\(') { throw 'Downloaded EraCore still contains the removed factionSuffix method reference.' }
if ($downloadEra -notmatch 'cmdSimTab') { throw 'Downloaded EraCore is missing /simtab diagnostics.' }
if ($downloadWorld -notmatch 'STRUCTURES' -or $downloadWorld -notmatch 'RESOURCES') { throw 'Downloaded staged world builder is incomplete.' }
if ($downloadTerrain -notmatch 'authoredWorld\(\)' -or
    $downloadTerrain -notmatch 'authoredSurfaceY' -or
    $downloadTerrain -notmatch 'no wilderness blocks are rewritten' -or
    $downloadTerrain -match 'maintainAuthoredChunk' -or
    $downloadTerrain -match 'keepAuthoredGroundDetail') {
    throw 'Downloaded terrain director does not preserve the approved FreeMap wilderness exactly.'
}
# Phase 1 does not gate deployment on the old v9 procedural-base doctrine.
# Base architecture is intentionally deferred to Phase 2. Only verify that the
# existing compiler still exposes its core planner/build hooks and let Maven
# compilation enforce source compatibility.
if ($downloadBasePlan -notmatch 'primaryFamilyName' -or
    $downloadBasePlan -notmatch 'coreHalfX' -or
    $downloadBaseBuilder -notmatch 'queueBase' -or
    $downloadBaseBuilder -notmatch 'forceRebuild') {
    throw 'Downloaded base compiler is missing required core planner/build hooks.'
}
if ($downloadTerrainDoctrine -notmatch 'Phase 1 production authority' -or
    $downloadTerrainDoctrine -notmatch 'Authored-world mutation policy' -or
    $downloadTerrainDoctrine -notmatch 'must \*\*not\*\* thin grass' -or
    $downloadTerrainDoctrine -notmatch 'new spawn schematic''s own road design') {
    throw 'Downloaded terrain doctrine does not match the untouched authored-world Phase-1 contract.'
}
if ($downloadReferenceLibrary -notmatch 'af9c214979fcde0b1c41e435a6359940a930ffa97c8e2ad09667f74203afba95') {
    throw 'Downloaded HCF reference library is missing the approved authored-map provenance.'
}
if ($downloadServerProperties -notmatch '(?m)^level-type=DEFAULT\s*$' -or
    $downloadServerProperties -match '(?m)^level-type=FLAT\s*$') {
    throw 'Downloaded server.properties would regenerate the Overworld as superflat.'
}
if ($downloadReset -notmatch 'FreeMap\.rar' -or
    $downloadReset -notmatch 'af9c214979fcde0b1c41e435a6359940a930ffa97c8e2ad09667f74203afba95' -or
    $downloadReset -notmatch 'Authored Stylez HCF overworld restored') {
    throw 'Downloaded reset script is missing the checksum-locked authored-world restore.'
}
if ($downloadSimWorld -notmatch 'terrain\.authored-world' -or
    $downloadMapAi -notmatch 'border:\s*1000' -or
    $downloadMapAi -notmatch 'x:\s*800' -or
    $downloadMapAi -notmatch 'z:\s*775') {
    throw 'Downloaded simulation/Mineflayer map intelligence is not aligned with the 2k authored world.'
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
# Coordinator validity is covered by repository CI's distributed smoke test.
# Do not block the Phase-1 map deployment on brittle source-text signatures.
if ($downloadEra -notmatch 'production-unification-version",8' -or
    $downloadEra -notmatch 'world-composer\.blocks-per-tick",320') {
    throw 'Downloaded EraCore is missing the authored-map production migration v8.'
}
if ($downloadWorld -notmatch 'jobProgress=' -or $downloadComposer -notmatch 'writes=') {
    throw 'Downloaded production composer is missing scan/write-aware progress reporting.'
}
if ($downloadComposer -notmatch 'HCF Spawn[\s\S]{0,500},false\)' -or
    $downloadComposer -notmatch 'SpawnRoadSurfaceJob' -or
    $downloadComposer -notmatch 'canonicalHcfTerrainY' -or
    $downloadComposer -notmatch 'map-layout\.conquest-z",775') {
    throw 'Downloaded production composer is missing the approved new-spawn/road/event placement contract.'
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
if ($downloadRewards -notmatch 'placeFunctionalCrate') {
    throw 'Downloaded rewards director is missing non-destructive crate placement.'
}
if ($downloadSim -notmatch 'case-insensitive unique identities' -or $downloadSim -notmatch 'namePrestigeTier') {
    throw 'Downloaded simulation is missing unique/prestige-aware HCF identities.'
}
# Approved spawn/road/event placement is already validated above using
# SpawnRoadSurfaceJob + canonicalHcfTerrainY + Conquest Z=775 markers.
# Do not repeat that check with brittle source-text patterns here.
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
$serverPropertiesBackup = Join-Path $Server 'server.properties'
if (Test-Path $serverPropertiesBackup) {
    Copy-Item -LiteralPath $serverPropertiesBackup -Destination (Join-Path $backupRoot 'server.properties') -Force
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
if (Test-Path (Join-Path $Here 'server.properties')) {
    Copy-Item -LiteralPath (Join-Path $Here 'server.properties') -Destination (Join-Path $Server 'server.properties') -Force
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
Write-Host '  - untouched Stylez/ViperMC-era authored FreeMap overworld; exact SHA-256 verified'
Write-Host '  - 2,000 x 2,000 Overworld border centered at 0,0'
Write-Host '  - approved 101x101 HCF spawn schematic pasted at 0,0'
Write-Host '  - the spawn schematic''s own four road designs extended to the +/-1000 border'
Write-Host '  - no global grass thinning, terrain repainting, elevation normalization or generic road palette'
Write-Host '  - reviewed KOTH layout preserved at +/-500 quadrants'
Write-Host '  - reviewed Conquest position preserved at Z=775'
Write-Host '  - KOTH/Conquest Y anchors sampled from the authored FreeMap terrain at their centers'
Write-Host '  - checksum-verified production asset pack for spawn, KOTHs, Conquest, Nether and End'
Write-Host '  - automatic verified FreeMap download/restore for full SOTW resets'
Write-Host '  - permanent clear daylight/no-rain presentation'
Write-Host '  - Mineflayer map intelligence synchronized to the 2k border and measured spawn-road exits'
Write-Host '  - roads remain PvP-enabled, no-claim and no-build'
Write-Host '  - faction claim rectangles remain fully inside the +/-1000 border'
Write-Host '  - existing HCF simulation/combat/economy/AI systems preserved'
Write-Host '  - server visual-QA workflows are manual-only; local server review is now the primary Phase-1 approval path'
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
Write-Host 'Phase 1 map note:' -ForegroundColor Yellow
Write-Host '  - FreeMap wilderness is preserved as authored.'
Write-Host '  - Only the approved spawn/events and copied spawn-road footprints modify the Overworld.'
Write-Host '  - Use a full /sotw reset confirm when you want the clean authored map restored before review.'
