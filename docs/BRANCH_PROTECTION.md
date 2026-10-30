# Malty public branch and release-tag protection

This document applies to `bggg-gizmo/malty` (public). The repository contains two **prepared** rulesets. The rules are **not active merely because their JSON files are committed**. Verify live status after applying.

## Ref and release policy

| Ref | Pinned commit | Meaning |
| --- | --- | --- |
| `main` | Fast-forward mutable | Main development/publication line; direct linear commits remain allowed |
| `v0.9.2` | `9a1c4439dfe9d05036c051fb87a4be7e8fa317c3` | Historical v0.9.2 public source and unchanged signed testing APK (versionCode 11) |
| `v0.9.3-rc.1` | `f81d17c5381ecf4d7f36e40ae5486438207bbb46` | Source-only hardened 0.9.3 candidate (versionCode 12); **not** an official production-signed APK |
| `v0.9.3` | **Not created** | Reserved for a future owner-approved v0.9.3 distribution/release |

The v0.9.2 tagged commit contains the original 555143-byte APK Git blob `25ad469fe17db0a7cac26597d2afb8b22ae52403` and the v0.9.2 direct-builder blob. The source candidate tag also contains the original v0.9.2 APK, but its build metadata is v0.9.3. Neither Git tag by itself creates a GitHub Release or signs an APK.

## Protection policy

Public `main` is covered by `rulesets/malty-main-branch-ruleset.json`, which prevents deletions, force/non-fast-forward updates, and merge commits. **Direct fast-forward commits remain permitted; no pull request or paid status check is required.** GitHub account permissions continue to apply. No bypass actors are listed. Squash/rebase must remain available if pull requests are used.

All `refs/tags/v*` are covered by `rulesets/malty-release-tags-ruleset.json`: existing matching tags cannot be updated or deleted. **New version tags can still be created**, but must be verified and approved before publication. No bypass actors are listed. There are no automatic restrictions on the creation of arbitrary non-version tag names. Do not move an issued version tag—issue a new version instead.

## Install tags and protections

Use a trusted local computer with Python 3 and [GitHub CLI](https://cli.github.com/), authenticated to the repository as an administrator:

```sh
gh auth login
gh auth status
git clone https://github.com/bggg-gizmo/malty.git
cd malty
python3 scripts/tag_and_protect_public.py --apply
python3 scripts/tag_and_protect_public.py --check
```

Windows CMD after cloning:

```bat
scripts\PROTECT-PUBLIC-HISTORY.cmd --apply
scripts\PROTECT-PUBLIC-HISTORY.cmd --check
```

Linux/macOS alternative: `bash scripts/PROTECT-PUBLIC-HISTORY.sh --apply` and `--check`.

The script first checks that the pinned commits are ancestors of the live `main` and contain the expected original APK and direct-build Git blobs. It creates missing **annotated** `v0.9.2` and `v0.9.3-rc.1` tags without moving pre-existing refs, then invokes `scripts/apply_public_rulesets.py` to create and verify both active rulesets. It fails rather than changing a conflicting tag or existing ruleset. Running `--check` makes **no changes**. The script cannot install the rulesets without GitHub administration permissions.

The rule JSONs can alternatively be imported manually at [GitHub repository rulesets](https://github.com/bggg-gizmo/malty/settings/rules) → **New ruleset** → **Import a ruleset**, and reviewed before clicking Create. This manual action does **not** create the version tags; use the tag script for that step.

## Verify enforcement

```sh
gh api repos/bggg-gizmo/malty/git/ref/tags/v0.9.2
gh api repos/bggg-gizmo/malty/git/ref/tags/v0.9.3-rc.1
gh api repos/bggg-gizmo/malty/rulesets
python3 scripts/tag_and_protect_public.py --check
```

GitHub's `main` branch readout should say protected; two active rulesets should have the specified targets and rules. Avoid creating fake tags or attempting destructive force pushes as a test. If classic branch protection exists separately, check [Settings → Branches](https://github.com/bggg-gizmo/malty/settings/branches): GitHub layers both policies and the stricter combination applies.

## Private development repository

`bggg-gizmo/malty-dev` remains private. On October 30, 2026 its ruleset endpoint returned **403** with the GitHub message: `Upgrade to GitHub Pro or make this repository public to enable this feature.` Do **not** expose the private repository just for protection. Until plan entitlements change, enforce private branch/tag discipline with authorized reviewer policy, locally verified commits, and manual controls. No private rulesets are claimed active.

## Capability boundary

The connected ChatGPT GitHub integration supports file/tree/commit/branch-ref publication but does not expose an action to create `refs/tags/*` or mutate repository rulesets. Only an administrator through the GitHub UI or locally authenticated GitHub CLI can complete these operations here. A checked-in JSON configuration is not proof of active enforcement. No paid GitHub Actions are used.
