#!/usr/bin/env bash
# Offline JDK17 tests, no GitHub Actions and no network.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/android/app/src/main/java/org/canceledagent/app"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
JAVAC=javac
JAVA=java
KEYTOOL=keytool
if [[ -n "$JAVA_HOME" ]]; then JAVAC="$JAVA_HOME/bin/javac"; JAVA="$JAVA_HOME/bin/java"; KEYTOOL="$JAVA_HOME/bin/keytool"; fi
mkdir -p "$TMP/classes"
"$JAVAC" -d "$TMP/classes" "$SRC/BridgeRequestSecurity.java" "$SRC/BridgeTransportSecurity.java" \
    "$ROOT/scripts/BridgeRequestSecurityTest.java" "$ROOT/scripts/BridgeTransportSecurityTest.java"
"$JAVA" -cp "$TMP/classes" BridgeRequestSecurityTest
"$KEYTOOL" -genkeypair -noprompt -alias malty-test -keyalg RSA -keysize 2048 -validity 2 \
    -storetype PKCS12 -keystore "$TMP/server.p12" -storepass changeit -keypass changeit \
    -dname "CN=localhost" -ext "SAN=dns:localhost" >/dev/null 2>&1
"$KEYTOOL" -exportcert -alias malty-test -keystore "$TMP/server.p12" -storepass changeit \
    -rfc -file "$TMP/server.pem" >/dev/null 2>&1
"$KEYTOOL" -importcert -noprompt -alias malty-test -file "$TMP/server.pem" \
    -storetype PKCS12 -keystore "$TMP/trust.p12" -storepass changeit >/dev/null 2>&1
"$JAVA" -cp "$TMP/classes" BridgeTransportSecurityTest "$TMP/server.p12" "$TMP/trust.p12"
