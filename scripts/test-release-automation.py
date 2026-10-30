#!/usr/bin/env python3
"""Release gates must reject unstable versions, downgrades and failed merges."""
import importlib.util
import subprocess
import sys
import unittest
from unittest.mock import patch

from release_config import ROOT

spec = importlib.util.spec_from_file_location("updater", ROOT / "scripts/update-upstream.py")
updater = importlib.util.module_from_spec(spec)
spec.loader.exec_module(updater)


class ReleaseGates(unittest.TestCase):
    def setUp(self):
        patcher = patch.object(updater, "configuration", return_value={
            "UPSTREAM_VERSION": "1.104.4",
            "UPSTREAM_TAG": "1.104.4-taaa-gbbb",
            "BYD_VERSION": "6",
        })
        patcher.start()
        self.addCleanup(patcher.stop)

    def test_stable_android_tag(self):
        self.assertEqual(updater.stable_release({"tag_name": "1.104.4-t938213198-gb9d487fe6"}), "1.104.4")

    def test_unstable_or_unpublished_never_released(self):
        for release in (
            {"tag_name": "1.105.1-taaa-gbbb"},
            {"tag_name": "1.104.4-taaa-gbbb", "draft": True},
            {"tag_name": "1.104.4-taaa-gbbb", "prerelease": True},
            {"tag_name": "1.104.4-taaa-gbbb;echo invalid"},
        ):
            with self.subTest(release=release), self.assertRaises(ValueError):
                updater.stable_release(release)

    def test_numeric_version_order(self):
        self.assertGreater(updater.version_tuple("1.104.10"), updater.version_tuple("1.104.9"))

    def test_downgrade_stops_before_mutation(self):
        with patch.object(sys, "argv", ["update-upstream.py"]), patch.object(
            updater, "run", return_value='{"tag_name":"1.102.4-taaa-gbbb"}'
        ) as run, self.assertRaisesRegex(ValueError, "downgrade"):
            updater.main()
        self.assertEqual(run.call_count, 1)

    def test_dirty_checkout_stops_before_fetch(self):
        with patch.object(sys, "argv", ["update-upstream.py"]), patch.object(
            updater, "run", side_effect=['{"tag_name":"1.106.1-taaa-gbbb"}', " M go.mod"]
        ) as run, self.assertRaisesRegex(ValueError, "clean checkout"):
            updater.main()
        self.assertEqual(run.call_count, 2)

    def test_merge_failure_never_commits_or_publishes(self):
        with patch.object(sys, "argv", ["update-upstream.py"]), patch.object(
            updater, "run", side_effect=[
                '{"tag_name":"1.106.1-taaa-gbbb"}', "", "",
                subprocess.CalledProcessError(1, "git merge"),
            ]
        ) as run, self.assertRaises(subprocess.CalledProcessError):
            updater.main()
        self.assertEqual(run.call_args.args[:2], ("git", "merge"))


if __name__ == "__main__":
    unittest.main()
