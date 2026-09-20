param(
  [string]$JavaHome = "",
  [switch]$InstallJava8
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$server = Join-Path $root 'server'
$build = Join-Path $server 'buildtools'
New-Item -ItemType Directory -Force -Path $build | Out-Null

function Test-Java8Home([string]$Home) {
  if ([string]::IsNullOrWhiteSpace($Home)) { return $false }
  $java = Join-Path $Home 'bin\java.exe'
  $javac = Join-Path $Home 'bin\javac.exe'
  if (!(Test-Path $java) -or !(Test-Path $javac)) { return $false }
  $versionText = (& $java -version 2>&1 | Out-String)
  return ($versionText -match 'version "1\.8\.' -or $versionText -match 'openjdk version "1\.8\.')
}

function Find-Java8Home {
  param([string]$Preferred)

  $candidates = New-Object System.Collections.Generic.List[string]
  if ($Preferred) { $candidates.Add($Preferred) }
  if ($env:JAVA8_HOME) { $candidates.Add($env:JAVA8_HOME) }
  if ($env:JAVA_HOME) { $candidates.Add($env:JAVA_HOME) }

  $patterns = @(
    "$env:ProgramFiles\Eclipse Adoptium\jdk-8*",
    "$env:ProgramFiles\Java\jdk1.8*",
    "$env:ProgramFiles\AdoptOpenJDK\jdk-8*",
    "$env:ProgramFiles\Microsoft\jdk-8*"
  )

  $pf86 = [Environment]::GetEnvironmentVariable('ProgramFiles(x86)')
  if ($pf86) {
    $patterns += @(
      "$pf86\Eclipse Adoptium\jdk-8*",
      "$pf86\Java\jdk1.8*",
      "$pf86\AdoptOpenJDK\jdk-8*"
    )
  }

  foreach ($pattern in $patterns) {
    Get-ChildItem -Path $pattern -Directory -ErrorAction SilentlyContinue |
      Sort-Object LastWriteTime -Descending |
      ForEach-Object { $candidates.Add($_.FullName) }
  }

  foreach ($candidate in ($candidates | Select-Object -Unique)) {
    if (Test-Java8Home $candidate) { return $candidate }
  }
  return $null
}

$JavaHome = Find-Java8Home -Preferred $JavaHome

if (!$JavaHome -and $InstallJava8) {
  $winget = Get-Command winget -ErrorAction SilentlyContinue
  if (!$winget) { throw 'winget is not available. Install a Java 8 JDK manually (Temurin 8 recommended), then rerun setup.' }
  Write-Host 'Java 8 JDK not found. Installing Eclipse Temurin 8 JDK side-by-side...' -ForegroundColor Yellow
  & winget install --id EclipseAdoptium.Temurin.8.JDK -e --accept-package-agreements --accept-source-agreements
  if ($LASTEXITCODE -ne 0) { throw 'Java 8 installation failed.' }
  $JavaHome = Find-Java8Home -Preferred ""
}

if (!$JavaHome) {
  Write-Host ''
  Write-Host 'Java 8 JDK was not found. Java 26 can stay installed; Minecraft 1.8.8 needs Java 8 only for this project.' -ForegroundColor Yellow
  Write-Host 'Fastest option:' -ForegroundColor Yellow
  Write-Host '  winget install --id EclipseAdoptium.Temurin.8.JDK -e --accept-package-agreements --accept-source-agreements' -ForegroundColor White
  Write-Host 'Then rerun:' -ForegroundColor Yellow
  Write-Host '  .\setup-windows.ps1' -ForegroundColor White
  Write-Host 'Or let this script install it automatically:' -ForegroundColor Yellow
  Write-Host '  .\setup-windows.ps1 -InstallJava8' -ForegroundColor White
  throw 'Java 8 JDK required.'
}

$java = Join-Path $JavaHome 'bin\java.exe'
$javac = Join-Path $JavaHome 'bin\javac.exe'

Write-Host 'Using Java 8 JDK:' -ForegroundColor Green
Write-Host "  $JavaHome"
& $java -version
& $javac -version
if ($LASTEXITCODE -ne 0) { throw 'Selected Java 8 JDK is incomplete.' }

Set-Content -Path (Join-Path $server 'java8-home.txt') -Value $JavaHome -Encoding ASCII

$buildTools = Join-Path $build 'BuildTools.jar'
if (!(Test-Path $buildTools)) {
  Write-Host 'Downloading official Spigot BuildTools...' -ForegroundColor Cyan
  Invoke-WebRequest -UseBasicParsing 'https://hub.spigotmc.org/jenkins/job/BuildTools/lastSuccessfulBuild/artifact/target/BuildTools.jar' -OutFile $buildTools
}

Push-Location $build
Write-Host 'Building Spigot 1.8.8 locally with Java 8. This is the slowest one-time setup step.' -ForegroundColor Cyan
& $java -jar $buildTools --rev 1.8.8
if ($LASTEXITCODE -ne 0) { Pop-Location; throw 'BuildTools failed.' }
$built = Get-ChildItem -Path $build -Filter 'spigot-1.8.8*.jar' | Sort-Object LastWriteTime -Descending | Select-Object -First 1
Pop-Location
if (!$built) { throw 'BuildTools finished but spigot-1.8.8 jar was not found.' }
Copy-Item $built.FullName (Join-Path $server 'spigot-1.8.8.jar') -Force

Write-Host 'Compiling EraCore...' -ForegroundColor Cyan
& (Join-Path $server 'build-plugin.ps1') -SpigotJar (Join-Path $server 'spigot-1.8.8.jar') -JavaHome $JavaHome

Write-Host 'Installing Mineflayer dependencies...' -ForegroundColor Cyan
Push-Location (Join-Path $root 'bots')
npm install
if ($LASTEXITCODE -ne 0) { Pop-Location; throw 'npm install failed. Install Node.js and retry.' }
Pop-Location

Write-Host ''
Write-Host 'Minecraft EULA action required:' -ForegroundColor Yellow
Write-Host 'Open server\eula.txt, read the EULA notice, and change eula=false to eula=true if you accept it.' -ForegroundColor Yellow
Write-Host ''
Write-Host 'Setup complete. start-server.bat will now use the saved Java 8 JDK automatically.' -ForegroundColor Green
