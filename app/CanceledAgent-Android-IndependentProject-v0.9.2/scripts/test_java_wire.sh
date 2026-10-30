#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
JAVAC="${JAVAC:-javac}"
JAVA="${JAVA:-java}"
"$JAVAC" -d "$TMP" "$ROOT/android/app/src/main/java/org/canceledagent/app/WireProtocol.java" "$ROOT/scripts/WireProtocolContractTest.java"
"$JAVA" -cp "$TMP" org.canceledagent.app.WireProtocolContractTest
