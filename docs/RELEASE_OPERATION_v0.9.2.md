# Malty v0.9.2 — verified GitHub prerelease operation

**Release type:** public Android testing prerelease; not production-certified.  
**Source:** `bggg-gizmo/malty`, default branch `main`.  
**Public build:** `downloads/v0.9.2/Malty-signed-v0.9.2.apk`.  
**SHA-256:** `a7baf8e9dbe00dd7879da445b109cac82870b81483e9a627b5b149f448c8cb0e`.  
**Signing certificate SHA-256:** `c2dedd7d4dbaa1b3eaf499849f2da4cc411e4e2779ce8c16642dfb5b57452010`.

## Local regression and artifact verification

From a copy of the public repository, with Python 3 installed:

```sh
python3 -m unittest discover -s tests -v
python3 scripts/verify_v092_artifacts.py
```

APKSigner verification, if Android build-tools are installed:

```sh
python3 scripts/verify_v092_artifacts.py --apksigner /path/to/apksigner
```

The verifier checks the exact pinned APK, ZIP structure, database/key file exclusions, and optionally the verified v2/v3 signature and existing certificate. Tests never run GitHub Actions or contact paid service providers.

## Create and verify the native GitHub testing prerelease

GitHub Release creation requires an authenticated GitHub CLI account with permission to publish releases. This capability is not exposed through the current connected GitHub Git Data integration, so publication requires GitHub CLI or the GitHub web interface. The existing APK on `main` does not by itself create a GitHub Release or tag.

After `gh auth login` (or an already working `gh auth status`), run:

```sh
python3 scripts/publish_v092_prerelease.py
python3 scripts/publish_v092_prerelease.py --check
```

Windows CMD:

```cmd
scripts\PUBLISH-VERIFIED-MALTY-v0.9.2.cmd
scripts\PUBLISH-VERIFIED-MALTY-v0.9.2.cmd --check
```

The program verifies current `main`, repository visibility, exact approved APK blob, local release notes and checksum, and any existing `v0.9.2` tag. If no native release exists and no conflicting tag exists, it creates the **prerelease** from the observed `main` commit with exactly two assets: the original signed APK and `SHA256SUMS.txt`. It then independently **downloads the GitHub Release assets** and verifies byte size, SHA-256, Git blob SHA-1, ZIP integrity and checksum file. Any mismatch terminates with a nonzero exit code. The script does not use force pushes, create branches or PRs, clobber release assets, publish unsigned APKs, or run GitHub Actions.

If a release already exists, the same program verifies it without overwriting its notes or assets; `--check` always uses read-only actions. A conflicting tag or an unexpected release draft, classification, asset set, or signature fails closed. If GitHub rejects a release operation, retain the exact error and do not bypass protection settings.

## Activate public history protections

The public ruleset definitions in `rulesets/` are **not active merely because their JSON is committed**. They protect `main` against deletion, non-fast-forward updates and merge commits, and protect `v*` tags against deletion or mutation. They intentionally do **not** require pull requests, preserving the authorized direct-`main` Git Data fast-forward publishing model.

After finishing the prerelease, an account with the necessary GitHub repository administration permission can run:

```sh
python3 scripts/apply_public_rulesets.py
python3 scripts/apply_public_rulesets.py --check
```

Alternatively apply both definitions through repository Settings → Rules → Rulesets. Do not falsely report them activated until the live remote rulesets are independently queried and verified. Existing unrelated rulesets or branch settings must remain unchanged. Release tag protections may prevent future tag mutation, which is intentional.

## Testing and licensing boundaries

Development history and third-party recovery materials remain in private `bggg-gizmo/malty-dev`. No private signing keys, SQLite databases, secrets, internal work logs or recovered third-party source are included in the public testing release. A project-wide public source license has not been selected. Physical-device behavior, live authenticated Moltbook/Bridge writes, and complete recovered-plugin behavior parity have not been certified. None of these are implied by successful host tests, APK signing or GitHub publishing.
