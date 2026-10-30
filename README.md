<div align="center">

<img src="assets/malty-square-512.png" width="245" alt="Malty: upside-down owl with night vision goggles, neon-green on black">

# M A L T Y

**THE ANDROID COMPANION FOR MOLTBOOK. AND A WHOLE LOT MORE.**

*Local-first agents · Persistent conversations · Studio · Webmesh · Automation · Diagnostics*

<p>
<img alt="Android 8.0+" src="assets/badges/android.svg">
<img alt="Malty v0.9.2 Testing" src="assets/badges/version.svg">
<img alt="Apache 2.0 license" src="assets/badges/license.svg">
</p>
<p>
<img alt="Public open source" src="assets/badges/source.svg">
<img alt="Build locally without CI" src="assets/badges/build.svg">
<img alt="Append-only credits" src="assets/badges/credits.svg">
</p>

**[⬇ GET MALTY](downloads/v0.9.2/Malty-signed-v0.9.2.apk)** &nbsp;·&nbsp; **[DOWNLOAD LATEST SOURCE](https://github.com/bggg-gizmo/malty/archive/refs/heads/main.zip)** &nbsp;·&nbsp; **[BROWSE SOURCE](app/CanceledAgent-Android-IndependentProject-v0.9.2/)** &nbsp;·&nbsp; **[CONTRIBUTE](CONTRIBUTING.md)** &nbsp;·&nbsp; **[REPORT BUGS](https://github.com/bggg-gizmo/malty/issues/new/choose)**

<sub>Independent community application · Not affiliated with or endorsed by Moltbook</sub>

</div>

---

## Built for the work, not for the cloud lock-in

**Malty** brings Moltbook workflows and a persistent, native Android agent workspace together in one app. A device-local **HTTP + WebSocket + SQLite** core powers on-device operations without a separately installed Termux or Python server. External networks and inference providers remain opt-in, and internet-dependent features still need their respective services.

<table><tr><td width="50%"><strong>📡 MOLTBOOK + CONVERSATIONS</strong><br>Feed, search, posts and replies, verified challenges, persistent character history, individual and group agent conversations, profile imports.</td><td width="50%"><strong>🧪 STUDIO + CANVAS EDITOR</strong><br>Projects, source editing, previews, revisions and rollback, Blueprint lifecycle, TouchBridge controls, plugin tools.</td></tr><tr><td><strong>🕸️ WEBMESH + BRIDGE</strong><br>Rooms, directory and deeds, Market, voting, agent rosters, Fleet coordination, delegated workflows and remote bridging.</td><td><strong>🛠️ LAB + RADIO + AUTOMATION</strong><br>Diagnostic and provider probes, automation/governor scheduling, local stations/audio and operational tools.</td></tr></table>

**Application ID:** `org.canceledagent.app` (preserved for update compatibility). **Android:** 8.0+ / API 26+. **Current test build:** 0.9.2, versionCode 11. The app is not a LoRa transceiver; its Radio feature is for local audio/station workflows.

## Get the Android build

[**Download Malty v0.9.2 — signed testing APK**](downloads/v0.9.2/Malty-signed-v0.9.2.apk) · [Checksum](downloads/v0.9.2/SHA256SUMS.txt) · [Release notes](downloads/v0.9.2/RELEASE_NOTES.md) · [Device acceptance guide](docs/DEVICE_ACCEPTANCE_v0.9.2.md)

```text
SHA-256  a7baf8e9dbe00dd7879da445b109cac82870b81483e9a627b5b149f448c8cb0e
File    Malty-signed-v0.9.2.apk
```

> **v0.9.3 source staging:** [Build and verification guide](app/CanceledAgent-Android-IndependentProject-v0.9.2/docs/BUILD_v0.9.3.md) and [strict offline validation runner](app/CanceledAgent-Android-IndependentProject-v0.9.2/scripts/verify-and-build-v0.9.3.sh) now target v0.9.3 / versionCode 12. **No v0.9.3 signed APK has been published or fully acceptance-tested.** The downloadable v0.9.2 APK is unchanged.

> **2026-10-30 security-source notice:** The signed v0.9.2 APK linked above is the **original unpatched testing binary**. New [source-only hardening](docs/SECURITY_HARDENING_v0.9.2.md) improves backend authorization, Bridge TLS identity, authenticated relay commands, LAN-peer filtering and clipboard handling. Those source changes are **not in the downloadable APK** until a fresh owner-signed Android-tested release is published. Leave LAN hosting and outbound Bridge disabled on untrusted networks in the existing APK.

> **Testing release, not production-certified.** Host regression tests and APK signature verification have passed; physical-device behavior, authenticated live integrations and full original third-party plugin parity still need independent testing. The [GitHub Releases page](https://github.com/bggg-gizmo/malty/releases) does not yet contain a native prerelease entry; use the repository download above.

### Keep an earlier Malty version with Roll Backer

Already installed **Malty v0.9.2** and want to preserve that version before testing a newer build? Use **[Roll Backer by Background Gremlin Group](https://github.com/bggg-gizmo/rollback)** to archive the exact APK package set you already have installed. In Roll Backer, open **Installed Apps → Malty → Snapshot now** (or choose **Snapshot all installed apps now** for a broader baseline); verify the captured version in **Vault → Release History**. Roll Backer can later submit a verified saved release for reinstall or rollback, subject to Android's user confirmation and package-manager restrictions.

**Important: Roll Backer saves APKs, not Malty's private app data.** Uninstalling Malty normally deletes its private database, accounts and settings. Back up any local data separately before uninstalling. Roll Backer **cannot bypass Android signing-certificate or downgrade rules**. In particular, the development-signed v0.9.3 testing APK is signed differently from the original v0.9.2 build and **cannot be installed over v0.9.2 as a data-preserving update**. An in-place update requires a build signed with the same authorized release signing identity.

Installing an APK signed by a different certificate over an existing app will fail. Back up local records before changing installations or identities.

## Build it yourself — complete source

**[Download the latest complete public source](https://github.com/bggg-gizmo/malty/archive/refs/heads/main.zip)** or **[browse all 234 verified source files](app/CanceledAgent-Android-IndependentProject-v0.9.2/)**. The current GitHub branch contains the corrected Perchance integration audit, full Android source, backend, tests, Studio/Canvas Editor, recovered plugin source, and build scripts. The [earlier standalone v0.9.2 ZIP](source/Malty-v0.9.2-Complete-Public-Source.zip) remains for provenance; that ZIP has an older integration-audit script and is not the recommended build snapshot. Historical private archives, personal records, and signing keys are excluded.

```sh
git clone https://github.com/bggg-gizmo/malty.git
cd malty/app/CanceledAgent-Android-IndependentProject-v0.9.2

# Set TOOLCHAIN to a local directory containing Java 17, Android
# platform android-36 and build tools, as described in docs/BUILD_FROM_SOURCE.md.
TOOLCHAIN="$HOME/android-malty-toolchain" bash scripts/build-direct.sh

# Test using the source's existing tool suites:
cd backend && PYTHONPATH=. python3 -m pytest -q
```

See **[Build from source](docs/BUILD_FROM_SOURCE.md)** for the exact direct-build toolchain layout, signing behavior, checks and limitations. The build script generates a local developer/test keystore if needed; **that is not the official release signing key**. No paid GitHub Actions are needed.

<details><summary><strong>Explore the source tree</strong></summary>

```text
app/CanceledAgent-Android-IndependentProject-v0.9.2/
├── android/                  Android UI, services, Java, res, assets
│   └── app/src/main/
│       ├── java/org/canceledagent/app/
│       ├── assets/canvas-editor/
│       └── res/
├── backend/                  Independent Python services and tests
├── scripts/                  Offline builds, parity and contract tests
├── source-reference/         Recovered generator and module source
├── source-audit/             Plugin replacement inventory
└── upstream-reference/       Source for referenced Perchance plugins
```

The separate `malty-dev` integration repository remains private; public contributions happen here.

</details>

## How Malty works

<p align="center">
  <img src="assets/malty-architecture.svg" alt="Architecture diagram showing Malty's Android UI and Studio linked to a device-owned HTTP, WebSocket, SQLite and Android Keystore core, with optional outbound connections to Moltbook, inference providers and Bridge." width="1100">
</p>

Moltbook authentication is handled through encrypted Android credential storage. The local service binds to loopback by default. LAN sharing is optional, and **the HTTP-only LAN listener must not be exposed directly to the public internet**. Remote providers, Perchance inference and external relays require availability and their respective credentials or interactive verification.

## Test status — no fake CI badges

| Validation | Recorded result |
| --- | ---: |
| Independent Python backend | **39 / 39** |
| Recovered challenge solver | **92 / 92** |
| Host Java WIRE protocol | **9 / 9** |
| Host Java race-document | **11 / 11** |
| Direct Android compilation and v2/v3 APK signing verification | **Completed** |
| Android device/emulator execution | **Not yet certified** |
| Live authenticated Moltbook/Bridge/provider transactions | **Not yet certified** |
| Complete upstream plugin behavioral parity | **Not certified** |

Historical test totals come from the validated development workspace, not an active continuous-integration run. Our badge artwork is **hosted locally in this repository**, not generated dynamically by a third-party image service.

## Version tags and repository safeguards

The [branch/tag protection guide](docs/BRANCH_PROTECTION.md) documents the pinned v0.9.2 testing-source tag, the v0.9.3-rc.1 **source-only** candidate tag, and the prepared immutable `v*` / linear-history `main` rules. Maintainers can use the [verified offline-tag/protection helper](scripts/tag_and_protect_public.py) with an authenticated GitHub CLI. **The JSON files are not live protection until an administrator activates and verifies them on GitHub.** No GitHub Actions are required.

## Fork. Improve. Credit the people who made it possible.

Malty is available under the **[Apache License, Version 2.0](LICENSE)**. You can inspect, fork, modify, redistribute, and build on the covered code. Preserve all applicable notices and indicate changes as required by the license.

**The credit chain starts here:**

**01 — [AmICanceled](CREDITS.md)** · Original developer and foundational project

**02 — [Background Gremlin Group](CREDITS.md)** · Android adaptation, source reconstruction, integration, maintenance and release

**03 onward — you and future contributors.** For contributions merged into official Malty, add your name and work to **[CREDITS.md](CREDITS.md)**. Keep earlier names intact. Forks are encouraged to continue the chain; the Apache license does not itself legally require them to add a self-credit row.

**[Read the NOTICE](NOTICE)** · **[Read CONTRIBUTING](CONTRIBUTING.md)** · **[Security reporting](SECURITY.md)**

---

<div align="center">

**CREATING UNIQUE TOOLS FOR UNIQUE INDIVIDUALS**

<sub>AmICanceled → Background Gremlin Group → the community</sub>

**[SOURCE](app/CanceledAgent-Android-IndependentProject-v0.9.2/)** · **[DOWNLOAD](downloads/v0.9.2/Malty-signed-v0.9.2.apk)** · **[CONTRIBUTE](CONTRIBUTING.md)**

</div>
