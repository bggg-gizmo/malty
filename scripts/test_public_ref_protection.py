#!/usr/bin/env python3
"""Offline checks for the GitHub public tag/protection orchestrator."""
from __future__ import annotations
import importlib.util
from pathlib import Path
from unittest import TestCase, main
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location("tag_and_protect_public", Path(__file__).with_name("tag_and_protect_public.py"))
tags = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(tags)


class PublicProtectionTests(TestCase):
    def test_pinned_versions(self):
        self.assertEqual([(t["name"], t["code"]) for t in tags.TAGS],
                         [("v0.9.2", 11), ("v0.9.3-rc.1", 12)])
        self.assertNotIn("v0.9.3", [t["name"] for t in tags.TAGS])

    def test_existing_lightweight_tag_is_resolved(self):
        with patch.object(tags, "gh", return_value={"object": {"type": "commit", "sha": "a" * 40}}):
            self.assertEqual(tags.read_tag_target("v0.9.2"), "a" * 40)

    def test_existing_annotated_tag_is_resolved(self):
        with patch.object(tags, "gh", side_effect=[
            {"object": {"type": "tag", "sha": "a" * 40}},
            {"tag": "v0.9.2", "object": {"type": "commit", "sha": "b" * 40}},
        ]):
            self.assertEqual(tags.read_tag_target("v0.9.2"), "b" * 40)

    def test_rejects_tag_of_another_name(self):
        with patch.object(tags, "gh", side_effect=[
            {"object": {"type": "tag", "sha": "a" * 40}},
            {"tag": "v9.9.9", "object": {"type": "commit", "sha": "b" * 40}},
        ]):
            with self.assertRaises(tags.ProtectionError):
                tags.read_tag_target("v0.9.2")

    def test_tag_name_input_is_constrained(self):
        with self.assertRaises(tags.ProtectionError):
            tags.read_tag_target("../../main")

    def test_existing_tag_never_moved(self):
        tag = tags.TAGS[0]
        with patch.object(tags, "read_tag_target", return_value="a" * 40), \
             patch.object(tags, "gh") as gh:
            with self.assertRaises(tags.ProtectionError):
                tags.create_annotated_tag(tag)
            gh.assert_not_called()

    def test_existing_matching_tag_is_idempotent(self):
        tag = tags.TAGS[0]
        with patch.object(tags, "read_tag_target", return_value=tag["commit"]), \
             patch.object(tags, "gh") as gh:
            tags.create_annotated_tag(tag)
            gh.assert_not_called()

    def test_annotated_tag_creates_object_then_ref(self):
        tag = tags.TAGS[0]
        with patch.object(tags, "read_tag_target", side_effect=[None, tag["commit"]]), \
             patch.object(tags, "gh", side_effect=[{"sha": "c" * 40}, {"ref": "refs/tags/" + tag["name"]}]) as gh:
            tags.create_annotated_tag(tag)
            self.assertEqual(gh.call_count, 2)
            self.assertTrue(any(arg.endswith("/git/tags") for arg in gh.call_args_list[0].args))
            self.assertTrue(any(arg.endswith("/git/refs") for arg in gh.call_args_list[1].args))
            self.assertNotIn("--force", str(gh.call_args_list))

    def test_snapshot_requires_approved_blob_hashes(self):
        tag = tags.TAGS[0]
        with patch.object(tags, "gh", side_effect=[
            {"status": "ahead", "behind_by": 0},
            {"truncated": False, "tree": [
                {"path": tags.APK_PATH, "type": "blob", "sha": tags.APK_SHA, "size": tags.APK_SIZE},
                {"path": tags.BUILD_PATH, "type": "blob", "sha": tag["build_blob"]},
            ]},
        ]):
            tags.verify_snapshot(tag, "f" * 40)

    def test_snapshot_rejects_wrong_signing_blob(self):
        tag = tags.TAGS[0]
        with patch.object(tags, "gh", side_effect=[
            {"status": "ahead", "behind_by": 0},
            {"truncated": False, "tree": [
                {"path": tags.APK_PATH, "type": "blob", "sha": "f" * 40, "size": tags.APK_SIZE},
                {"path": tags.BUILD_PATH, "type": "blob", "sha": tag["build_blob"]},
            ]},
        ]):
            with self.assertRaises(tags.ProtectionError):
                tags.verify_snapshot(tag, "f" * 40)

    def test_snapshot_rejects_divergent_history(self):
        with patch.object(tags, "gh", return_value={"status": "diverged", "behind_by": 2}):
            with self.assertRaises(tags.ProtectionError):
                tags.verify_snapshot(tags.TAGS[0], "f" * 40)

    def test_snapshot_rejects_truncated_tree(self):
        with patch.object(tags, "gh", side_effect=[
            {"status": "ahead", "behind_by": 0}, {"truncated": True, "tree": []}
        ]):
            with self.assertRaises(tags.ProtectionError):
                tags.verify_snapshot(tags.TAGS[0], "f" * 40)


if __name__ == "__main__":
    main(verbosity=2)
