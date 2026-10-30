#!/usr/bin/env python3
"""Render release notes from verified build metadata."""
import json
from release_config import ROOT, configuration, release_name

release = configuration()
manifest = json.loads((ROOT / "outputs/tailpark/build-manifest.json").read_text())
print(f"Tailpark {release_name()} updates the BYD client to Tailscale {release['UPSTREAM_VERSION']}.")
print(f"\nUpstream: https://github.com/tailscale/tailscale-android/releases/tag/{release['UPSTREAM_TAG']}")
print("\nInstall the attached APK over your existing Tailpark installation to retain settings and login.")
print("\nChecks passed:")
print(f"- {manifest['unit_tests']['tests']} JVM unit tests")
print(f"- {manifest['watchdog_tests']['tests']} watchdog process tests")
print("- Kotlin formatting, critical release lint, APK signature and package verification")
print("\nAutomated checks do not validate head-unit reboot, extended parked connectivity or battery drain.")
print(f"\nAPK SHA-256: `{manifest['sha256']}`")
