#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BUILD="$(mktemp -d)"; trap 'rm -rf "$BUILD"' EXIT
javac -d "$BUILD" "$ROOT/android/app/src/main/java/org/canceledagent/app/RaceDocument.java" "$ROOT/scripts/RaceDocumentContractTest.java"
java -cp "$BUILD" org.canceledagent.app.RaceDocumentContractTest
