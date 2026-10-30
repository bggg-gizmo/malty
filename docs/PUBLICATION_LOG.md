# Malty public publication coordination log

## 2026-10-29 — initial public-facing repository publication

Repository: `bggg-gizmo/malty`, public, default branch `main`.

Completed through the connected GitHub application:
- Expanded `README.md` with live badges and documented Malty v0.9.2 capabilities and limitations.
- Committed verified owl graphic from the development archive at `assets/malty-square-512.png`, Git blob SHA `2f5cfe7fb2ca449f6fde7ef41d2b6283331559c4` and SHA-256 `c6fad806d5e1cb09ea1608d58bf0a6883b82a4558c57ed7d019242d52ffc94f1`.
- Added `CONTRIBUTING.md`, `SECURITY.md`, bug/feature issue forms, and the pull-request template.
- Added branch/tag ruleset definitions and authenticated activation/check scripts that do not use GitHub Actions.
- Corrected a README link to a Discussions feature that is not currently enabled on the repository.
- Recorded v0.9.2 signed/unsigned APK hashes without claiming the APKs are downloadable from GitHub.

Unfinished:
- The GitHub connection does not expose administrative ruleset mutation. JSON in the repository is **not** proof that protection is enabled.
- Neither v0.9.2 candidate APK has been published via GitHub Releases.
- The private development repository's full 310-file remote sync remains unverified/incomplete; do not claim 310/310 remotely.
- Physical-device acceptance, authenticated external service verification, and full upstream plugin parity remain open.

Development boundary: preserve all existing features and UI/UX. Do not mirror private assets, release signing credentials, or unlicensed recovered third-party components to the public repository without explicit authorization.

## 2026-10-29 — direct public-testing APK publication

The byte-verified signed v0.9.2 APK was published as `downloads/v0.9.2/Malty-signed-v0.9.2.apk` in `main`, with `downloads/v0.9.2/SHA256SUMS.txt` and `downloads/v0.9.2/RELEASE_NOTES.md`. Its Git blob SHA is `25ad469fe17db0a7cac26597d2afb8b22ae52403` and SHA-256 is `a7baf8e9dbe00dd7879da445b109cac82870b81483e9a627b5b149f448c8cb0e`. Existing README content was retained while adding a direct public download path.

This is an Android *testing build*. No device or live external-service certification is claimed. The GitHub connector does not expose release creation, so no GitHub Release object or tag is claimed. Private development ZIPs, prior source archives, unsigned APK, SQLite state, signing keys and coordination logs were not copied here. GitHub Actions were not used.

## 2026-10-29 — checkpoint 4 artifact verification

- Verified the public v0.9.2 APK against SHA-256 `a7baf8e9dbe00dd7879da445b109cac82870b81483e9a627b5b149f448c8cb0e`, byte size 555143, and Git blob SHA `25ad469fe17db0a7cac26597d2afb8b22ae52403`.
- Ran six dedicated release-verifier regression tests (6/6), including malformed ZIP, embedded database, manipulated checksum, duplicate ZIP entries, ZIP path traversal, and valid signed candidate.
- Verified APKSigner v2/v3 signatures and signer certificate SHA-256 `c2dedd7d4dbaa1b3eaf499849f2da4cc411e4e2779ce8c16642dfb5b57452010`.
- Added standard-library offline verifier `scripts/verify_v092_artifacts.py`, Windows wrapper `scripts/VERIFY-MALTY-v0.9.2.cmd`, and machine-readable `docs/VERIFICATION_v0.9.2.json`.
- Existing `main` policy JSON includes a PR requirement that conflicts with the current direct-to-`main`, no-PR publishing workflow. Both public rulesets remain inactive; no protection settings were changed.
- A native GitHub Releases prerelease still requires a release-creation capability unavailable through the connected GitHub Git Data integration. The public signed APK remains directly downloadable in `downloads/v0.9.2/`.
- Physical device execution, authenticated live services, and complete recovered-plugin parity have not been certified. No GitHub Actions, force pushes, or unauthorized branches were used.

## 2026-10-29 — checkpoint 5 validation and direct-main policy alignment

The signed v0.9.2 APK remains present on public `main` with the verified original blob SHA `25ad469fe17db0a7cac26597d2afb8b22ae52403` and SHA-256 `a7baf8e9dbe00dd7879da445b109cac82870b81483e9a627b5b149f448c8cb0e`. The native GitHub Releases API still lists no releases; the public repository lists no active rulesets. A local, separately downloadable release publishing/verification tool and regression tests were prepared, with 16/16 local unit tests passing. No actual GitHub Release creation or physical-device/authenticated-service certification is claimed.

The stored `malty-main-branch-ruleset.json` previously required pull requests and conflicted with the authorized direct-`main` workflow. The pending ruleset configuration now disallows deletions, non-fast-forward updates and merge commits while allowing fast-forward direct publishing. This is a change to an **inactive ruleset definition only**; the live remote rulesets still need explicitly authorized activation. No GitHub Actions or paid workflows were executed.

