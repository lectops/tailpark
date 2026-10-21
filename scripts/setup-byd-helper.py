#!/usr/bin/env python3
"""Provision the APK's existing helper through an already authorized host ADB connection."""
import argparse
import base64
import re
import subprocess

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--serial", required=True, help="ADB serial of the head unit")
args = parser.parse_args()
adb = ["adb", "-s", args.serial]
result = subprocess.run(adb + [
    "shell", "am", "broadcast", "--receiver-foreground", "-n",
    "dev.tailpark.byd/com.tailscale.ipn.byd.BydRecoveryReceiver",
    "-a", "dev.tailpark.byd.BYD_BOOTSTRAP",
], check=True, capture_output=True, text=True)
match = re.search(r'data="([A-Za-z0-9+/=]+)"', result.stdout)
if not match:
    raise SystemExit("Open Tailpark, log in, connect, and enable parked connectivity first.")
try:
    command = base64.b64decode(match.group(1), validate=True).decode()
except (ValueError, UnicodeError):
    raise SystemExit("The APK did not return a valid helper launch ticket.")
if not command.startswith("nohup /system/bin/app_process "):
    raise SystemExit("The APK did not return its expected helper launch command.")
# Capture output so neither the launch token nor shell command is printed.
subprocess.run(adb + ["shell", command], check=True, capture_output=True, text=True)
print("Parked connectivity helper launched. Check its status in Tailpark Settings.")
