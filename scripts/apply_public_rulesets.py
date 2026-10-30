#!/usr/bin/env python3
"""Apply and verify Malty public branch/tag rulesets using GitHub CLI.

Requires: Python 3, GitHub CLI (gh), authenticated account with repository admin rights.
No GitHub Actions, Gradle, or paid CI are used.
Never updates an existing ruleset with a conflicting policy.
"""
from __future__ import annotations
import argparse
import json
from pathlib import Path
import shutil
import subprocess
import sys

REPO = "bggg-gizmo/malty"
ROOT = Path(__file__).resolve().parent.parent
RULESET_FILES = (
    ROOT / "rulesets" / "malty-main-branch-ruleset.json",
    ROOT / "rulesets" / "malty-release-tags-ruleset.json",
)

def gh_api(*args: str) -> object:
    proc = subprocess.run(
        ["gh", "api", "-H", "Accept: application/vnd.github+json", *args],
        capture_output=True, text=True, encoding="utf-8",
    )
    if proc.returncode:
        raise RuntimeError(
            "GitHub API command failed (exit %d): %s\n%s" %
            (proc.returncode, " ".join(args), proc.stderr.strip())
        )
    try:
        return json.loads(proc.stdout)
    except json.JSONDecodeError as exc:
        raise RuntimeError("GitHub returned invalid JSON: " + str(exc)) from exc

def verify(expected: dict, observed: dict) -> None:
    label = expected["name"]
    for field in ("name", "target", "enforcement"):
        if observed.get(field) != expected[field]:
            raise RuntimeError("%s: mismatched %s" % (label, field))
    if observed.get("bypass_actors", []) != expected.get("bypass_actors", []):
        raise RuntimeError(label + ": unexpected bypass actors")
    desired = expected["conditions"]["ref_name"]
    actual = observed.get("conditions", {}).get("ref_name", {})
    for field in ("include", "exclude"):
        if sorted(actual.get(field, [])) != sorted(desired[field]):
            raise RuntimeError("%s: mismatched ref_name.%s" % (label, field))
    actual_rules = observed.get("rules", [])
    required = expected["rules"]
    if len(actual_rules) != len(required):
        raise RuntimeError(label + ": different number of rules")
    for item in required:
        matches = [rule for rule in actual_rules if rule.get("type") == item["type"]]
        if len(matches) != 1:
            raise RuntimeError("%s: missing/duplicate rule %s" % (label, item["type"]))
        for key, value in item.get("parameters", {}).items():
            if matches[0].get("parameters", {}).get(key) != value:
                raise RuntimeError("%s: different %s.%s" % (label, item["type"], key))

def run(check_only: bool) -> None:
    if not shutil.which("gh"):
        raise RuntimeError("GitHub CLI (gh) is not installed or is absent from PATH")
    repo = gh_api("repos/" + REPO)
    if repo.get("full_name", "").lower() != REPO.lower() or repo.get("private"):
        raise RuntimeError("Refusing to act on a missing/private/unexpected repository")
    entries = gh_api("repos/" + REPO + "/rulesets?includes_parents=false")
    for filepath in RULESET_FILES:
        expected = json.loads(filepath.read_text(encoding="utf-8"))
        matches = [entry for entry in entries if entry.get("name") == expected["name"]]
        if len(matches) > 1:
            raise RuntimeError("Duplicate ruleset names: " + expected["name"])
        if matches:
            current = gh_api("repos/" + REPO + "/rulesets/" + str(matches[0]["id"]))
            verify(expected, current)
            print("VERIFIED existing:", expected["name"], "ID", matches[0]["id"])
            continue
        if check_only:
            raise RuntimeError("NOT INSTALLED: " + expected["name"])
        result = gh_api("--method", "POST", "repos/" + REPO + "/rulesets",
                        "--input", str(filepath))
        rule_id = result["id"]
        observed = gh_api("repos/" + REPO + "/rulesets/" + str(rule_id))
        verify(expected, observed)
        print("CREATED AND VERIFIED:", expected["name"], "ID", rule_id)
        entries.append({"id": rule_id, "name": expected["name"]})
    print("PUBLIC RULESET VERIFICATION PASSED — 2/2 active rulesets")

def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true",
                        help="Read-only verification; do not create missing rulesets")
    args = parser.parse_args()
    try:
        run(args.check)
    except (RuntimeError, OSError, KeyError, ValueError) as exc:
        print("PROTECTION NOT FULLY VERIFIED:", exc, file=sys.stderr)
        return 2
    return 0

if __name__ == "__main__":
    sys.exit(main())