## 2026-10-29 — checkpoint 6 release verification hardening

Development and public GitHub heads were independently inspected before publication. The public repository continues to distribute the original signed Malty v0.9.2 testing APK without changing the application, its UI, its package ID, or its signing identity.

Commit `53feb777f13837dea1d34e20e174ffe19043f16d` hardens the native GitHub prerelease publishing script and regression suite. The script now validates the public SHA256SUMS.txt Git blob SHA and size against the approved local contents, refuses to accept an existing GitHub Release if its expected v0.9.2 tag cannot be resolved, and re-reads the tag immediately after release creation before claiming successful publication. The updated publisher and tests were published and independently checked against their expected Git blob hashes.

Checkpoint 6: 19/19 local offline regression tests passed. The existing public signed APK separately passed direct APKSigner v2/v3 signature verification and matched the pinned signing certificate SHA-256. No Android app byte change was part of this checkpoint. The development repository still has 309/310 original source paths; the original 11,875,261-byte recovery archive is preserved as eight SHA-verified segments with a reassembly utility instead of an unsplit Git blob. The original integration audit and coordination log have intentional modifications and must not be overwritten by a mechanical manifest sync.

The connected GitHub tools support Git blobs, trees, commits and branch-ref updates but do not expose GitHub Releases creation, release asset attachment or administrative ruleset mutation. Live API inspection still reports zero native Releases and zero active rulesets for the public repository; do not conflate a downloadable APK in the Git tree with a published native GitHub Release. There is no authenticated GitHub CLI in the build container. Physical-device testing, authenticated Moltbook/Bridge/provider operations, and full external plugin parity remain unverified. No GitHub Actions, pull requests, unauthorized branches, force updates or production-certification claims were made.

## 2026-10-29 — checkpoint 7 Android acceptance tooling

Added the complete standalone Malty v0.9.2 Android device acceptance harness to public `main` at commit `b802be1c41071f4ec4e0e1ff2f1f7172277cdb6a` and published a matching private development copy at commit `462369fa0302f0fc4193fce9b6d5dbfc54ae99e4`. New source/test/docs/launcher Git blobs were compared against the original 5-file transfer manifest and verified after fast-forward publication.

The runner verifies the already-published signed APK, requires exactly one authorized ADB target (or explicit device selection), rejects an installed version other than 0.9.2, and requires explicit opt-in to install/update, launch, screenshot the six original navigation pages, or invoke the app's isolated SQLite/HTTP/WebSocket integration suite. It never deliberately taps posting, payment or provider controls. Screenshot evidence remains on the tester's local device host, not in GitHub.

24/24 new acceptance runner tests and 19/19 previous publisher/artifact tests passed locally. APK integrity and signing were checked again, with no application-code changes. ADB reported no attached device; no native Android acceptance passed yet. Live authenticated Moltbook/Bridge/provider transactions and complete external-plugin parity remain unverified. The public native GitHub Releases entry and active rulesets remain outstanding; the signed APK is still directly available through the public repository.

No GitHub Actions, paid CI, PRs, force updates, unauthorized branches, private signing materials or app UI changes were introduced.


## 2026-10-30 — public source verification

All 234 source manifest paths are independently verified against live public GitHub blob hashes. The public Java Android source built successfully using Java 17, SDK API 36, Build Tools r36, and D8; the locally signed test APK verifies v2/v3. 39 Python backend tests, 92 solver cases, 9 Java WIRE cases, 11 Java race cases and additional host integration contracts passed. The historical standalone source ZIP contains a stale integration-audit script; GitHub's browsable source has the corrected version. README now prioritizes the generated current-branch ZIP; see `docs/SOURCE_PUBLICATION_VERIFICATION.md`. No app code/UI changes or paid GitHub Actions.

## 2026-10-30 — source-only security hardening; v0.9.2 APK unchanged

Promoted 13 security source/test/documentation files from private development, retaining all existing Android activities, source features, UI layout, backend operations and the byte-identical original signed testing APK. The Python backend now authenticates /v1 and other non-health paths with AGENT_BACKEND_TOKEN and refuses unauthenticated remote peers. Android Bridge now verifies HTTPS hostname identity and requires operation-scoped HMAC signatures, connection challenges, fresh timestamps and request ID replay filtering; an older unsigned relay must be upgraded to protocol v1 to invoke those same operations. Optional plaintext LAN hosting is restricted to local/private peers but still requires a trusted encrypted network or an authenticated TLS/VPN layer for untrusted use. Clipboard security improved without removing the copy-token control.

New host test coverage: Java HMAC helper 10/10 cases, live trusted TLS test correct host accepted / incorrect host rejected, 4 Python relay signer tests, Java helper Android API 36/Java 8 compilation. The **new integrated Python regression suite and complete Android rebuild have not been executed in this publication step**; neither device/live-network certification nor original public APK modification is claimed. See docs/SECURITY_HARDENING_v0.9.2.md. No force push, paid GitHub Actions or native Release/tag change.
