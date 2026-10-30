#!/usr/bin/env python3
"""Publish or independently verify the pinned Malty v0.9.2 testing prerelease.

Requires an authenticated GitHub CLI (gh) with release write permission.
No GitHub Actions, branches, pull requests, force updates, or release-asset clobber.

Usage:
  python3 scripts/publish_v092_prerelease.py --check
  python3 scripts/publish_v092_prerelease.py
  python3 scripts/publish_v092_prerelease.py --apksigner /path/to/apksigner

The script refuses to overwrite an existing release, reuse a conflicting tag,
ship nonmatching APKs, or report success before redownloading and hashing the
actual GitHub Release assets. --check never changes GitHub state.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys
from tempfile import TemporaryDirectory

from verify_v092_artifacts import (
    ARTIFACT, BYTE_COUNT, GIT_BLOB_SHA1, REPOSITORY, ROOT, SHA256, run as verify_apk
)

TAG = "v0.9.2"
RELEASE_TITLE = "Malty v0.9.2 — Android Testing Prerelease"
ASSET_DIR = ROOT / "downloads" / TAG
NOTES = ASSET_DIR / "RELEASE_NOTES.md"
CHECKSUMS = ASSET_DIR / "SHA256SUMS.txt"
APK = ASSET_DIR / ARTIFACT
EXPECTED_ASSETS = {ARTIFACT, "SHA256SUMS.txt"}


class PublicationError(RuntimeError):
    pass


def gh(*args: str, allow_missing: bool = False) -> str | None:
    """Run only explicit GitHub CLI subcommands; never invoke a shell."""
    proc = subprocess.run(
        ["gh", *args], check=False, capture_output=True, text=True,
        encoding="utf-8", timeout=120,
    )
    if proc.returncode:
        message = proc.stderr.strip() or proc.stdout.strip()
        if allow_missing and ("HTTP 404" in message or "not found" in message.lower()):
            return None
        raise PublicationError(f"GitHub CLI failed (exit {proc.returncode}): " + message)
    return proc.stdout.strip()


def api(path: str, *, allow_missing: bool = False) -> dict | None:
    result = gh("api", path, allow_missing=allow_missing)
    if result is None:
        return None
    try:
        parsed = json.loads(result)
    except json.JSONDecodeError as exc:
        raise PublicationError("GitHub returned malformed API JSON") from exc
    if not isinstance(parsed, dict):
        raise PublicationError("GitHub API returned an unexpected object")
    return parsed


def ensure_environment() -> None:
    if shutil.which("gh") is None:
        raise PublicationError("GitHub CLI (gh) is not installed")
    gh("auth", "status")


def verify_local(apksigner: str | None) -> dict:
    if not NOTES.is_file():
        raise PublicationError("Release notes missing: " + str(NOTES))
    if not NOTES.read_text(encoding="utf-8").strip():
        raise PublicationError("Release notes are empty")
    return verify_apk(APK, CHECKSUMS, apksigner)


def verify_remote_repository() -> str:
    repo = api("repos/" + REPOSITORY)
    if repo is None or repo.get("full_name", "").lower() != REPOSITORY.lower() or repo.get("private"):
        raise PublicationError("Unexpected repository or repository is not public")
    if repo.get("default_branch") != "main":
        raise PublicationError("Refusing to publish from a non-main default branch")
    head = api("repos/" + REPOSITORY + "/git/ref/heads/main")
    if head is None:
        raise PublicationError("Public main branch does not exist")
    head_sha = head.get("object", {}).get("sha")
    if not isinstance(head_sha, str) or len(head_sha) != 40:
        raise PublicationError("Invalid public main commit SHA")
    tree = api("repos/" + REPOSITORY + "/git/trees/main?recursive=1")
    if tree is None or tree.get("truncated"):
        raise PublicationError("Cannot inspect complete public source tree")
    entries = {item["path"]: item for item in tree.get("tree", []) if item.get("type") == "blob"}
    apk_entry = entries.get("downloads/v0.9.2/" + ARTIFACT)
    if not apk_entry or apk_entry.get("sha") != GIT_BLOB_SHA1 or apk_entry.get("size") != BYTE_COUNT:
        raise PublicationError("Public main does not contain the exact approved APK Git blob")
    if "downloads/v0.9.2/SHA256SUMS.txt" not in entries:
        raise PublicationError("Public checksum file is missing")
    return head_sha


def validate_tag_target(head_sha: str, *, existing_release: bool) -> None:
    tag = api("repos/" + REPOSITORY + "/git/ref/tags/" + TAG, allow_missing=True)
    if tag is None:
        return  # gh release create will create the tag from the pinned --target commit.
    obj = tag.get("object", {})
    if obj.get("type") == "commit":
        commit_sha = obj.get("sha")
    elif obj.get("type") == "tag":
        nested = api("repos/" + REPOSITORY + "/git/tags/" + str(obj.get("sha")))
        if not nested or nested.get("object", {}).get("type") != "commit":
            raise PublicationError("Tag does not ultimately refer to a commit")
        commit_sha = nested["object"].get("sha")
    else:
        raise PublicationError("Unexpected tag object type")
    if commit_sha != head_sha:
        if not existing_release:
            raise PublicationError("Existing release tag points to a different commit; will not move it")
        # A verified release can legitimately predate later documentation commits.
        comparison = api("repos/" + REPOSITORY + "/compare/" + commit_sha + "..." + head_sha)
        if comparison is None or comparison.get("behind_by") != 0:
            raise PublicationError("Release tag is not an ancestor of public main")
    tag_tree = api("repos/" + REPOSITORY + "/git/trees/" + commit_sha + "?recursive=1")
    if tag_tree is None or tag_tree.get("truncated"):
        raise PublicationError("Cannot inspect complete release tag tree")
    found = next((item for item in tag_tree.get("tree", [])
                  if item.get("path") == "downloads/v0.9.2/" + ARTIFACT and item.get("type") == "blob"), None)
    if found is None or found.get("sha") != GIT_BLOB_SHA1 or found.get("size") != BYTE_COUNT:
        raise PublicationError("Release tag does not contain the verified v0.9.2 APK")


def validate_release_info(release: dict, head_sha: str) -> dict:
    if release.get("tag_name") != TAG or not release.get("prerelease") or release.get("draft"):
        raise PublicationError("Existing release is not the approved published testing prerelease")
    assets = release.get("assets", [])
    if not isinstance(assets, list):
        raise PublicationError("Unexpected GitHub release asset response")
    names = [a.get("name") for a in assets]
    if len(names) != len(set(names)) or set(names) != EXPECTED_ASSETS:
        raise PublicationError("Release asset list is missing, duplicated or contains unexpected items")
    sizes = {a["name"]: a.get("size") for a in assets}
    if sizes[ARTIFACT] != BYTE_COUNT or sizes["SHA256SUMS.txt"] != CHECKSUMS.stat().st_size:
        raise PublicationError("Remote release asset sizes do not match the approved files")
    return {"tag": TAG, "release_url": release.get("html_url"), "release_id": release.get("id"),
            "prerelease": True, "draft": False, "asset_sizes": sizes}


def verify_release(head_sha: str) -> dict:
    release = api("repos/" + REPOSITORY + "/releases/tags/" + TAG, allow_missing=True)
    if release is None:
        raise PublicationError("Native GitHub Releases prerelease does not exist")
    info = validate_release_info(release, head_sha)
    with TemporaryDirectory(prefix="malty-release-verify-") as folder:
        dest = Path(folder)
        for filename in sorted(EXPECTED_ASSETS):
            gh("release", "download", TAG, "-R", REPOSITORY,
               "--pattern", filename, "--dir", str(dest))
        downloaded_apk, downloaded_sums = dest / ARTIFACT, dest / "SHA256SUMS.txt"
        if not downloaded_apk.is_file() or not downloaded_sums.is_file():
            raise PublicationError("GitHub release download did not return all requested assets")
        if downloaded_sums.read_bytes() != CHECKSUMS.read_bytes():
            raise PublicationError("GitHub-downloaded checksum manifest differs from approved release")
        result = verify_apk(downloaded_apk, downloaded_sums)
        info.update({"download_verified": True, "sha256": result["sha256"],
                     "git_blob_sha1": result["git_blob_sha1"], "size": result["size"]})
    return info


def publish(apksigner: str | None, check_only: bool) -> dict:
    ensure_environment()
    local = verify_local(apksigner)
    head_sha = verify_remote_repository()
    existing = api("repos/" + REPOSITORY + "/releases/tags/" + TAG, allow_missing=True)
    validate_tag_target(head_sha, existing_release=existing is not None)
    if existing is None:
        if check_only:
            raise PublicationError("No native v0.9.2 GitHub prerelease exists; --check makes no changes")
        gh("release", "create", TAG, str(APK), str(CHECKSUMS), "-R", REPOSITORY,
           "--target", head_sha, "--title", RELEASE_TITLE, "--notes-file", str(NOTES),
           "--prerelease", "--latest=false")
    info = verify_release(head_sha)
    info.update({"repository": REPOSITORY, "main_commit_at_start": head_sha,
                 "local_artifact_verified": local["verified"], "status": "verified"})
    return info


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="Read-only verification; do not create a release")
    parser.add_argument("--apksigner", help="Require APKSigner v2/v3 and signer-certificate validation")
    parser.add_argument("--report", type=Path, help="Write an independent verification report outside the repository")
    args = parser.parse_args()
    try:
        report = publish(args.apksigner, args.check)
    except (OSError, ValueError, PublicationError, subprocess.TimeoutExpired) as exc:
        print(json.dumps({"verified": False, "error": str(exc)}, indent=2), file=sys.stderr)
        return 2
    print(json.dumps(report, indent=2))
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    sys.exit(main())
