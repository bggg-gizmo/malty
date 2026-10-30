# Malty public publication checklist

Public repository: https://github.com/bggg-gizmo/malty
Private development repository: https://github.com/bggg-gizmo/malty-dev

## Completed in the public repository

- [x] Publish a full project README with dynamic GitHub shields, documented capabilities, and explicit test limitations.
- [x] Publish a verified owl asset as `assets/malty-square-512.png`.
- [x] Publish contribution and security reporting guidance.
- [x] Provide structured issue forms and a pull-request template.
- [x] Commit two GitHub ruleset definitions for `main` and `v*` tags.
- [x] Commit a no-Actions GitHub CLI ruleset activation and read-only verification utility.
- [x] Record signed and unsigned v0.9.2 candidate hashes and sizes.

## Outstanding release and repository work

- [ ] Verify private `malty-dev` remote has received all 310 original source-package files at normal paths, and independently check hashes against the manifest.
- [ ] Confirm which additional development sources and third-party components can legally and safely be published; no blanket redistribution permission has been assumed.
- [ ] Check the intended public release candidate and any Git history for tokens, API credentials, private signing keys, cookies, personal records, and databases.
- [ ] Complete or clearly disclose outstanding physical-device, authenticated live-service, and plugin-parity validation.
- [ ] Upload the specifically approved signed v0.9.2 APK as a GitHub **prerelease**, attach SHA-256 checksums and verification notes, and independently verify GitHub-downloaded bytes.
- [ ] Activate both rulesets using Settings → Rules → Rulesets or run `python3 scripts/apply_public_rulesets.py` with an authenticated administrative GitHub CLI. Verify the actual remote rulesets with `python3 scripts/apply_public_rulesets.py --check`.
- [ ] Verify that direct commits, force pushes and deletion of `main` are refused, and that published `v*` tags cannot be changed or removed; test on disposable refs without modifying actual release tags.
- [ ] Review README version links and actual release downloads after publishing the prerelease.
- [ ] Choose an appropriate project license if public source redistribution is explicitly approved. Do not assume one.

## Non-regression

The public repository's documentation and issue forms do not change Android application code, UI layouts, embedded services, app identity, APK signing keys, or the existing development commit history. No GitHub Actions are required.

## 2026-10-29 — additional verified progress

- [x] The signed v0.9.2 candidate APK is publicly downloadable at `downloads/v0.9.2/Malty-signed-v0.9.2.apk` with a matching SHA-256 checksum.
- [x] Public release notes explicitly disclose outstanding physical-device, authenticated-service, and plugin-parity acceptance.
- [x] Source and recovered-private-development archives have not been mirrored into the public repository.
- [ ] Create the native GitHub Releases **prerelease entry** (not supported by the connected GitHub Git Data actions); then verify GitHub-downloaded release assets.
- [ ] Activate and verify public repository rulesets and finish device/live-service acceptance.

## Checkpoint 4 verification progress

- [x] Verified the published testing APK against pinned SHA-256, Git blob SHA-1, and exact byte count.
- [x] Verified v2/v3 APK signatures and certificate continuity with APKSigner.
- [x] Added a fully offline artifact verifier and Windows CMD wrapper, with six passing negative/positive regression tests.
- [x] Confirmed the recovery archive's bytes are preserved as eight verified segments in the private development repository with a reconstruction tool.
- [ ] Create a native GitHub Releases prerelease and independently verify its release asset download when release creation is available.
- [ ] Resolve existing PR-required main ruleset versus directly publishing to main without pull requests before activating protection.
- [ ] Complete physical-device, authenticated service, and full plugin-parity acceptance.
