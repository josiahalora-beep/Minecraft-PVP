param(
  [Parameter(Mandatory=$true)][string]$CoordinatorToken,
  [string]$OpenAIKey = "",
  [string]$BindAddress = "0.0.0.0",
  [int]$Port = 8770
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$bots = Join-Path $root 'bots'
if (!(Test-Path (Join-Path $bots 'package.json'))) { throw "bots\package.json not found." }

$env:WORKER_COORDINATOR_TOKEN = $CoordinatorToken
$env:WORKER_COORDINATOR_BIND = $BindAddress
$env:WORKER_COORDINATOR_PORT = [string]$Port
$env:HCF_AI_BRIDGE_ENABLED = '1'
if (![string]::IsNullOrWhiteSpace($OpenAIKey)) { $env:OPENAI_API_KEY = $OpenAIKey }

Write-Host "Starting authoritative worker coordinator on $BindAddress`:$Port" -ForegroundColor Cyan
Write-Host "The Minecraft server should already be running on this computer." -ForegroundColor DarkGray
if ([string]::IsNullOrWhiteSpace($env:OPENAI_API_KEY)) {
  Write-Host "OPENAI_API_KEY is not set; semantic chat will use EraCore's deterministic fallback." -ForegroundColor Yellow
} else {
  Write-Host "LLM social chat bridge enabled on localhost:8765." -ForegroundColor Green
}

Push-Location $bots
try {
  npm install --no-audit --no-fund
  if ($LASTEXITCODE -ne 0) { throw 'npm install failed.' }
  npm run coordinator
} finally {
  Pop-Location
}
