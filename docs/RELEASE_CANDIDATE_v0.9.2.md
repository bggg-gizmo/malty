# Malty v0.9.2 — public-testing candidate

Status: **candidate prepared; public APK release not yet published**.

This file documents the exact signed and unsigned Android packages supplied for the v0.9.2 checkpoint. It is not a claim of completed on-device certification or a download link.

## Candidate artifacts and SHA-256

| Artifact | Size (bytes) | SHA-256 |
| --- | ---: | --- |
| Malty-signed-v0.9.2.apk | 555143 | `a7baf8e9dbe00dd7879da445b109cac82870b81483e9a627b5b149f448c8cb0e` |
| Malty-unsigned-v0.9.2.apk | 546451 | `aaaeffe82246ece7707202c181bd550423a155b1a3d0ac072fc92fe9d97eaa44` |

The signed APK has an existing developer certificate retained for update continuity, and the private signing key is not publicly distributed. Signature validation and SHA-256 integrity checks were performed in the local workspace. The unsigned APK is a development artifact and is not a signed installable release.

## Compatibility and functional scope

- Native Android application ID: `org.canceledagent.app`, retained for upgrade compatibility.
- Minimum supported Android version: Android 8.0 / API 26.
- Moltbook client workflows, agent conversations, Canvas Editor / Studio, Blueprint / TouchBridge, Bridge / Fleet, Webmesh / Market, Lab / Radio, and embedded HTTP/WebSocket and SQLite services.
- Optional network integrations and Perchance inference require network access and provider availability.

## Verification record

- 39/39 independent Python host tests.
- 92/92 recovered challenge solver tests.
- 9/9 Java WIRE contract cases.
- 11/11 Java race-document cases.
- Android compilation, packaging, and APK signing completed.
- Physical device and authenticated live-service acceptance **not yet verified**.
- Complete behavior parity with original recovered external plugins **not certified**.

## Publication conditions

Publish the **signed** APK as a GitHub prerelease only after ensuring its bytes match the SHA-256 above and confirming no secrets or personal data are embedded. Do not identify this candidate as a production-stable release without the necessary acceptance results. The public repository's Releases page is the intended distribution location. The private development repository is not to be mirrored indiscriminately.
