# Malty public source integrity and independent build verification — 2026-10-30

## What was published

The public `bggg-gizmo/malty` `main` Git tree has all **234/234** files from the public source transfer manifest at their normal browsable paths. All 234 Git blob SHA-1 values match the locally prepared source, with zero missing or mismatched entries. APK and original application logic were not changed.

The repository contains `LICENSE` (Apache-2.0), `NOTICE`, `CREDITS.md` (AmICanceled → Background Gremlin Group → subsequent contributors), `CONTRIBUTING.md`, the local README artwork and six locally hosted SVG badges. These badges do not claim that unrun CI has passed.

Signed APK: `downloads/v0.9.2/Malty-signed-v0.9.2.apk`; Git blob `25ad469fe17db0a7cac26597d2afb8b22ae52403`; SHA-256 `a7baf8e9dbe00dd7879da445b109cac82870b81483e9a627b5b149f448c8cb0e`.

## Independent exported-source checks

A separate workspace extracted the source ZIP and was tested using Python 3, Node.js, OpenJDK 17, Android Build Tools r36, Android Platform API 36, AAPT2, D8, and APKSigner:

| Validation | Verified outcome |
| --- | --- |
| Python backend | 39 tests passed |
| Recovered challenge solver | 92/92 cases |
| Embedded app contract | 15/15 |
| Character network | 9/9 |
| Parity additions | 7/7 |
| Race integration | 6/6 |
| v0.9.1 contract | 9/9 |
| Java WIRE | 9/9 |
| Java race document | 11/11 |
| Integration audit | PASS with the GitHub source's corrected script |
| Direct Android source build | PASS |
| Local test-key APK signing v2/v3 | PASS |

No real Android device was attached; no live external service actions were run. The local test-key APK is not an official release-signing identity.

## Historical standalone ZIP caveat

The older standalone `source/Malty-v0.9.2-Complete-Public-Source.zip` is retained unchanged for history at 2,055,245 bytes, Git SHA `b842af400308bf8d6a678a3c6c4a066b80b153cc`. It has 247 entries and no ZIP corruption, but contains the earlier 1,190-byte `scripts/audit_integrations.py` that incorrectly rejects valid Perchance character-share URL data.

The correct current source file is 1,726 bytes, Git SHA `5126a6efd439eb0f96fdb0d6a5e4e84d82dd1a8f`, and is present in public `main` and private `malty-dev`. Use the latest `main` source archive from GitHub's automatic code downloads at [archive/refs/heads/main.zip](https://github.com/bggg-gizmo/malty/archive/refs/heads/main.zip) or clone `main` to obtain this corrected implementation.

The corrected standalone bundle was independently reconstructed and ZIP-validated locally with SHA-256 `3d74627fbe9eadeb2ba494e2c91c20d7caa40ad3813d148d9525153c8a4e9808`. It has **not** replaced the historical standalone GitHub blob.

## Remaining release gates

GitHub native Releases publishing, activation of branch/tag rulesets, physical-device testing, authenticated provider/service acceptance, and exhaustive plugin parity remain open. No paid GitHub Actions, force pushes, unauthorized branches, UI changes, or feature deletions were used.
