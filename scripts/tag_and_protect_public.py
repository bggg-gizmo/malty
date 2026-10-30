#!/usr/bin/env python3
"""Pin Malty public source tags and activate public branch/tag protections.

Requires an authenticated, repository-admin GitHub CLI (`gh auth login`).
Writes only the two approved, previously absent Git tag refs and the two
repository rulesets. Never force-updates refs or changes branches or releases.

Usage from the public Malty repo root:
  python3 scripts/tag_and_protect_public.py --check
  python3 scripts/tag_and_protect_public.py --apply
"""
from __future__ import annotations

import argparse
import json
import re
import shutil
import subprocess
import sys
from pathlib import Path
from urllib.parse import quote

REPOSITORY = "bggg-gizmo/malty"
ROOT = Path(__file__).resolve().parent.parent
APK_PATH = "downloads/v0.9.2/Malty-signed-v0.9.2.apk"
APK_SHA = "25ad469fe17db0a7cac26597d2afb8b22ae52403"
APK_SIZE = 555143
BUILD_PATH = "app/CanceledAgent-Android-IndependentProject-v0.9.2/scripts/build-direct.sh"
TAGS = (
    {"name": "v0.9.2",
     "commit": "9a1c4439dfe9d05036c051fb87a4be7e8fa317c3",
     "build_blob": "2dd3c7e8624789ff19fba3d3ec4967a9bac445da",
     "version": "0.9.2", "code": 11,
     "message": "Malty v0.9.2 public Android testing source and signed testing APK; not production certified"},
    {"name": "v0.9.3-rc.1",
     "commit": "f81d17c5381ecf4d7f36e40ae5486438207bbb46",
     "build_blob": "1304976df99c361c49d190b8b7ea4dd54f5bd391",
     "version": "0.9.3", "code": 12,
     "message": "Malty v0.9.3 hardened source candidate only; no official release-signed APK or GitHub Release"},
)

class ProtectionError(RuntimeError):
    pass


def gh(*arguments: str, allow_not_found: bool = False) -> object | None:
    proc = subprocess.run(("gh", "api", "-H", "Accept: application/vnd.github+json", *arguments),
                          capture_output=True, text=True, encoding="utf-8", timeout=90)
    if proc.returncode:
        message = (proc.stderr or proc.stdout).strip()
        if allow_not_found and ("HTTP 404" in message or "Not Found" in message):
            return None
        raise ProtectionError("GitHub API failed: " + message)
    try:
        return json.loads(proc.stdout)
    except json.JSONDecodeError as exc:
        raise ProtectionError("GitHub API response is not valid JSON") from exc


def object_or_fail(value: object, label: str) -> dict:
    if not isinstance(value, dict):
        raise ProtectionError(label + ": expected JSON object")
    return value


def verify_current_repository() -> str:
    repo = object_or_fail(gh("repos/" + REPOSITORY), "repository")
    if repo.get("full_name") != REPOSITORY or repo.get("private") or repo.get("default_branch") != "main":
        raise ProtectionError("unexpected repository, visibility or default branch")
    head = object_or_fail(gh("repos/" + REPOSITORY + "/git/ref/heads/main"), "main ref")
    if head.get("object", {}).get("type") != "commit":
        raise ProtectionError("main does not point directly to a commit")
    sha = head["object"].get("sha", "")
    if not re.fullmatch(r"[0-9a-f]{40}", sha):
        raise ProtectionError("main commit SHA malformed")
    return sha


def verify_snapshot(tag: dict, main_head: str) -> None:
    sha = tag["commit"]
    comparison = object_or_fail(gh(f"repos/{REPOSITORY}/compare/{sha}...{main_head}"), "compare")
    if comparison.get("status") not in ("ahead", "identical") or comparison.get("behind_by") != 0:
        raise ProtectionError(tag["name"] + " target is not an ancestor of main")
    response = object_or_fail(gh(f"repos/{REPOSITORY}/git/trees/{sha}?recursive=1"), "tree")
    if response.get("truncated") or not isinstance(response.get("tree"), list):
        raise ProtectionError(tag["name"] + " source tree is incomplete")
    blobs = {x["path"]: x for x in response["tree"] if x.get("type") == "blob"}
    apk = blobs.get(APK_PATH, {})
    build = blobs.get(BUILD_PATH, {})
    if apk.get("sha") != APK_SHA or apk.get("size") != APK_SIZE:
        raise ProtectionError(tag["name"] + " lacks pinned original v0.9.2 signed testing APK")
    if build.get("sha") != tag["build_blob"]:
        raise ProtectionError(tag["name"] + " direct-builder version is not the approved Git blob")


