#!/usr/bin/env python3
"""Tests for evidence interpretation; no car, network or ADB required."""
import importlib.util
import json
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("monitor", Path(__file__).with_name("monitor-byd.py"))
monitor = importlib.util.module_from_spec(spec)
spec.loader.exec_module(monitor)


class EvidenceTest(unittest.TestCase):
    def snapshot(self, uptime, sampled, heartbeat):
        return monitor.parse_snapshot(json.dumps({"sampledAtElapsedMs": sampled, "sampleAgeMs": 0})
                                      + f"\n{uptime} 42.0\n123 {heartbeat}\n")

    def test_saved_zero_age_does_not_hide_stale_helper(self):
        self.assertFalse(self.snapshot(200, 100000, 199)["fresh"])

    def test_old_snapshot_from_previous_boot_is_not_fresh(self):
        self.assertFalse(self.snapshot(10, 100000, 9)["fresh"])

    def test_stale_heartbeat_is_not_healthy(self):
        self.assertFalse(self.snapshot(200, 199000, 100)["fresh"])

    def test_current_snapshot_is_fresh(self):
        self.assertTrue(self.snapshot(200, 199000, 198)["fresh"])

    def test_relay_pong_is_success(self):
        self.assertTrue(monitor.ping_ok({"exit": 0, "output": "pong from car via DERP(sin) in 100ms"}))
        self.assertFalse(monitor.ping_ok({"exit": 1, "output": "timeout"}))

    def test_monitor_gap_is_reported_even_with_successful_samples(self):
        times = ["2026-10-15T00:00:00+00:00", "2026-10-15T00:10:00+00:00"]
        summary = monitor.summarize([{"at": value, "healthy_parked": True} for value in times],
                                    monitor.parse_time(times[0]), monitor.parse_time(times[1]), 60)
        self.assertEqual(summary["max_sample_gap_seconds"], 600)
        self.assertEqual(summary["gaps_over_expected_interval"], 1)
        self.assertEqual(summary["counts"]["healthy_parked"], 2)


if __name__ == "__main__":
    unittest.main()
