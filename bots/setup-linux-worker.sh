#!/usr/bin/env bash
set -euo pipefail

if ! command -v node >/dev/null 2>&1 || ! command -v npm >/dev/null 2>&1; then
  sudo apt-get update
  sudo apt-get install -y nodejs npm
fi

major="$(node -p \"process.versions.node.split('.')[0]\")"
if [ "$major" -lt 18 ]; then
  echo "The distro Node.js is too old ($(node -v)). Install Node.js 18+ before continuing." >&2
  exit 1
fi

cd "$(dirname "$0")"
npm install --no-audit --no-fund
echo "Mineflayer worker dependencies installed. Node $(node -v), npm $(npm -v)."
