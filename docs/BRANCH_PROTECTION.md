# Malty branch protection and release-tag rules

These instructions apply to **public** https://github.com/bggg-gizmo/malty only. These rules have been prepared but **not applied** to GitHub.

## Before enabling the rules

1. Publish and review the approved public README, artwork, and any approved source or release files on `main` first. Applying the strict rules before the first import would block direct bootstrap pushes.
2. In repository **Settings → General → Pull Requests**, make sure **Squash merging** is enabled (Rebase merging is optional). The rule requires linear history.
3. Import `rulesets/malty-main-branch-ruleset.json` using **Settings → Rules → Rulesets → New ruleset → Import a ruleset**, review it, and enable it.
4. Import `rulesets/malty-release-tags-ruleset.json` the same way to protect release tags matching `v*`. Future tags can be created; existing release tags cannot be moved or deleted by ordinary pushes.
5. Confirm effective rules on `main` and `v*` in the GitHub rules UI and verify with a disposable feature branch/pull request. Avoid creating a fake public release merely to test tag protection.

GitHub documentation: https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-rulesets/creating-rulesets-for-a-repository

## Public `main` policy

| Setting | Initial value | Why |
| --- | --- | --- |
| Require a pull request | **On** | Every main-branch change is documented |
| Required approving reviews | **0** | One-person maintainer workflows would be locked out by a required independent review |
| Require review conversation resolution | **On** | Blocks merges with unresolved review threads |
| Dismiss stale approvals | **On** | Already prepared for future multi-maintainer reviews |
| Code-owner review | **Off** | No named additional reviewers have been configured |
| Latest-push approval | **Off** | Prevents locking a solo contributor out |
| Require linear history | **On** | Use squash or rebase, no merge commits |
| Block force pushes | **On** | Prevents history rewrites |
| Block branch deletion | **On** | Protects `main` |
| Required status checks | **None** | No paid GitHub Actions and no independently reporting check is installed |
| Required deployment checks | **None** | No external deployment reporter configured |
| Require signed commits | **Off initially** | May reject existing unsigned commits; enable after signing is configured and validated |
| Bypass actors | **None** | Enforcement is not silently bypassed by repo admins |

Once a second reviewer with write permissions is available, change required approvals to **1** (or more by agreement) and consider code-owner review. Set required checks only **after** you confirm a named external check actually reports a status on pull requests; otherwise GitHub will block every merge waiting for a nonexistent check.

## Public tag policy

`refs/tags/v*` is protected from updates and deletions. Tags may still be newly created for releases. Do not move a published tag; publish a new version instead.

## Private dev repository

`bggg-gizmo/malty-dev` remains private. GitHub's ruleset API currently reports that rulesets are unavailable there on the present plan, and GitHub's documentation gates private-repository rulesets to qualifying paid plans. Do not change the visibility of that repository just to obtain free branch protection. Keep a separate reviewed local Git procedure until GitHub plan/entitlements are changed.

## GitHub limitations

The ChatGPT-connected GitHub integration did not expose a ruleset creation/mutation operation, so these settings are delivered as importable files; the JSON files themselves do **not** enforce rules until imported in GitHub. Existing classic branch-protection state was not readable through this integration, so check Settings → Branches to avoid unintended overlapping rules.