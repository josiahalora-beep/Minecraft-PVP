param(
  [Parameter(Mandatory=$true)][string]$SpigotJar,
  [string]$JavaHome = $env:JAVA_HOME
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$plugin = Join-Path $root 'plugins-src\EraCore'
$classes = Join-Path $plugin 'build\classes'
New-Item -ItemType Directory -Force -Path $classes | Out-Null

if ($JavaHome) {
  $javac = Join-Path $JavaHome 'bin\javac.exe'
  $jarTool = Join-Path $JavaHome 'bin\jar.exe'
} else {
  $javac = 'javac'
  $jarTool = 'jar'
}

$sourceDir = Join-Path $plugin 'src\main\java\dev\jorel\eracore'
$sources = Get-ChildItem $sourceDir -Filter '*.java' | ForEach-Object { $_.FullName }
if (!$sources -or $sources.Count -eq 0) { throw 'No EraCore Java sources found.' }
& $javac -encoding UTF-8 -source 8 -target 8 -cp $SpigotJar -d $classes $sources
if ($LASTEXITCODE -ne 0) { throw 'EraCore javac failed.' }
Copy-Item (Join-Path $plugin 'src\main\resources\plugin.yml') $classes -Force
Copy-Item (Join-Path $plugin 'src\main\resources\config.yml') $classes -Force
$out = Join-Path $root 'plugins\EraCore.jar'
New-Item -ItemType Directory -Force -Path (Split-Path $out -Parent) | Out-Null
Push-Location $classes
& $jarTool cf $out *
Pop-Location
if ($LASTEXITCODE -ne 0) { throw 'EraCore jar packaging failed.' }
Write-Host "Built $out" -ForegroundColor Green
