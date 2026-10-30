#!/usr/bin/env bash
# Build a debug APK with your locally installed Android SDK and Gradle.
# No GitHub Actions or paid services are used.
set -euo pipefail
ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
if [[ ! -f "$SDK/platforms/android-35/android.jar" ]]; then
  printf '%s\n' 'Android SDK Platform 35 is required but was not found.' "Checked: $SDK" >&2
  exit 2
fi
if [[ -x "$ROOT/android/gradlew" ]]; then
  RUNNER=("$ROOT/android/gradlew")
elif command -v gradle >/dev/null 2>&1; then
  RUNNER=(gradle)
else
  printf '%s\n' 'Gradle 8.9+ is required. Install Android Studio or Gradle.' >&2
  exit 2
fi
cd "$ROOT/android"
ANDROID_HOME="$SDK" "${RUNNER[@]}" --no-daemon :app:assembleDebug
APK="$ROOT/android/app/build/outputs/apk/debug/app-debug.apk"
test -s "$APK"
printf 'APK created: %s\n' "$APK"
