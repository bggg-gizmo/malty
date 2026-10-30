"""Production prerelease publication, readback and conflict tests (no network)."""
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

SCRIPTS = Path(__file__).resolve().parents[1] / "scripts"
sys.path.insert(0, str(SCRIPTS))
spec = importlib.util.spec_from_file_location("malty_public_release", SCRIPTS / "publish_v092_prerelease.py")
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)

REAL_APK = Path(__file__).resolve().parents[1] / "downloads/v0.9.2/Malty-signed-v0.9.2.apk"
REAL_SUMS = Path(__file__).resolve().parents[1] / "downloads/v0.9.2/SHA256SUMS.txt"
REAL_NOTES = Path(__file__).resolve().parents[1] / "downloads/v0.9.2/RELEASE_NOTES.md"
HEAD = "a0095984e9e7011164cb3002b6b03e3ef3ab162a"


class GitHubMock:
    def __init__(self, existing=False, tag=False, tagsha=HEAD, wrong_apk=False):
        self.existing = existing
        self.tag = tag
        self.tagsha = tagsha
        self.wrong_apk = wrong_apk
        self.commands = []

    def release(self):
        if not self.existing:
            return None
        return {"tag_name": m.TAG, "prerelease": True, "draft": False,
                "html_url": "https://github.com/bggg-gizmo/malty/releases/tag/v0.9.2", "id": 9002,
                "assets": [{"name": m.ARTIFACT, "size": m.BYTE_COUNT},
                           {"name": "SHA256SUMS.txt", "size": REAL_SUMS.stat().st_size}]}

    def api(self, path, *, allow_missing=False):
        prefix = "repos/" + m.REPOSITORY + "/"
        if path == "repos/" + m.REPOSITORY:
            return {"full_name": m.REPOSITORY, "private": False, "default_branch": "main"}
        assert path.startswith(prefix), path
        endpoint = path[len(prefix):]
        if endpoint == "git/ref/heads/main":
            return {"object": {"sha": HEAD}}
        if endpoint == "git/ref/tags/" + m.TAG:
            return {"object": {"sha": self.tagsha, "type": "commit"}} if self.tag else None
        if endpoint.startswith("git/trees/"):
            return {"truncated": False, "tree": [
                {"type": "blob", "path": "downloads/v0.9.2/" + m.ARTIFACT,
                 "sha": "x" * 40 if self.wrong_apk else m.GIT_BLOB_SHA1, "size": m.BYTE_COUNT},
                {"type": "blob", "path": "downloads/v0.9.2/SHA256SUMS.txt", "sha": "s" * 40}]}
        if endpoint.startswith("compare/"):
            return {"behind_by": 0}
        if endpoint == "releases/tags/" + m.TAG:
            return self.release()
        raise AssertionError("Unexpected API path: " + path)

    def gh(self, *args, allow_missing=False):
        self.commands.append(args)
        if args[:2] == ("auth", "status"):
            return "authenticated"
        if args[:2] == ("release", "create"):
            if self.existing:
                raise AssertionError("Existing release should never be overwritten")
            assert "--prerelease" in args
            assert "--latest=false" in args
            assert "--target" in args and args[args.index("--target")+1] == HEAD
            self.existing = True
            self.tag = True
            self.tagsha = HEAD
            return "created"
        if args[:2] == ("release", "download"):
            folder = Path(args[args.index("--dir") + 1])
            name = args[args.index("--pattern") + 1]
            content = REAL_APK.read_bytes() if name == m.ARTIFACT else REAL_SUMS.read_bytes()
            (folder / name).write_bytes(content)
            return ""
        raise AssertionError("Unexpected command: " + repr(args))


class TestRelease(unittest.TestCase):
    def setUp(self):
        self.root_patches = [patch.object(m, "APK", REAL_APK), patch.object(m, "CHECKSUMS", REAL_SUMS),
                             patch.object(m, "NOTES", REAL_NOTES), patch.object(m.shutil, "which", return_value="/usr/bin/gh")]
        for item in self.root_patches:
            item.start()
            self.addCleanup(item.stop)

    def fake(self, **kwargs):
        obj = GitHubMock(**kwargs)
        self.addCleanup(patch.stopall)
        patch.object(m, "api", side_effect=obj.api).start()
        patch.object(m, "gh", side_effect=obj.gh).start()
        return obj

    def test_create_and_verify_actual_downloaded_asset(self):
        gh = self.fake()
        result = m.publish(None, False)
        self.assertTrue(result["download_verified"])
        self.assertEqual(result["sha256"], m.SHA256)
        self.assertEqual(sum(cmd[:2] == ("release", "create") for cmd in gh.commands), 1)

    def test_existing_release_is_read_only_and_idempotent(self):
        gh = self.fake(existing=True, tag=True)
        result = m.publish(None, False)
        self.assertTrue(result["download_verified"])
        self.assertFalse(any(cmd[:2] == ("release", "create") for cmd in gh.commands))

    def test_check_mode_does_not_create_missing_release(self):
        gh = self.fake()
        with self.assertRaisesRegex(m.PublicationError, "No native"):
            m.publish(None, True)
        self.assertFalse(any(cmd[:2] == ("release", "create") for cmd in gh.commands))

    def test_conflicting_tag_blocks_new_release(self):
        gh = self.fake(tag=True, tagsha="0"*40)
        with self.assertRaisesRegex(m.PublicationError, "different commit"):
            m.publish(None, False)
        self.assertFalse(any(cmd[:2] == ("release", "create") for cmd in gh.commands))

    def test_existing_release_can_predate_latest_main(self):
        self.fake(existing=True, tag=True, tagsha="0"*40)
        self.assertTrue(m.publish(None, True)["download_verified"])

    def test_tag_tree_wrong_apk_rejected(self):
        self.fake(existing=True, tag=True, wrong_apk=True)
        with self.assertRaisesRegex(m.PublicationError, "approved APK|verified v0.9.2 APK"):
            m.publish(None, False)

    def test_draft_release_not_claimed_published(self):
        release = {"tag_name": m.TAG, "prerelease": True, "draft": True, "assets": []}
        with self.assertRaisesRegex(m.PublicationError, "published testing prerelease"):
            m.validate_release_info(release, HEAD)

    def test_extra_asset_rejected(self):
        r = {"tag_name": m.TAG, "prerelease": True, "draft": False,
             "assets": [{"name": m.ARTIFACT, "size": m.BYTE_COUNT},
                        {"name": "SHA256SUMS.txt", "size": REAL_SUMS.stat().st_size},
                        {"name": "private.sqlite", "size": 4}]}
        with self.assertRaisesRegex(m.PublicationError, "asset list"):
            m.validate_release_info(r, HEAD)

    def test_missing_cli_fails_safe(self):
        with patch.object(m.shutil, "which", return_value=None):
            with self.assertRaisesRegex(m.PublicationError, "not installed"):
                m.ensure_environment()

    def test_exact_local_artifact_verified(self):
        self.assertTrue(m.verify_local(None)["verified"])


if __name__ == "__main__":
    unittest.main()
