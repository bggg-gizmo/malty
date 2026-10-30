# Malty 0.9.2 — build directly from public source

**Source root:** `app/CanceledAgent-Android-IndependentProject-v0.9.2/`

The Android application is native Java with a device-local HTTP/WebSocket/SQLite
service. It does not require a separately started Python backend. The separate
`backend/` tree supports service compatibility and offline regression testing.

## Linux toolchain requirements

- OpenJDK **17** (JDK, including `javac`, `jar`, and `keytool`).
- Android SDK Platform **36**, including `android.jar`.
- Android Build Tools with working `aapt2`, `zipalign`, D8 and `apksigner`.
- `bash`, `find`, `zip`, and Python 3 for the backend test suite.

The preserved `scripts/build-direct.sh` expects a specific layout under the
`TOOLCHAIN` directory. This is a direct-build historical contract, not a Gradle
wrapper; do not change its path semantics without review:

```text
$TOOLCHAIN/
├── jdk-17.0.20.1+1/
├── build-tools/android-16/
│   ├── aapt2
│   ├── zipalign
│   ├── lib/d8.jar
│   └── lib/apksigner.jar
└── platform/android-36/android.jar
```

If your local JDK or build-tools directory has another name, reproduce the
expected directory names with local symlinks. No proprietary signing key is
published or required to test compilation.

```sh
git clone https://github.com/bggg-gizmo/malty.git
cd malty/app/CanceledAgent-Android-IndependentProject-v0.9.2
TOOLCHAIN="$HOME/android-malty-toolchain" bash scripts/build-direct.sh
```

The generated APKs are `out/CanceledAgent-unsigned.apk` and
`out/CanceledAgent-signed.apk`.

**Important:** the direct-build script will generate a local development signing
keystore with a test password if none exists in `out/`. This is a development
fixture, **not** the existing official Malty v0.9.2 signing certificate. Do not
publish this test keystore, put it in Git, or assume its output can update the
original signed public APK in place. Preserve user data when changing signing
identities. The `.gitignore` excludes signing keys and build outputs.

## Tests

```sh
cd backend && PYTHONPATH=. python3 -m pytest -q
cd ..
node scripts/test_recovered_solver.js
python3 scripts/audit_integrations.py
python3 scripts/test_embedded_contract.py
bash scripts/test_java_wire.sh
bash scripts/test_java_race.sh
python3 scripts/test_character_network.py
python3 scripts/test_parity_additions.py
```

The historical workbench recorded 39 backend, 92 solver, 9 Java WIRE and 11
Java race-document passing cases. Run tests yourself on your platform. The
project does **not** claim successful physical-device or authenticated
external-provider testing unless independently performed and documented.

## Code map

- `android/app/src/main/java/org/canceledagent/app/`: native Android activities,
  embedded service, credentials, Moltbook, Studio, automation and operations.
- `android/app/src/main/assets/`: bundled JS utilities and Canvas Editor.
- `android/app/src/main/res/`: original user-facing Android launcher resources.
- `backend/services/` and `backend/tests/`: independent Python compatibility
  services and tests. Not required as an external Android runtime process.
- `source-reference/`: preserved generator and related original modules.
- `upstream-reference/`: recovered plugin source for comparison and maintenance.
- `scripts/`: build, recovery and parity audit utilities.

## Privacy and release safety

Never commit private keys, device token stores, personal databases, temporary
credential files, unreviewed internal coordination records, or user conversation
archives. Official binary releases are signed separately by maintainers.
