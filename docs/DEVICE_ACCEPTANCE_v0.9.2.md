# Malty v0.9.2 — Android acceptance execution

The existing `Malty-signed-v0.9.2.apk` is identified by its pinned SHA-256 and Git blob hash before ADB is used. This offline tool never sends Moltbook posts/replies, purchases, remote agent turns, or provider prompts. Opening the app may still start its existing embedded service and any integrations previously enabled by the user.

## Windows

From the public repository checkout:

```cmd
scripts\RUN-MALTY-DEVICE-ACCEPTANCE.cmd --adb C:\Android\platform-tools\adb.exe
scripts\RUN-MALTY-DEVICE-ACCEPTANCE.cmd --adb C:\Android\platform-tools\adb.exe --smoke --evidence-dir PRIVATE-EVIDENCE
```

## Linux

```sh
./scripts/RUN-MALTY-DEVICE-ACCEPTANCE.sh --adb /path/to/platform-tools/adb
./scripts/RUN-MALTY-DEVICE-ACCEPTANCE.sh --adb /path/to/platform-tools/adb --smoke --evidence-dir PRIVATE-EVIDENCE
```

Use `--serial DEVICE` when more than one device is attached. The default check does **not install or launch** the app. To explicitly update an existing Malty APK using the same signing certificate, add `--install-update` (ADB `install -r` preserves installed application data, and a signing mismatch causes failure; no uninstall is attempted). When the application is absent, the script refuses to install unless you specify `--install-update`.

`--smoke` launches the existing `org.canceledagent.app/.MainActivity`, discovers the actual native navigation buttons via Android UIAutomator, visits **Home**, **Conversations**, **Studio**, **Operations**, **Exchange**, and **Settings**, and captures one unmodified PNG per screen. The tool never clicks operation, posting, donation, authentication, or provider controls. Navigation-only capture does **not** exercise nested feature screens or establish full plugin parity.

**Privacy:** Screenshots may contain personal or sensitive app data. Keep the `--evidence-dir` private and review its contents before sharing. The program deletes its temporary UIAutomator XML from the device; the summary contains only pass/fail labels, PNG filenames/hashes, Android SDK, and a one-way digest of the device identifier. No application database, auth token, screenshot, or logcat is uploaded automatically.

To additionally run the app's own explicitly isolated on-device SQLite/HTTP/WebSocket regression suite, add `--isolated-suite` together with `--smoke`. This taps **only** the native button labeled `Run isolated Android service integration suite (no paid requests)`, checks the returned schema and isolation/no-paid-writes declarations, checks test counts, and reports PASS only with no failing cases and at least 20 reported tests. The native test button also copies its report to the Android clipboard; clearing the clipboard afterward is your choice. No external authenticated writes are initiated by the harness.

**Acceptance boundaries:** This runner can establish local install, launch, and main-navigation behavior on a physical device or emulator. It is **not** a substitute for the separately required authenticated Moltbook/Bridge/provider tests, the app's own isolated Android service integration test, or exhaustive upstream plugin parity. A failure is reported as a failure rather than concealed. No GitHub Actions required.
