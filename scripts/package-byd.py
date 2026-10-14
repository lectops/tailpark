#!/usr/bin/env python3
"""Verify and package the signed APK with install instructions and the optional host setup tool."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]
output = root / "outputs/tailscale-byd"
apk = output / "Tailscale-BYD-1.104.1-byd.1.apk"
sdk = Path(os.environ.get("ANDROID_SDK_ROOT", Path.home() / "Library/Android/sdk"))
tools = sdk / "build-tools/36.0.0"
verification = subprocess.run([str(tools / "apksigner"), "verify", "--verbose", "--print-certs", str(apk)],
                              check=True, capture_output=True, text=True).stdout
badging = subprocess.run([str(tools / "aapt"), "dump", "badging", str(apk)],
                         check=True, capture_output=True, text=True).stdout
assert "package: name='dev.tailscale.byd'" in badging
assert "application-label:'Tailscale BYD'" in badging
assert "sdkVersion:'26'" in badging
assert "arm64-v8a" in badging
sha = hashlib.sha256(apk.read_bytes()).hexdigest()
reports = [ET.parse(path).getroot().attrib for path in
           (root / "android/build/test-results/testDebugUnitTest").glob("TEST-*.xml")]
tests = {key: sum(int(report.get(key, 0)) for report in reports)
         for key in ("tests", "failures", "errors", "skipped")}
assert tests["tests"] > 0 and tests["failures"] == 0 and tests["errors"] == 0
(output / "SHA256.txt").write_text(f"{sha}  {apk.name}\n")
(output / "signature-verification.txt").write_text(verification)
(output / "package-metadata.txt").write_text(badging)
(output / "build-manifest.json").write_text(json.dumps({
    "package": "dev.tailscale.byd", "label": "Tailscale BYD", "upstream": "1.104.1",
    "byd_version": 1, "apk": apk.name, "sha256": sha, "bytes": apk.stat().st_size,
    "implementation": "BYD ACC-off wake/backlight sequence with detached shell helper",
    "unit_tests": tests,
    "hardware_validation": "not yet tested on the head unit",
}, indent=2) + "\n")
shutil.copy2(root / "scripts/setup-byd-helper.py", output)
shutil.copy2(root / "docs/BYD-INSTALL.md", output / "README.md")
print(f"Verified signed APK: {apk} ({apk.stat().st_size / 1024 / 1024:.1f} MiB)")
