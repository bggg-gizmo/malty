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
