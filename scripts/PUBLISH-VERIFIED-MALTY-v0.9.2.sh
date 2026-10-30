#!/usr/bin/env sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
exec python3 "$ROOT/publish_v092_prerelease.py" "$@"
