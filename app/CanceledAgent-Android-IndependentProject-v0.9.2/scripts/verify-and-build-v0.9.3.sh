#!/usr/bin/env bash
# Malty v0.9.3 complete host regression and offline Android development build.
# No GitHub Actions, no paid CI, no production signing materials.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUTPUT="${MALTY_VERIFY_OUTPUT:-$ROOT/out/verification-v0.9.3}"
mkdir -p "$OUTPUT"
LOG="$OUTPUT/full-regression-and-build.log"
exec > >(tee "$LOG") 2>&1
printf 'Malty v0.9.3 verification started: %s\n' "$(date -u +%FT%TZ)"
for command in python3 node bash sha256sum; do
  command -v "$command" >/dev/null || { echo "Missing $command" >&2; exit 2; }
done
if [[ ! -f "$ROOT/android/app/src/main/java/org/canceledagent/app/OutboundBridge.java" || ! -f "$ROOT/backend/services/rooms_votes/app.py" ]]; then
  echo 'A complete Malty source checkout is required, never a partial or substituted project.' >&2; exit 2
fi
export JAVA_HOME="${JAVA_HOME:-${TOOLCHAIN:-/mnt/data/.canceled-toolchain}/jdk-17.0.20.1+1}"
[[ -x "$JAVA_HOME/bin/javac" ]] || { echo "Missing Java 17 at $JAVA_HOME" >&2; exit 2; }
"$JAVA_HOME/bin/java" -version
(cd "$ROOT/backend" && PYTHONPATH=. python3 -m pytest -q)
PYTHONPATH="$ROOT/scripts" python3 -m unittest discover -s "$ROOT/scripts" -p test_bridge_signer.py -v
for suite in test_embedded_contract.py test_character_network.py test_parity_additions.py test_race_integration_contract.py test_v091_contract.py test_v093_identity.py; do
  python3 "$ROOT/scripts/$suite" -v
done
node "$ROOT/scripts/test_recovered_solver.js"
bash "$ROOT/scripts/test_java_wire.sh"
bash "$ROOT/scripts/test_java_race.sh"
bash "$ROOT/scripts/test_bridge_security.sh"
python3 "$ROOT/scripts/audit_integrations.py"
TOOLCHAIN="${TOOLCHAIN:-/mnt/data/.canceled-toolchain}" bash "$ROOT/scripts/build-direct.sh"
SIGNED="$ROOT/out/CanceledAgent-signed.apk"
UNSIGNED="$ROOT/out/CanceledAgent-unsigned.apk"
[[ -s "$SIGNED" && -s "$UNSIGNED" ]] || { echo 'Missing output APK' >&2; exit 2; }
BT="${TOOLCHAIN:-/mnt/data/.canceled-toolchain}/build-tools/android-16"
"$JAVA_HOME/bin/java" -jar "$BT/lib/apksigner.jar" verify --verbose --print-certs "$SIGNED"
"$BT/aapt2" dump badging "$SIGNED" > "$OUTPUT/apk-badging.txt"
python3 - "$OUTPUT/apk-badging.txt" <<'PY'
import pathlib,sys
badge=pathlib.Path(sys.argv[1]).read_text()
line=next((s for s in badge.splitlines() if s.startswith('package:')),'')
expected=("name='org.canceledagent.app'","versionCode='12'","versionName='0.9.3'")
if any(value not in line for value in expected):
    raise SystemExit('Unexpected package/version: '+line)
print('Verified:',line)
PY
cp "$SIGNED" "$ROOT/out/Malty-v0.9.3-development-signed.apk"
cp "$UNSIGNED" "$ROOT/out/Malty-v0.9.3-unsigned.apk"
(cd "$ROOT/out" && sha256sum Malty-v0.9.3-development-signed.apk Malty-v0.9.3-unsigned.apk > "$OUTPUT/SHA256SUMS-v0.9.3.txt")
python3 - "$OUTPUT" <<'PY'
import json,pathlib,datetime,sys
p=pathlib.Path(sys.argv[1])
report={'version':'0.9.3','versionCode':12,'status':'local-development-build-verified',
        'production_signed':False,'on_device_tested':False,
        'timestamp_utc':datetime.datetime.now(datetime.timezone.utc).isoformat()}
(p/'verification.json').write_text(json.dumps(report,indent=2)+'\n')
PY
printf 'Malty v0.9.3 full host regressions and development APK verification PASSED.\nLog: %s\n' "$LOG"
