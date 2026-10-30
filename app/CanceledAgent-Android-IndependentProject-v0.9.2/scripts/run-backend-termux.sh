#!/usr/bin/env bash
# Independent localhost server for Android + Termux. No Gradle or Perchance.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT/backend"
mkdir -p "$ROOT/backend/data"
export AGENT_DB_PATH="${AGENT_DB_PATH:-$ROOT/backend/data/canceled-agent.sqlite3}"
# Bind locally: credentials and write controls must never be exposed publicly.
if ! python3 -c 'import fastapi,httpx,uvicorn' >/dev/null 2>&1; then
  python3 -m pip install -r requirements.txt
fi
printf 'Independent Canceled Agent server: http://127.0.0.1:8000\nDatabase: %s\n' "$AGENT_DB_PATH"
exec python3 -m uvicorn services.rooms_votes.app:app --host 127.0.0.1 --port 8000 --workers 1
