#!/usr/bin/env python3
"""Verify and package the signed APK with install instructions and the optional host setup tool."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import xml.etree.ElementTree as ET
import zipfile

root = Path(__file__).resolve().parents[1]
output = root / "outputs/tailpark"
apk = output / "Tailpark-1.104.1-byd.5.apk"
sdk = Path(os.environ.get("ANDROID_SDK_ROOT", Path.home() / "Library/Android/sdk"))
tools = sdk / "build-tools/36.0.0"
verification = subprocess.run([str(tools / "apksigner"), "verify", "--verbose", "--print-certs", str(apk)],
                              check=True, capture_output=True, text=True).stdout
badging = subprocess.run([str(tools / "aapt"), "dump", "badging", str(apk)],
                         check=True, capture_output=True, text=True).stdout
assert "package: name='dev.tailpark.byd'" in badging
assert "application-label:'Tailpark'" in badging
assert "sdkVersion:'26'" in badging
assert "arm64-v8a" in badging
assert "-byd.5" in badging
aar = root / "android/libs/libtailscale.aar"
go_inputs = list((root / "libtailscale").rglob("*.go")) + [root / "go.mod", root / "go.sum"]
assert aar.stat().st_mtime >= max(path.stat().st_mtime for path in go_inputs), "Go AAR is older than its source"
native = root / "android/build/intermediates/stripped_native_libs/release/stripReleaseDebugSymbols/out/lib/arm64-v8a/libgojni.so"
with zipfile.ZipFile(apk) as archive:
    assert archive.read("assets/byd-watchdog.sh") == (root / "android/src/main/assets/byd-watchdog.sh").read_bytes()
    assert archive.read("lib/arm64-v8a/libgojni.so") == native.read_bytes()
sha = hashlib.sha256(apk.read_bytes()).hexdigest()
reports = [ET.parse(path).getroot().attrib for path in
           (root / "android/build/test-results/testDebugUnitTest").glob("TEST-*.xml")]
tests = {key: sum(int(report.get(key, 0)) for report in reports)
         for key in ("tests", "failures", "errors", "skipped")}
assert tests["tests"] > 0 and tests["failures"] == 0 and tests["errors"] == 0
watchdog_tests = json.loads((output / "watchdog-tests.json").read_text())
assert watchdog_tests["tests"] > 0 and watchdog_tests["failures"] == 0 and watchdog_tests["errors"] == 0
(output / "SHA256.txt").write_text(f"{sha}  {apk.name}\n")
(output / "signature-verification.txt").write_text(verification)
(output / "package-metadata.txt").write_text(badging)
(output / "build-manifest.json").write_text(json.dumps({
    "package": "dev.tailpark.byd", "label": "Tailpark", "upstream": "1.104.1",
    "byd_version": 5, "apk": apk.name, "sha256": sha, "bytes": apk.stat().st_size,
    "implementation": "BYD parked connectivity with supervised helper and recurring network recovery",
    "unit_tests": tests,
    "watchdog_tests": watchdog_tests,
    "go_aar_sha256": hashlib.sha256(aar.read_bytes()).hexdigest(),
    "hardware_validation": "not yet tested on the head unit",
}, indent=2) + "\n")
shutil.copy2(root / "scripts/setup-byd-helper.py", output)
shutil.copy2(root / "docs/INSTALL.md", output / "README.md")
print(f"Verified signed APK: {apk} ({apk.stat().st_size / 1024 / 1024:.1f} MiB)")
