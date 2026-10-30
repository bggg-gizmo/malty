#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${1:-$ROOT/plugin-upstream-source}"
mkdir -p "$OUT"
GENERATORS=(
  ai-helper-blueprint-plugin
  canceled-codebase-generator-plugin
  canceled-ai-helper-thinking-plugin
  donation-link-2-ad
  canceled-touchscreen-api-plugin
  server-plugin
  ai-text-plugin
)
RECOVER="$ROOT/scripts/recover-perchance-plugin.py"
for generator in "${GENERATORS[@]}"; do
  echo "=== Public plugin source: $generator ==="
  PERCHANCE_GENERATOR="$generator" python3 "$RECOVER" --out "$OUT/$generator" || {
    printf '%s\n' "Failed: $generator. Audit all failures before asserting parity." >&2
    exit 1
  }
done
printf 'Plugin source archives were saved to: %s\n' "$OUT"