def read_tag_target(name: str) -> str | None:
    if not re.fullmatch(r"v[0-9A-Za-z.\-]+", name):
        raise ProtectionError("unsafe tag name")
    ref = gh("repos/" + REPOSITORY + "/git/ref/tags/" + quote(name, safe=""), allow_not_found=True)
    if ref is None:
        return None
    obj = object_or_fail(ref, "tag ref").get("object", {})
    if obj.get("type") == "commit":
        return obj.get("sha")
    if obj.get("type") == "tag":
        annotated = object_or_fail(gh("repos/" + REPOSITORY + "/git/tags/" + obj["sha"]), "annotated tag")
        target = annotated.get("object", {})
        if target.get("type") != "commit" or annotated.get("tag") != name:
            raise ProtectionError(name + " does not contain a direct annotated commit target")
        return target.get("sha")
    raise ProtectionError(name + ": unsupported tag object type")


def create_annotated_tag(tag: dict) -> None:
    name, target = tag["name"], tag["commit"]
    old = read_tag_target(name)
    if old is not None:
        if old != target:
            raise ProtectionError(name + " already points to a different commit; refusing to move it")
        print("VERIFIED existing tag", name, target)
        return
    obj = object_or_fail(gh("--method", "POST", "repos/" + REPOSITORY + "/git/tags",
                            "-f", "tag=" + name, "-f", "message=" + tag["message"],
                            "-f", "object=" + target, "-f", "type=commit"), "created tag object")
    sha = obj.get("sha", "")
    if not re.fullmatch(r"[0-9a-f]{40}", sha):
        raise ProtectionError("GitHub did not return a valid annotated tag object SHA")
    # A tag object by itself is not a visible/published Git tag; create refs/tags/ explicitly.
    gh("--method", "POST", "repos/" + REPOSITORY + "/git/refs",
       "-f", "ref=refs/tags/" + name, "-f", "sha=" + sha)
    if read_tag_target(name) != target:
        raise ProtectionError(name + " Git reference was not verified after creation")
    print("CREATED AND VERIFIED annotated tag", name, target)


def check_or_create_tags(check_only: bool, main_head: str) -> None:
    for tag in TAGS:
        verify_snapshot(tag, main_head)
        if check_only:
            old = read_tag_target(tag["name"])
            if old is None:
                raise ProtectionError("NOT INSTALLED: " + tag["name"])
            if old != tag["commit"]:
                raise ProtectionError(tag["name"] + " points to wrong commit")
            print("VERIFIED tag", tag["name"], tag["commit"])
        else:
            create_annotated_tag(tag)


def ruleset_step(check_only: bool) -> None:
    rules_script = ROOT / "scripts" / "apply_public_rulesets.py"
    if not rules_script.is_file():
        raise ProtectionError("Missing existing public ruleset activation helper: " + str(rules_script))
    cmd = [sys.executable, str(rules_script)]
    if check_only:
        cmd.append("--check")
    proc = subprocess.run(cmd, check=False)
    if proc.returncode:
        raise ProtectionError("Branch/tag rulesets were not activated and verified (exit %d)" % proc.returncode)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--check", action="store_true", help="Read-only check of both version tags and both live rulesets")
    group.add_argument("--apply", action="store_true", help="Create missing tags and activate rulesets, never move existing refs")
    args = parser.parse_args(argv)
    try:
        if not shutil.which("gh"):
            raise ProtectionError("GitHub CLI is not installed; see https://cli.github.com/")
        auth = subprocess.run(("gh", "auth", "status"), capture_output=True, text=True, timeout=30)
        if auth.returncode:
            raise ProtectionError("GitHub CLI authentication unavailable; run gh auth login")
        head = verify_current_repository()
        # Preflight all snapshots and existing tag refs before starting any mutations.
        for tag in TAGS:
            verify_snapshot(tag, head)
            existing = read_tag_target(tag["name"])
            if existing is not None and existing != tag["commit"]:
                raise ProtectionError("Conflicting existing tag " + tag["name"])
        check_or_create_tags(not args.apply, head)
        ruleset_step(not args.apply)
        print("VERIFIED: two pinned tags, active main protection and immutable v* tag policy")
        return 0
    except (ProtectionError, OSError, subprocess.TimeoutExpired, KeyError, ValueError) as exc:
        print("TAG/PROTECTION SETUP INCOMPLETE:", exc, file=sys.stderr)
        return 2

if __name__ == "__main__":
    raise SystemExit(main())
