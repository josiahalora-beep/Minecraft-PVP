param(
  [Parameter(Mandatory=$true)][string]$CoordinatorToken,
  [string]$CoordinatorUrl = "http://127.0.0.1:8770",
  [string]$MinecraftHost = "127.0.0.1",
  [int]$Bodies = 10,
  [string]$NodeId = "home-pc",
  [int]$Priority = 10
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$bots = Join-Path $root 'bots'

$env:WORKER_COORDINATOR_URL = $CoordinatorUrl
$env:WORKER_COORDINATOR_TOKEN = $CoordinatorToken
$env:WORKER_NODE_ID = $NodeId
$env:WORKER_NODE_PRIORITY = [string]$Priority
$env:WORKER_MAX = [string]$Bodies
$env:MC_HOST = $MinecraftHost
$env:MC_PORT = '25565'
$env:HCF_AI_LOCAL = '0'

Write-Host "Starting local worker node '$NodeId' with capacity $Bodies" -ForegroundColor Cyan
Write-Host "Minecraft: $MinecraftHost`:25565" -ForegroundColor DarkGray
Write-Host "Coordinator: $CoordinatorUrl" -ForegroundColor DarkGray

Push-Location $bots
try {
  npm install --no-audit --no-fund
  if ($LASTEXITCODE -ne 0) { throw 'npm install failed.' }
  npm run workers
} finally {
  Pop-Location
}
