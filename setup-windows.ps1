param(
  [string]$JavaHome = $env:JAVA_HOME
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$server = Join-Path $root 'server'
$build = Join-Path $server 'buildtools'
New-Item -ItemType Directory -Force -Path $build | Out-Null

if ($JavaHome) {
  $java = Join-Path $JavaHome 'bin\java.exe'
  $javac = Join-Path $JavaHome 'bin\javac.exe'
} else {
  $java = 'java'
  $javac = 'javac'
}

Write-Host 'Checking Java/JDK...' -ForegroundColor Cyan
& $java -version
& $javac -version
if ($LASTEXITCODE -ne 0) { throw 'A JDK is required. For this 1.8.8 build, use a Java 8 JDK and set JAVA_HOME.' }

$buildTools = Join-Path $build 'BuildTools.jar'
if (!(Test-Path $buildTools)) {
  Write-Host 'Downloading official Spigot BuildTools...' -ForegroundColor Cyan
  Invoke-WebRequest -UseBasicParsing 'https://hub.spigotmc.org/jenkins/job/BuildTools/lastSuccessfulBuild/artifact/target/BuildTools.jar' -OutFile $buildTools
}

Push-Location $build
Write-Host 'Building Spigot 1.8.8 locally. This is the slowest one-time setup step.' -ForegroundColor Cyan
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
Write-Host 'Setup complete. Next: run server\start-server.bat.' -ForegroundColor Green
