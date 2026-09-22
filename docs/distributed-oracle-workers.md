# Distributed HCF workers: home Minecraft server + Oracle bot nodes

This topology keeps Spigot/EraCore authoritative on the Windows gaming PC while
Mineflayer CPU work is shared by the home PC and one or more remote Linux nodes.

## Topology

- Home Windows PC
  - Spigot 1.8.8 + EraCore
  - `worker-coordinator.js`
  - localhost LLM bridge on TCP 8765
  - optional local Mineflayer worker node
- Oracle Linux VM(s)
  - Mineflayer worker nodes only
- Tailscale
  - private network between home and Oracle
  - Minecraft TCP 25565 and coordinator TCP 8770 stay private

The coordinator owns HOT-body leases. A logical identity is leased to at most one
healthy node. Nodes heartbeat every worker reconciliation cycle. A missing node is
expired after about 30 seconds and its identities become eligible for reassignment.

## Default population and capacity

- persistent logical population: 150
- global HOT ceiling: 40
- offline HOT target: 24
- default node capacity: 12
- combat remains higher priority than ambient bodies
- EraCore's global worker budget contracts automatically when p95 MSPT rises
- coordinator growth is ramped instead of connecting the entire cluster at once

## Home Windows setup

Install Tailscale and sign in to the same tailnet that the Oracle VM will use.
Get the home Tailscale IPv4 address:

```powershell
tailscale ip -4
```

Minecraft's `server-ip` must be blank so Spigot can accept the private Tailscale
connection. The repository default now uses:

```properties
server-ip=
server-port=25565
```

Restrict inbound worker traffic to Tailscale addresses with Windows Firewall:

```powershell
New-NetFirewallRule -DisplayName "Era HCF Minecraft - Tailscale" `
  -Direction Inbound -Action Allow -Protocol TCP -LocalPort 25565 `
  -RemoteAddress 100.64.0.0/10

New-NetFirewallRule -DisplayName "Era HCF Coordinator - Tailscale" `
  -Direction Inbound -Action Allow -Protocol TCP -LocalPort 8770 `
  -RemoteAddress 100.64.0.0/10
```

Generate one coordinator secret and keep it private:

```powershell
$TOKEN = ([guid]::NewGuid().ToString("N") + [guid]::NewGuid().ToString("N"))
$TOKEN
```

Start Spigot first.

Open a second PowerShell window in the repository root and start the control plane:

```powershell
$TOKEN = "PASTE_THE_SAME_SECRET"
.\Start-HCF-ControlPlane.ps1 -CoordinatorToken $TOKEN
```

If `OPENAI_API_KEY` is already configured in the environment, the LLM social
bridge is enabled automatically. It can also be supplied explicitly:

```powershell
.\Start-HCF-ControlPlane.ps1 `
  -CoordinatorToken $TOKEN `
  -OpenAIKey $env:OPENAI_API_KEY
```

Open a third PowerShell window for the home worker contribution:

```powershell
$TOKEN = "PASTE_THE_SAME_SECRET"
.\Start-HCF-LocalWorker.ps1 `
  -CoordinatorToken $TOKEN `
  -Bodies 10 `
  -NodeId "home-pc"
```

The local worker connects to Minecraft over loopback, so it does not waste a
Tailscale hop.

## Oracle worker setup

Create an Ubuntu x86 VM for the first benchmark. SSH into it and install Tailscale:

```bash
curl -fsSL https://tailscale.com/install.sh | sh
sudo tailscale up
```

Use the same tailnet as the Windows machine. Verify the home PC is reachable:

```bash
tailscale ping HOME_MACHINE_NAME
```

Clone/update this repository, then:

```bash
cd Minecraft-PVP/bots
bash setup-linux-worker.sh
```

Export the home PC's Tailscale IPv4 address and the same coordinator secret:

```bash
export HOME_HCF_IP="100.x.y.z"
export WORKER_COORDINATOR_URL="http://$HOME_HCF_IP:8770"
export WORKER_COORDINATOR_TOKEN="PASTE_THE_SAME_SECRET"
export MC_HOST="$HOME_HCF_IP"
export MC_PORT="25565"
export WORKER_NODE_ID="oracle-1"
export WORKER_MAX="16"
export WORKER_NODE_PRIORITY="0"

bash start-worker-linux.sh
```

Start at 16 remote bodies. Increase to 20, 24, and beyond only after observing
server MSPT. The cluster's global ceiling remains 40 unless the EraCore config is
changed.

## Coordinator health

From Windows:

```powershell
$headers = @{ Authorization = "Bearer $TOKEN" }
Invoke-RestMethod -Headers $headers http://127.0.0.1:8770/v1/status |
  ConvertTo-Json -Depth 8
```

The status shows each node's capacity, live bodies, Node CPU/RSS, current
server-advertised budget, and its leased identities.

## In-game monitoring

Use:

```text
/simprobe
/simworker status
/simcombat director
```

Watch p95 MSPT while increasing remote capacity. Remote CPU removes Mineflayer
pathfinding/AI cost from the Windows process, but every physical bot still costs
Spigot entity/network/tick work. Scale HOT bodies according to the Minecraft
server's p95 MSPT, not according to Oracle CPU alone.

## Failure behavior

- Oracle worker dies: its leases expire after the coordinator node TTL, then
  another healthy node may receive them.
- Coordinator temporarily goes down: existing workers keep their current bodies
  rather than falling back to unsafe independent leasing.
- Minecraft server goes down: Mineflayer sessions disconnect normally.
- Duplicate healthy-node leasing is prevented by the coordinator lease table.

## LLM chat

The LLM service belongs on the home control plane because EraCore calls
`http://127.0.0.1:8765/reply`. Oracle workers do not need an OpenAI key.

The LLM proposes text, relationship deltas, memories, and a bounded social action.
EraCore remains authoritative and validates faction/economy/game-state actions.
