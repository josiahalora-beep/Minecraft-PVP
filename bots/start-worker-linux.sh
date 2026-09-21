#!/usr/bin/env bash
set -euo pipefail

: "${WORKER_COORDINATOR_URL:?Set WORKER_COORDINATOR_URL, e.g. http://100.x.y.z:8770}"
: "${WORKER_COORDINATOR_TOKEN:?Set WORKER_COORDINATOR_TOKEN}"
: "${MC_HOST:?Set MC_HOST to the home server Tailscale IP}"

export MC_PORT="${MC_PORT:-25565}"
export WORKER_NODE_ID="${WORKER_NODE_ID:-$(hostname)}"
export WORKER_MAX="${WORKER_MAX:-20}"
export WORKER_NODE_PRIORITY="${WORKER_NODE_PRIORITY:-0}"
export HCF_AI_LOCAL=0

major="$(node -p \"process.versions.node.split('.')[0]\")"
if [ "$major" -lt 18 ]; then
  echo "Node.js 18+ is required; found $(node -v)." >&2
  exit 1
fi

echo "[oracle-worker] node=$WORKER_NODE_ID capacity=$WORKER_MAX"
echo "[oracle-worker] coordinator=$WORKER_COORDINATOR_URL"
echo "[oracle-worker] minecraft=$MC_HOST:$MC_PORT"
exec npm run workers
