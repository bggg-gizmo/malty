#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TOOLCHAIN="${TOOLCHAIN:-/mnt/data/.canceled-toolchain}"
JAVA_HOME="${JAVA_HOME:-$TOOLCHAIN/jdk-17.0.20.1+1}"
BT="$TOOLCHAIN/build-tools/android-16"; PLATFORM="$TOOLCHAIN/platform/android-36/android.jar"
B="$ROOT/direct-build"; SRC="$ROOT/android/app/src/main"
mkdir -p "$B/classes" "$B/dex" "$ROOT/out"
"$JAVA_HOME/bin/javac" -encoding UTF-8 -source 8 -target 8 -Xlint:none -cp "$PLATFORM" -d "$B/classes" $(find "$SRC/java" -name '*.java' -print)
"$JAVA_HOME/bin/jar" cf "$B/classes.jar" -C "$B/classes" .
"$JAVA_HOME/bin/java" -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 --min-api 26 --lib "$PLATFORM" --output "$B/dex" "$B/classes.jar"
mkdir -p "$B/resources"
"$BT/aapt2" compile --dir "$SRC/res" -o "$B/resources"
RESOURCE_ARGS=()
while IFS= read -r -d '' resource; do RESOURCE_ARGS+=(-R "$resource"); done < <(find "$B/resources" -name '*.flat' -print0)
"$BT/aapt2" link -o "$B/base.apk" --manifest "$SRC/AndroidManifest.xml" -I "$PLATFORM" "${RESOURCE_ARGS[@]}" --min-sdk-version 26 --target-sdk-version 28 --version-code 12 --version-name 0.9.3
cp "$B/base.apk" "$B/assembled.apk"
(cd "$B/dex" && zip -q -u "$B/assembled.apk" classes.dex)
if [[ -d "$SRC/assets" ]]; then (cd "$SRC" && zip -q -r -u "$B/assembled.apk" assets);fi
"$BT/zipalign" -f -p 4 "$B/assembled.apk" "$ROOT/out/CanceledAgent-unsigned.apk"
KEY="$ROOT/out/canceled-agent-dev.keystore"
if [[ ! -f "$KEY" ]]; then "$JAVA_HOME/bin/keytool" -genkeypair -noprompt -keystore "$KEY" -storepass android -keypass android -alias canceled-agent-dev -keyalg RSA -keysize 3072 -validity 3650 -dname "CN=Canceled Agent Development, O=Independent Build" >/dev/null 2>&1; fi
"$JAVA_HOME/bin/java" -jar "$BT/lib/apksigner.jar" sign --ks "$KEY" --ks-pass pass:android --key-pass pass:android --out "$ROOT/out/CanceledAgent-signed.apk" "$ROOT/out/CanceledAgent-unsigned.apk"
"$JAVA_HOME/bin/java" -jar "$BT/lib/apksigner.jar" verify --verbose "$ROOT/out/CanceledAgent-signed.apk"
ls -lh "$ROOT/out"/*.apk
