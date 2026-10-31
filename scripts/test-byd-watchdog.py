#!/usr/bin/env python3
# Copyright (c) Tailpark contributors
# SPDX-License-Identifier: BSD-3-Clause
"""Exercise the shipped POSIX watchdog with local fake Android commands."""
import json
import argparse
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import time
import unittest

ROOT = Path(__file__).resolve().parents[1]
BODY = (ROOT / "android/src/main/assets/byd-watchdog.sh").read_text()

HELPER = """
import json, os, pathlib, signal, sys, time
d = pathlib.Path(os.environ["BYD_FIXTURE"])
pid = os.getpid()
name = next(arg.split("=", 1)[1] for arg in sys.argv if arg.startswith("--nice-name="))
proc = d / "test-proc" / str(pid)
proc.mkdir(parents=True, exist_ok=True)
(proc / "cmdline").write_bytes(name.encode() + bytes([0]))
with (d / "events").open("a") as f:
    f.write(json.dumps({"pid": pid, "apk": os.environ["CLASSPATH"]}) + "\\n")
if (d / "app-disabled").exists():
    (d / "disabled").write_text("stopped")
    sys.exit(0)
(d / "helper.pid").write_text(str(pid))
(d / "helper.apk").write_text(os.environ["CLASSPATH"])
(d / "helper.identity").write_text("fixture-identity")
signal.signal(signal.SIGTERM, lambda *args: sys.exit(0))
while True:
    if not (d / "freeze-heartbeat").exists():
        seconds = (d / "test-clock").read_text().split(".")[0]
        tmp = d / ("heartbeat.tmp." + str(pid))
        tmp.write_text(str(pid) + " " + seconds + "\\n")
        tmp.replace(d / "heartbeat")
    time.sleep(0.05)
"""


class WatchdogTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="tsbyd-watchdog-")
        self.directory = Path(self.temporary.name)
        self.processes = []
        self.binary = self.directory / "bin"
        self.binary.mkdir()
        (self.directory / "test-proc").mkdir()
        (self.directory / "test-clock").write_text("10000.00 0.00\n")
        (self.directory / "test-apk").write_text("/fixture/first.apk\n")
        (self.directory / "version").write_text("first")
        (self.directory / "events").touch()
        self.write_executable("app_process", HELPER)
        self.write_executable("pm", """
import os, pathlib
print("package:" + (pathlib.Path(os.environ["BYD_FIXTURE"]) / "test-apk").read_text().strip()
      if (pathlib.Path(os.environ["BYD_FIXTURE"]) / "test-apk").read_text().strip() else "")
""")
        self.script = self.directory / "watchdog.sh"
        # Adapt only Android filesystem locations; the supervision logic is the shipped asset.
        self.body = BODY.replace("/proc/uptime", '"$DIR/test-clock"').replace(
            '"/proc/$1/cmdline"', '"$DIR/test-proc/$1/cmdline"'
        ).replace("/system/bin/sh", "/bin/sh")
        self.prefix = (
            "#!/bin/sh\nPKG=dev.tailpark.byd\nTOKEN=fixture-token\n"
            + "DIR='" + str(self.directory) + "'\nIDENTITY=fixture-identity\n"
        )
        self.script.write_text(self.prefix + self.body)
        self.environment = dict(os.environ, BYD_FIXTURE=str(self.directory))
        self.environment["PATH"] = str(self.binary) + os.pathsep + os.environ["PATH"]

    def tearDown(self):
        (self.directory / "disabled").touch()
        for process in self.processes:
            try:
                # Allow one five-second supervision interval plus child cleanup.
                process.wait(timeout=8)
            except subprocess.TimeoutExpired:
                pass
            try:
                os.killpg(process.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
            process.wait(timeout=4)
        self.temporary.cleanup()

    def write_executable(self, name, code):
        path = self.binary / name
        path.write_text("#!" + sys.executable + "\n" + code)
        path.chmod(0o700)

    def eventually(self, condition, timeout=12):
        until = time.monotonic() + timeout
        while time.monotonic() < until:
            try:
                if condition():
                    return
            except FileNotFoundError:
                pass
            time.sleep(0.05)
        log = (self.directory / "helper.log").read_text() if (self.directory / "helper.log").exists() else ""
        self.fail("Watchdog condition timed out\n" + log)

    def events(self):
        return [json.loads(line) for line in (self.directory / "events").read_text().splitlines()]

    def start(self):
        process = subprocess.Popen(
            ["/bin/sh", str(self.script)], env=self.environment,
            stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
            start_new_session=True,
        )
        self.processes.append(process)
        proc = self.directory / "test-proc" / str(process.pid)
        proc.mkdir(exist_ok=True)
        (proc / "cmdline").write_bytes(str(self.script).encode() + bytes([0]))
        self.eventually(lambda: process.poll() == 0 or (
            (self.directory / "watchdog.lock/pid").read_text().strip() == str(process.pid)
        ))
        return process

    def advance(self, seconds):
        (self.directory / "test-clock").write_text(str(10000 + seconds) + ".00 0.00\n")

    def test_shipped_script_has_valid_posix_syntax(self):
        subprocess.run(["/bin/sh", "-n", str(ROOT / "android/src/main/assets/byd-watchdog.sh")], check=True)

    def test_crash_recovers_and_duplicate_supervisor_does_not_fork_another_helper(self):
        first = self.start()
        self.eventually(lambda: len(self.events()) == 1)
        duplicate = self.start()
        self.assertEqual(0, duplicate.wait(timeout=4))
        self.assertEqual(1, len(self.events()))
        os.kill(self.events()[0]["pid"], signal.SIGKILL)
        self.eventually(lambda: len(self.events()) == 2)
        self.assertNotEqual(self.events()[0]["pid"], self.events()[1]["pid"])
        (self.directory / "disabled").touch()
        self.assertEqual(0, first.wait(timeout=8))
        self.assertEqual(2, len(self.events()))

    def test_disable_during_backoff_prevents_respawn(self):
        process = self.start()
        self.eventually(lambda: len(self.events()) == 1)
        os.kill(self.events()[0]["pid"], signal.SIGKILL)
        self.eventually(lambda: "retrying" in (self.directory / "helper.log").read_text())
        (self.directory / "disabled").touch()
        self.assertEqual(0, process.wait(timeout=4))
        self.assertEqual(1, len(self.events()))

    def test_idle_expiry_stops_and_stays_terminal_after_supervisor_restart(self):
        process = self.start()
        self.eventually(lambda: len(self.events()) == 1)
        (self.directory / "idle-expired").touch()
        self.assertEqual(0, process.wait(timeout=8))
        replacement = self.start()
        self.assertEqual(0, replacement.wait(timeout=4))
        self.assertEqual(1, len(self.events()))
        self.assertFalse((self.directory / "disabled").exists())

    def test_idle_expiry_during_backoff_prevents_respawn(self):
        process = self.start()
        self.eventually(lambda: len(self.events()) == 1)
        os.kill(self.events()[0]["pid"], signal.SIGKILL)
        self.eventually(lambda: "retrying" in (self.directory / "helper.log").read_text())
        (self.directory / "idle-expired").touch()
        self.assertEqual(0, process.wait(timeout=4))
        self.assertEqual(1, len(self.events()))

    def test_apk_update_reloads_even_with_fresh_heartbeats(self):
        self.start()
        self.eventually(lambda: len(self.events()) == 1)
        (self.directory / "test-apk").write_text("/fixture/second.apk\n")
        self.advance(16)
        self.eventually(lambda: len(self.events()) == 2)
        self.assertEqual("/fixture/second.apk", self.events()[1]["apk"])

    def test_watchdog_update_executes_new_script(self):
        self.start()
        self.eventually(lambda: len(self.events()) == 1)
        self.script.write_text(self.prefix + 'printf updated > "$DIR/update-applied"\n' + self.body)
        (self.directory / "version").write_text("second")
        self.advance(16)
        self.eventually(lambda: (self.directory / "update-applied").exists())
        self.eventually(lambda: len(self.events()) == 2)

    def test_live_but_stuck_helper_is_restarted(self):
        self.start()
        self.eventually(lambda: (self.directory / "heartbeat").exists())
        (self.directory / "freeze-heartbeat").touch()
        time.sleep(0.1)
        self.advance(100)
        self.eventually(lambda: len(self.events()) == 2)
        (self.directory / "freeze-heartbeat").unlink()

    def test_replacement_supervisor_adopts_live_orphan(self):
        first = self.start()
        self.eventually(lambda: len(self.events()) == 1)
        child = self.events()[0]["pid"]
        os.kill(first.pid, signal.SIGKILL)
        first.wait(timeout=4)
        self.start()
        self.eventually(lambda: (self.directory / "watchdog.lock/pid").read_text().strip() != str(first.pid))
        time.sleep(0.2)
        self.assertEqual(1, len(self.events()))
        self.assertEqual(child, int((self.directory / "helper.pid").read_text()))

    def test_uninstall_stops_without_retrying(self):
        process = self.start()
        self.eventually(lambda: len(self.events()) == 1)
        (self.directory / "test-apk").write_text("")
        self.advance(16)
        self.assertEqual(0, process.wait(timeout=8))
        self.assertEqual(1, len(self.events()))

    def test_saved_disconnect_is_honored_before_helper_starts_work(self):
        (self.directory / "app-disabled").touch()
        process = self.start()
        self.assertEqual(0, process.wait(timeout=6))
        self.assertEqual(1, len(self.events()))
        self.assertFalse((self.directory / "heartbeat").exists())


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()
    result = unittest.TextTestRunner(verbosity=2).run(unittest.defaultTestLoader.loadTestsFromTestCase(WatchdogTests))
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps({
            "tests": result.testsRun, "failures": len(result.failures),
            "errors": len(result.errors), "skipped": len(result.skipped),
        }, indent=2) + "\n")
    sys.exit(0 if result.wasSuccessful() else 1)
